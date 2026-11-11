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
 * Sends to the user's Immich server the camera files the user chose (design "Immich: caricare solo quando lo
 * scegli"). Nothing goes up on its own unless the user picked "Sempre, dopo ogni download".
 *
 * A file joins the queue in three ways: "Immich" in the gallery or on a photo ([MODE_ASK] and always), the answer
 * to the question at the end of a download ([MODE_OFFER]), or the end of every download ([MODE_AUTO], only the
 * kinds of file chosen in "Cosa caricare"). Each queued file is hashed (SHA-1) and the server is asked whether it
 * has it; only a file it lacks is uploaded ("C'era già" otherwise). The work runs as a job with a network
 * condition: while the phone is on the camera's Wi-Fi it waits, and it runs when the phone is back on a normal
 * network (by default an unmetered one: Wi-Fi, not mobile data).
 */
object ImmichSync {
    private const val PREFS = "fuji"
    /** Before the "Quando caricare" choice: "Invia dopo il download" (now [MODE_AUTO]). Read once to migrate. */
    const val PREF_ENABLED = "immich_enabled"
    const val PREF_URL = "immich_url"
    const val PREF_KEY = "immich_key"
    const val PREF_WIFI_ONLY = "immich_wifi_only"
    private const val PREF_MODE = "immich_mode"
    private const val PREF_KINDS = "immich_kinds"
    private const val PREF_LOGIN = "immich_use_login"
    private const val PREF_EMAIL = "immich_email"
    private const val PREF_TOKEN = "immich_token"
    private const val PREF_SERVER = "immich_server_info"
    /** Camera files ("name|date", as the "done" marks) waiting to go to Immich. */
    private const val PREF_QUEUE = "immich_queue"
    /** Camera files known to be on the server (sent, or found there already). */
    private const val PREF_ON_SERVER = "immich_on_server"
    /** "name|date\tcontent uri": which media store item holds a camera file (written when it is saved). */
    private const val PREF_MEDIA = "immich_media"
    private const val PREF_SYNCED = "immich_synced"
    private const val PREF_LAST = "immich_last"
    private const val PREF_PAUSED = "immich_paused"
    /** "name|date\tmessage": files the server refused; they wait for "Riprova". */
    private const val PREF_FAILED = "immich_failed"
    /** Finished files, newest first, one per line: "name|date\tsize\t1 if the server had it already". */
    private const val PREF_HISTORY = "immich_history"
    /** "Carica ora con i dati mobili": the next run may use any network, once. */
    private const val PREF_ANY_ONCE = "immich_any_once"
    /** Why nothing can go up (address or access refused); empty = fine. */
    private const val PREF_BLOCKED = "immich_blocked"
    /** The last run could not reach the server (timeout, no route): why, "" = it answered. */
    private const val PREF_UNREACHABLE = "immich_unreachable"
    private const val HISTORY_MAX = 200
    private const val JOB_ID = 4711
    private const val DEVICE_ID = "fuji-transfer"

    /** "Solo quando lo chiedo": the gallery's "Immich" only. */
    const val MODE_ASK = "ask"
    /** "Chiedi a fine download": one question after every batch. */
    const val MODE_OFFER = "offer"
    /** "Sempre, dopo ogni download": the files just downloaded join the queue. */
    const val MODE_AUTO = "auto"

    /** "Cosa caricare": photos (JPG and HEIF), RAW, video. */
    const val KIND_PHOTO = "jpg"
    const val KIND_RAW = "raf"
    const val KIND_VIDEO = "mov"

