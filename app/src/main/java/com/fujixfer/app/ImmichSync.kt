package com.fujixfer.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.fujixfer.core.ImmichClient
import java.io.IOException

/**
 * Sends what the app saved in Pictures/Movies/Download "Fujifilm" to the user's Immich server.
 *
 * Nothing is asked of the camera: the files are already on the phone. Each one is hashed (SHA-1) and the server
 * is asked whether it has it; only a file it lacks is uploaded, so photos that are already in Immich (from the
 * phone's own backup, from XApp, from an earlier run) are never sent twice. The work runs as a job with a
 * network condition: while the phone is on the camera's Wi-Fi it waits, and it runs when the phone is back on a
 * normal network (by default an unmetered one: home Wi-Fi, not mobile data).
 */
object ImmichSync {
    private const val PREFS = "fuji"
    const val PREF_ENABLED = "immich_enabled"
    const val PREF_URL = "immich_url"
    const val PREF_KEY = "immich_key"
    const val PREF_WIFI_ONLY = "immich_wifi_only"
    private const val PREF_LOGIN = "immich_use_login"
    private const val PREF_EMAIL = "immich_email"
    private const val PREF_TOKEN = "immich_token"
    private const val PREF_SERVER = "immich_server_info"
    /** Camera files ("name|date", as the "done" marks) the user asked to send with "Carica". */
    private const val PREF_QUEUE = "immich_queue"
    /** Camera files known to be on the server (sent, or found there already). */
    private const val PREF_ON_SERVER = "immich_on_server"
    /** "name|date\tcontent uri": which media store item holds a camera file (written when it is saved). */
    private const val PREF_MEDIA = "immich_media"
    private const val PREF_SYNCED = "immich_synced"
    private const val PREF_LAST = "immich_last"
    private const val JOB_ID = 4711
    private const val DEVICE_ID = "fuji-transfer"
    private val FOLDERS = listOf("Pictures/Fujifilm", "Movies/Fujifilm", "Download/Fujifilm")

    /** What the settings dialog shows: the last outcome, and progress while a run is going. */
    @Volatile
    var progress: String = ""

    /** Goes up when the queue or the "on Immich" marks change: the gallery re-reads them. */
    @Volatile
    var version: Int = 0

    private fun set(c: Context, pref: String): MutableSet<String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(pref, emptySet())!!.toHashSet()

    private fun putSet(c: Context, pref: String, v: Set<String>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(pref, HashSet(v)).apply()
        version++
    }

    fun queued(c: Context): Set<String> = set(c, PREF_QUEUE)

    fun onServer(c: Context): Set<String> = set(c, PREF_ON_SERVER)

    /** "Carica": these camera files go to Immich as soon as they are on the phone and the network allows it. */
    @Synchronized
    fun enqueue(c: Context, keys: Collection<String>) {
        val q = set(c, PREF_QUEUE)
        val there = onServer(c)
        val todo = keys.filter { it !in there }
        q.addAll(todo)
        putSet(c, PREF_QUEUE, q)
        DebugLog.add("Immich: ${todo.size} file in coda da inviare")
    }

    /** "Annulla" after "Carica" (files already sent stay sent). */
    @Synchronized
    fun dequeue(c: Context, keys: Collection<String>) {
        putSet(c, PREF_QUEUE, set(c, PREF_QUEUE) - keys.toSet())
    }

    /** Called when a camera file has been saved in the media store. */
    @Synchronized
    fun rememberMedia(c: Context, cameraKey: String, uri: Uri) {
        val m = set(c, PREF_MEDIA).filterTo(HashSet()) { it.substringBefore('\t') != cameraKey }
        m.add(cameraKey + "\t" + uri)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(PREF_MEDIA, m).apply()
    }

    @Synchronized
    private fun markOnServer(c: Context, key: String) {
        putSet(c, PREF_ON_SERVER, set(c, PREF_ON_SERVER) + key)
        val q = set(c, PREF_QUEUE)
        if (q.remove(key)) putSet(c, PREF_QUEUE, q)
    }