    fun kindOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "raf" -> KIND_RAW
        "mov", "mp4" -> KIND_VIDEO
        else -> KIND_PHOTO
    }

    /** The last outcome, for the log and the settings screen. */
    @Volatile
    var progress: String = ""

    /** Goes up when the queue, the marks or a run's progress change: screens re-read them. */
    @Volatile
    var version: Int = 0

    // what the run going on is doing (one run at a time: the job)
    @Volatile var running: Boolean = false
        private set
    @Volatile var currentKey: String? = null
        private set
    @Volatile var currentDone: Long = 0
        private set
    @Volatile var currentTotal: Long = 0
        private set
    /** Files and bytes of the run going on: "3 di 7", "74 di 320 MB", speed. */
    @Volatile var runFiles: Int = 0
        private set
    @Volatile var runIndex: Int = 0
        private set
    @Volatile var runBytesTotal: Long = 0
        private set
    @Volatile var runBytesDone: Long = 0
        private set
    @Volatile var runStartMs: Long = 0
        private set

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun set(c: Context, pref: String): MutableSet<String> =
        prefs(c).getStringSet(pref, emptySet())!!.toHashSet()

    private fun putSet(c: Context, pref: String, v: Set<String>) {
        prefs(c).edit().putStringSet(pref, HashSet(v)).apply()
        version++
    }

    fun queued(c: Context): Set<String> = set(c, PREF_QUEUE)

    fun onServer(c: Context): Set<String> = set(c, PREF_ON_SERVER)

    /** Queues these camera files; they go up as soon as they are on the phone and the network allows it. */
    @Synchronized
    fun enqueue(c: Context, keys: Collection<String>) {
        val q = set(c, PREF_QUEUE)
        val there = onServer(c)
        val todo = keys.filter { it !in there }
        q.addAll(todo)
        putSet(c, PREF_QUEUE, q)
        val failed = set(c, PREF_FAILED)
        if (failed.removeAll { it.substringBefore('\t') in todo }) putSet(c, PREF_FAILED, failed)
        DebugLog.add("Immich: ${todo.size} file in coda da inviare")
    }

    /** Out of the queue (files already sent stay sent). */
    @Synchronized
    fun dequeue(c: Context, keys: Collection<String>) {
        putSet(c, PREF_QUEUE, set(c, PREF_QUEUE) - keys.toSet())
    }

    /** "Annulla tutti": the queue and the refused files are dropped; the file going up now stops after it. */
    @Synchronized
    fun dequeueAll(c: Context) {
        putSet(c, PREF_QUEUE, emptySet())
        putSet(c, PREF_FAILED, emptySet())
        prefs(c).edit().putBoolean(PREF_ANY_ONCE, false).apply()
        schedule(c)
        DebugLog.add("Immich: coda svuotata")
    }

    fun isPaused(c: Context) = prefs(c).getBoolean(PREF_PAUSED, false)

    /** "Pausa" / "Riprendi": a run going on stops after the file it is sending. */
    fun setPaused(c: Context, paused: Boolean) {
        prefs(c).edit().putBoolean(PREF_PAUSED, paused).apply()
        version++
        DebugLog.add(if (paused) "Immich: caricamenti in pausa" else "Immich: caricamenti ripresi")
        schedule(c)
    }

    /** Files the server refused, key → message. */
    fun failed(c: Context): Map<String, String> =
        set(c, PREF_FAILED).associate { it.substringBefore('\t') to it.substringAfter('\t', "") }

    /** "Riprova" on a refused file. */
    @Synchronized
    fun retry(c: Context, key: String) {
        putSet(c, PREF_FAILED, set(c, PREF_FAILED).filterTo(HashSet()) { it.substringBefore('\t') != key })
        enqueue(c, listOf(key))
        schedule(c)
    }

    @Synchronized
    private fun markFailed(c: Context, key: String, message: String) {
        val f = set(c, PREF_FAILED).filterTo(HashSet()) { it.substringBefore('\t') != key }
        f.add(key + "\t" + message)
        putSet(c, PREF_FAILED, f)
        val q = set(c, PREF_QUEUE)
        if (q.remove(key)) putSet(c, PREF_QUEUE, q)
    }

    /** "Carica ora con i dati mobili": this time any network will do. */
    fun uploadNowAnyNetwork(c: Context) {
        prefs(c).edit().putBoolean(PREF_ANY_ONCE, true).apply()
        DebugLog.add("Immich: carico subito, anche con i dati mobili (solo questa volta)")
        schedule(c)
    }

    fun anyNetworkOnce(c: Context) = prefs(c).getBoolean(PREF_ANY_ONCE, false)

    /** Why nothing can go up (access or address refused), empty when fine. */
    fun blocked(c: Context): String = prefs(c).getString(PREF_BLOCKED, "") ?: ""

    fun unreachable(c: Context): String = prefs(c).getString(PREF_UNREACHABLE, "") ?: ""

    private fun setUnreachable(c: Context, why: String) {
        prefs(c).edit().putString(PREF_UNREACHABLE, why).apply()
    }

    private fun setBlocked(c: Context, why: String) {
        prefs(c).edit().putString(PREF_BLOCKED, why).apply()
        version++
    }

    /** A finished file in the "Fatti" list. */
    class Finished(val key: String, val size: Long, val wasThere: Boolean) {
        val name get() = key.substringBefore('|')
    }

    fun history(c: Context): List<Finished> =
        (prefs(c).getString(PREF_HISTORY, "") ?: "").lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3) null else Finished(p[0], p[1].toLongOrNull() ?: -1L, p[2] == "1")
        }

    @Synchronized
    private fun addHistory(c: Context, key: String, size: Long, wasThere: Boolean) {
        val old = history(c).filter { it.key != key }.take(HISTORY_MAX - 1)
        val lines = listOf("$key\t$size\t${if (wasThere) 1 else 0}") +
            old.map { "${it.key}\t${it.size}\t${if (it.wasThere) 1 else 0}" }
        prefs(c).edit().putString(PREF_HISTORY, lines.joinToString("\n")).apply()
    }

    /** Called when a camera file has been saved in the media store. */
    @Synchronized
    fun rememberMedia(c: Context, cameraKey: String, uri: Uri) {
        val m = set(c, PREF_MEDIA).filterTo(HashSet()) { it.substringBefore('\t') != cameraKey }
        m.add(cameraKey + "\t" + uri)
        prefs(c).edit().putStringSet(PREF_MEDIA, m).apply()
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
        val mode: String, val url: String, val key: String, val wifiOnly: Boolean,
        val useLogin: Boolean = false, val email: String = "", val token: String = "",
        val kinds: Set<String> = setOf(KIND_PHOTO, KIND_VIDEO),
    ) {
        val credential get() = if (useLogin) token else key
        /** Address and access are set: files can go up. */
        val configured get() = url.isNotBlank() && credential.isNotBlank()

        fun copy(
            mode: String = this.mode, url: String = this.url, key: String = this.key, wifiOnly: Boolean = this.wifiOnly,
            useLogin: Boolean = this.useLogin, email: String = this.email, token: String = this.token,
            kinds: Set<String> = this.kinds,
        ) = Settings(mode, url, key, wifiOnly, useLogin, email, token, kinds)
    }

    /** What the status card shows after a check: "Immich 1.118 · 12.480 foto" (empty = never checked). */
    fun serverInfo(c: Context): String = prefs(c).getString(PREF_SERVER, "") ?: ""

    fun saveServerInfo(c: Context, info: String) {
        prefs(c).edit().putString(PREF_SERVER, info).apply()
    }

    /**
     * Checks address and access; with [password] (email mode) logs in first. Returns the settings with the new
     * token and the status line. Network: call off the main thread.
     */
    fun verify(c: Context, s: Settings, password: String): Pair<Settings, String> {
        var now = s
        if (s.useLogin && password.isNotEmpty()) now = s.copy(token = ImmichClient.login(s.url, s.email, password))
        if (now.credential.isBlank()) throw IOException(c.getString(if (now.useLogin) R.string.im_need_login else R.string.im_need_key))
        val api = client(now)
        api.test()
        val v = api.version().split('.').take(2).joinToString(".")
        val n = api.assetCount()
        val count = if (n < 0) "" else " · " + c.resources.getQuantityString(R.plurals.im_n_photos, n.toInt().coerceAtLeast(0), java.text.NumberFormat.getIntegerInstance().format(n))
        return now to "Immich $v$count"
    }

    private class Item(val uri: Uri, val name: String, val size: Long, val modifiedMs: Long) {
        val key get() = "$name|$size|${modifiedMs / 1000}"
        var cameraKey: String? = null
    }

    class Outcome(val retry: Boolean, val summary: String)

    fun settings(c: Context): Settings {
        val p = prefs(c)
        val mode = p.getString(PREF_MODE, null) ?: if (p.getBoolean(PREF_ENABLED, false)) MODE_AUTO else MODE_ASK
        return Settings(
            mode, p.getString(PREF_URL, "") ?: "", p.getString(PREF_KEY, "") ?: "",
            p.getBoolean(PREF_WIFI_ONLY, true),
            p.getBoolean(PREF_LOGIN, (p.getString(PREF_KEY, "") ?: "").isEmpty()) /* an API key set before: key mode */,
            p.getString(PREF_EMAIL, "") ?: "", p.getString(PREF_TOKEN, "") ?: "",
            p.getStringSet(PREF_KINDS, null)?.toSet() ?: setOf(KIND_PHOTO, KIND_VIDEO),
        )
    }

    fun save(c: Context, s: Settings) {
        prefs(c).edit()
            .putString(PREF_MODE, s.mode).putBoolean(PREF_ENABLED, s.mode == MODE_AUTO)
            .putString(PREF_URL, s.url.trim()).putString(PREF_KEY, s.key.trim())
            .putBoolean(PREF_WIFI_ONLY, s.wifiOnly).putStringSet(PREF_KINDS, HashSet(s.kinds))
            .putBoolean(PREF_LOGIN, s.useLogin).putString(PREF_EMAIL, s.email.trim()).putString(PREF_TOKEN, s.token).apply()
        version++
    }

    /** "Esci": access forgotten (token, API key, last check); the address and the email stay to sign in again. */
    fun signOut(c: Context) {
        prefs(c).edit().putString(PREF_TOKEN, "").putString(PREF_KEY, "").putString(PREF_SERVER, "")
            .putString(PREF_BLOCKED, "").apply()
        version++
        schedule(c)
        DebugLog.add("Immich: uscito dall'account")
    }

    /**
     * End of a download batch: in [MODE_AUTO] the files just saved of the chosen kinds join the queue.
     * Returns how many were queued (0 in the other modes).
     */
    fun afterDownload(c: Context, keys: List<String>): Int {
        val s = settings(c)
        if (!s.configured || s.mode != MODE_AUTO) return 0
        val there = onServer(c)
        val todo = keys.filter { kindOf(it.substringBefore('|')) in s.kinds && it !in there }
        if (todo.isNotEmpty()) enqueue(c, todo)
        schedule(c)
        return todo.size
    }

    fun lastSummary(c: Context): String = prefs(c).getString(PREF_LAST, "") ?: ""

    fun client(s: Settings) = ImmichClient(s.url, s.credential, DEVICE_ID, s.useLogin)

    /** Asks the system to run the sync when the network allows it. Replaces a job already waiting. */
    fun schedule(c: Context) {
        val s = settings(c)
        val js = c.getSystemService(JobScheduler::class.java)
        if (!s.configured || isPaused(c) || queued(c).isEmpty()) {
            js.cancel(JOB_ID)
            return
        }
        val any = !s.wifiOnly || anyNetworkOnce(c)
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, ImmichJob::class.java))
            .setRequiredNetworkType(if (any) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        js.schedule(job)
        DebugLog.add("Immich: sincronizzazione in coda (${if (any) "qualsiasi rete" else "solo rete senza limiti"})")
    }

    /** One pass over the queued files that are on the phone, newest first. Runs on a background thread. */
    fun run(c: Context, stopped: () -> Boolean): Outcome {
        val s = settings(c)
        if (!s.configured) return Outcome(false, "Immich non è configurato")
        if (isPaused(c)) return Outcome(false, "Immich: in pausa")
        val synced = prefs(c).getStringSet(PREF_SYNCED, emptySet())!!.toHashSet()
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
        // queued and already sent in an earlier run: only the mark was missing
        for (x in all) if (asked(x) && x.key in synced) markOnServer(c, x.cameraKey!!)
        val todo = all.filter { asked(it) && it.key !in synced }
        if (todo.isEmpty()) return finish(c, false, "Immich: niente da inviare")
        val api = client(s)
        api.progress = ImmichClient.Progress { done, total ->
            runBytesDone += done - currentDone
            currentDone = done
            currentTotal = total
            version++
        }
        running = true
        runFiles = todo.size
        runIndex = 0
        runBytesTotal = todo.sumOf { it.size }
        runBytesDone = 0
        runStartMs = System.currentTimeMillis()
        var uploaded = 0
        var already = 0
        var refused = 0
        for ((i, item) in todo.withIndex()) {
            if (stopped()) return finish(c, true, "Immich: interrotto (inviati $uploaded, già presenti $already)", synced)
            if (isPaused(c)) return finish(c, false, "Immich: in pausa (inviati $uploaded, già presenti $already)", synced)
            val camKey = item.cameraKey!!
            if (camKey !in queued(c)) { // taken out of the queue meanwhile ("Annulla tutti")
                runBytesDone += item.size
                continue
            }
            runIndex = i + 1
            currentKey = camKey
            currentDone = 0
            currentTotal = item.size
            version++
            progress = "Immich ${i + 1}/${todo.size}: ${item.name}"
            try {
                val src = object : ImmichClient.Source {
                    override fun open() = c.contentResolver.openInputStream(item.uri) ?: throw IOException(c.getString(R.string.ex_cannot_open))
                    override fun length() = item.size
                }
                val outcome = api.sync(item.name, src, item.modifiedMs, item.modifiedMs)
                when (outcome) {
                    ImmichClient.Outcome.UPLOADED -> uploaded++
                    ImmichClient.Outcome.DUPLICATE -> already++
                }
                runBytesDone += item.size - currentDone // a duplicate sends nothing: count it as done
                synced.add(item.key)
                remember(c, synced)
                markOnServer(c, camKey)
                addHistory(c, camKey, item.size, outcome == ImmichClient.Outcome.DUPLICATE)
                if (blocked(c).isNotEmpty()) setBlocked(c, "")
                if (unreachable(c).isNotEmpty()) setUnreachable(c, "")
            } catch (e: ImmichClient.ImmichException) {
                if (e.code == 401 || e.code == 403 || e.code == 404) {
                    // address or access: every file would get the same answer
                    setBlocked(c, e.message ?: c.getString(R.string.im_access_refused))
                    return finish(c, false, "Immich: ${e.message}", synced)
                }
                if (e.permanent) {
                    refused++
                    markFailed(c, camKey, e.message ?: c.getString(R.string.im_refused_by_server))
                    DebugLog.add("Immich: ${item.name} rifiutato: ${e.message}")
                    continue
                }
                return finish(c, true, "Immich: ${e.message}, riprovo più tardi", synced)
            } catch (e: java.net.SocketTimeoutException) {
                setUnreachable(c, e.message ?: "nessuna risposta")
                return finish(c, true, "Immich: il server non ha risposto (${e.message}), riprovo più tardi", synced)
            } catch (e: IOException) {
                setUnreachable(c, e.message ?: "rete")
                return finish(c, true, "Immich non raggiungibile (${e.message}): riprovo più tardi", synced)
            } catch (e: SecurityException) {
                refused++
                markFailed(c, camKey, c.getString(R.string.im_unreadable))
                DebugLog.add("Immich: ${item.name} non leggibile (${e.message})")
            }
        }
        prefs(c).edit().putBoolean(PREF_ANY_ONCE, false).apply()
        return finish(c, false, "Immich: inviati $uploaded, già presenti $already" + (if (refused > 0) ", rifiutati $refused" else ""), synced)
    }

    private fun finish(c: Context, retry: Boolean, text: String, synced: Set<String>? = null): Outcome {
        synced?.let { remember(c, it) }
        running = false
        currentKey = null
        currentDone = 0
        currentTotal = 0
        progress = ""
        version++
        DebugLog.add(text)
        prefs(c).edit()
            .putString(PREF_LAST, java.text.DateFormat.getDateTimeInstance().format(java.util.Date()) + " — " + text).apply()
        return Outcome(retry, text)
    }

    private fun remember(c: Context, synced: Set<String>) {
        prefs(c).edit().putStringSet(PREF_SYNCED, HashSet(synced)).apply()
    }

    // ------------------------------------------------------------------ the "Caricamenti" screen

    enum class RowState { Running, Failed, Queued, NotOnPhone, Done, Skipped }

    /** [size] -1 = not known here (the file is not on the phone yet). [note]: the server's answer for [RowState.Failed]. */
    class Row(val key: String, val size: Long, val state: RowState, val note: String = "") {
        val name get() = key.substringBefore('|')
    }

    /**
     * Every file the screen lists: the one going up, the refused ones, the queue (on the phone or not yet), then
     * the finished ones. Reads the media store: call off the main thread.
     */
    fun rows(c: Context): List<Row> {
        val bySize = HashMap<String, Long>()
        val links = set(c, PREF_MEDIA).associate { it.substringAfter('\t') to it.substringBefore('\t') }
        for (x in items(c)) links[x.uri.toString()]?.let { bySize[it] = x.size }
        val cur = currentKey.takeIf { running }
        val out = ArrayList<Row>()
        cur?.let { out.add(Row(it, bySize[it] ?: currentTotal, RowState.Running)) }
        failed(c).forEach { (k, why) -> if (k != cur) out.add(Row(k, bySize[k] ?: -1L, RowState.Failed, why)) }
        val q = queued(c).filter { it != cur }.sortedDescending()
        q.filter { it in bySize }.forEach { out.add(Row(it, bySize[it]!!, RowState.Queued)) }
        q.filter { it !in bySize }.forEach { out.add(Row(it, -1L, RowState.NotOnPhone)) }
        history(c).forEach { f -> if (f.key != cur) out.add(Row(f.key, f.size, if (f.wasThere) RowState.Skipped else RowState.Done)) }
        return out
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
            val folders = SaveFolder.allFolders(c)
            val where = folders.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
            val args = folders.map { "$it/%" }.toTypedArray()
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