    /**
     * [useLogin]: email and password as in the web app; the password is used once to get [token] and not kept.
     * Otherwise [key] is an API key (Immich → Impostazioni account → Chiavi API).
     */
    class Settings(
        val enabled: Boolean, val url: String, val key: String, val wifiOnly: Boolean,
        val useLogin: Boolean = false, val email: String = "", val token: String = "",
    ) {
        val credential get() = if (useLogin) token else key
        /** Address and access are set: "Carica" can work even with [enabled] (send after every download) off. */
        val configured get() = url.isNotBlank() && credential.isNotBlank()
        val ready get() = enabled && configured
    }

    /** What the status card shows after a check: "Immich 1.118 · 12.480 foto" (empty = never checked). */
    fun serverInfo(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_SERVER, "") ?: ""

    fun saveServerInfo(c: Context, info: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_SERVER, info).apply()
    }

    /**
     * Checks address and access; with [password] (email mode) logs in first. Returns the settings with the new
     * token and the status line. Network: call off the main thread.
     */
    fun verify(s: Settings, password: String): Pair<Settings, String> {
        var now = s
        if (s.useLogin && password.isNotEmpty()) now = Settings(s.enabled, s.url, s.key, s.wifiOnly, true, s.email, ImmichClient.login(s.url, s.email, password))
        if (now.credential.isBlank()) throw IOException(if (now.useLogin) "inserisci email e password" else "inserisci la chiave API")
        val api = client(now)
        api.test()
        val v = api.version().split('.').take(2).joinToString(".")
        val n = api.assetCount()
        val count = if (n < 0) "" else " · " + java.text.NumberFormat.getIntegerInstance(java.util.Locale.ITALY).format(n) + " foto"
        return now to "Immich $v$count"
    }

    private class Item(val uri: Uri, val name: String, val size: Long, val modifiedMs: Long) {
        val key get() = "$name|$size|${modifiedMs / 1000}"
        var cameraKey: String? = null
    }

    class Outcome(val retry: Boolean, val summary: String)

    fun settings(c: Context): Settings {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            p.getBoolean(PREF_ENABLED, false), p.getString(PREF_URL, "") ?: "", p.getString(PREF_KEY, "") ?: "",
            p.getBoolean(PREF_WIFI_ONLY, true),
            p.getBoolean(PREF_LOGIN, (p.getString(PREF_KEY, "") ?: "").isEmpty()) /* an API key set before: key mode */, p.getString(PREF_EMAIL, "") ?: "", p.getString(PREF_TOKEN, "") ?: "",
        )
    }

    fun save(c: Context, s: Settings) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, s.enabled).putString(PREF_URL, s.url.trim()).putString(PREF_KEY, s.key.trim())
            .putBoolean(PREF_WIFI_ONLY, s.wifiOnly)
            .putBoolean(PREF_LOGIN, s.useLogin).putString(PREF_EMAIL, s.email.trim()).putString(PREF_TOKEN, s.token).apply()
    }

    fun lastSummary(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_LAST, "") ?: ""

    fun client(s: Settings) = ImmichClient(s.url, s.credential, DEVICE_ID, s.useLogin)

    /** Asks the system to run the sync when the network allows it. Replaces a job already waiting. */
    fun schedule(c: Context) {
        val s = settings(c)
        val js = c.getSystemService(JobScheduler::class.java)
        if (!s.configured || (!s.enabled && queued(c).isEmpty())) {
            js.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, ImmichJob::class.java))
            .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        js.schedule(job)
        DebugLog.add("Immich: sincronizzazione in coda (${if (s.wifiOnly) "solo rete senza limiti" else "qualsiasi rete"})")
    }

    /** One pass: every unsent file of the app's folders, newest first. Runs on a background thread. */
    fun run(c: Context, stopped: () -> Boolean): Outcome {
        val s = settings(c)
        if (!s.configured) return Outcome(false, "Immich non è configurato")
        val synced = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(PREF_SYNCED, emptySet())!!.toHashSet()
        val all = items(c)
        // which camera file each item is: the link saved at download, else the file name if it is unique
        val byUri = set(c, PREF_MEDIA).associate { it.substringAfter('\t') to it.substringBefore('\t') }
        val queue = queued(c)
        val names = all.groupingBy { it.name }.eachCount()
        val queueByName = queue.groupBy { it.substringBefore('|') }
        for (x in all) {
            x.cameraKey = byUri[x.uri.toString()]
                ?: queueByName[x.name]?.singleOrNull()?.takeIf { names[x.name] == 1 }
        }
        fun asked(x: Item) = x.cameraKey?.let { it in queue } == true
        // asked with "Carica" and already sent in an earlier run: only the mark was missing
        for (x in all) if (asked(x) && x.key in synced) markOnServer(c, x.cameraKey!!)
        val todo = all.filter { it.key !in synced && (s.enabled || asked(it)) }
        if (todo.isEmpty()) return finish(c, false, "Immich: niente da inviare")
        val api = client(s)
        var uploaded = 0
        var already = 0
        for ((i, item) in todo.withIndex()) {
            if (stopped()) return finish(c, true, "Immich: interrotto (inviati $uploaded, già presenti $already)", synced)
            progress = "Immich ${i + 1}/${todo.size}: ${item.name}"
            try {
                val src = object : ImmichClient.Source {
                    override fun open() = c.contentResolver.openInputStream(item.uri) ?: throw IOException("File non apribile")
                    override fun length() = item.size
                }
                when (api.sync(item.name, src, item.modifiedMs, item.modifiedMs)) {
                    ImmichClient.Outcome.UPLOADED -> uploaded++
                    ImmichClient.Outcome.DUPLICATE -> already++
                }
                synced.add(item.key)
                remember(c, synced)
                item.cameraKey?.let { markOnServer(c, it) }
            } catch (e: ImmichClient.ImmichException) {
                if (e.permanent) return finish(c, false, "Immich: ${item.name}: ${e.message}", synced) // wrong key/address: not retried
                return finish(c, true, "Immich: ${e.message}, riprovo più tardi", synced)
            } catch (e: IOException) {
                return finish(c, true, "Immich non raggiungibile (${e.message}): riprovo più tardi", synced)
            } catch (e: SecurityException) {
                DebugLog.add("Immich: ${item.name} non leggibile (${e.message}), lo salto")
            }
        }
        return finish(c, false, "Immich: inviati $uploaded, già presenti $already", synced)
    }

    private fun finish(c: Context, retry: Boolean, text: String, synced: Set<String>? = null): Outcome {
        synced?.let { remember(c, it) }
        progress = ""
        DebugLog.add(text)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_LAST, java.text.DateFormat.getDateTimeInstance().format(java.util.Date()) + " — " + text).apply()
        return Outcome(retry, text)
    }

    private fun remember(c: Context, synced: Set<String>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(PREF_SYNCED, HashSet(synced)).apply()
    }

    /** The files this app saved (it owns them, so no storage permission is needed to read them). */
    private fun items(c: Context): List<Item> {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val out = ArrayList<Item>()
        for (collection in listOf(
            MediaStore.Images.Media.getContentUri(volume),
            MediaStore.Video.Media.getContentUri(volume),
            MediaStore.Downloads.getContentUri(volume),
        )) {
            val cols = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            )
            val where = FOLDERS.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
            val args = FOLDERS.map { "$it%" }.toTypedArray()
            c.contentResolver.query(collection, cols, "($where) AND ${MediaStore.MediaColumns.IS_PENDING} = 0", args, null)?.use { q ->
                while (q.moveToNext()) {
                    val size = q.getLong(2)
                    if (size <= 0) continue
                    out.add(Item(ContentUris.withAppendedId(collection, q.getLong(0)), q.getString(1), size, q.getLong(3) * 1000))
                }
            }
        }
        return out.sortedByDescending { it.modifiedMs }
    }
}

class ImmichJob : JobService() {
    @Volatile
    private var stop = false

    override fun onStartJob(params: JobParameters): Boolean {
        stop = false
        Thread {
            val r = try {
                ImmichSync.run(this) { stop }
            } catch (e: Exception) {
                DebugLog.add("Immich: errore inatteso: ${e.message}")
                ImmichSync.Outcome(true, "")
            }
            jobFinished(params, r.retry)
        }.start()
        return true
    }

    /** The network went away or the system needs the job to stop: it is started again when conditions return. */
    override fun onStopJob(params: JobParameters): Boolean {
        stop = true
        return true
    }
}
