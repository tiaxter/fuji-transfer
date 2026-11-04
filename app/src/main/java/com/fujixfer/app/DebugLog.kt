package com.fujixfer.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Diagnostic log. Two levels:
 * - {@link #add}: the readable lines, kept in memory (last 600, for the short views) and in the SQLite table of
 *   [LogStore] (with their kind and an error mark, for the "Registro tecnico" screen: filters, survives a restart),
 *   and in the file;
 * - {@link #detail}: everything else (each command to the camera with its answer and time, network changes,
 *   viewer actions), in the file only.
 * The file (filesDir/logs, one per app start, the last 6 kept) survives a crash or a restart of the app, and
 * {@link #export} turns the recent ones into one text file to share. Every file line carries, after the clock,
 * the seconds since the phone joined the camera's Wi-Fi ("w+18.07"), to line events up with the 18.1 s drop.
 */
object DebugLog {
    private const val MAX = 600
    private const val KEEP_FILES = 6
    private const val MAX_FILE = 8L * 1024 * 1024
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val queue = LinkedBlockingQueue<String>()
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fujixfer-log").apply { isDaemon = true } }
    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    @Volatile private var store: LogStore? = null
    private val pending = LinkedBlockingQueue<LogRow>()
    private var insertedSincePrune = 0
    /** Kind of the last line: a line whose words say nothing (a detail of the one before) takes it. */
    @Volatile private var lastKind = LogKind.APP

    /** Grows each time new rows reach the table: the log screen asks again when it changes. */
    @Volatile var revision = 0
        private set

    /** When the phone joined the camera's Wi-Fi (System.currentTimeMillis), 0 = not joined. Set by the service. */
    @Volatile var wifiJoinedAt = 0L

    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            val d = File(context.filesDir, "logs").apply { mkdirs() }
            d.listFiles { f -> f.name.startsWith("registro-") }?.sortedBy { it.name }?.dropLast(KEEP_FILES - 1)?.forEach { it.delete() }
            file = newFile(d)
            dir = d
            store = LogStore(context)
            writer.execute {
                try {
                    store?.prune()
                } catch (e: Exception) {
                    Log.w("FujiTransfer", "pulizia del registro non riuscita: ${e.message}")
                }
            }
            writer.scheduleWithFixedDelay({ flush() }, 1, 1, TimeUnit.SECONDS)
        }
        val v = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        detail("avvio dell'app: Fuji Transfer $v, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    private fun newFile(d: File) =
        File(d, "registro-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt")

    private fun stamp(now: Long = System.currentTimeMillis()): String {
        val clock = synchronized(fmt) { fmt.format(Date(now)) }
        val w = wifiJoinedAt
        return if (w > 0 && now >= w) "$clock w+" + String.format(Locale.ROOT, "%.2f", (now - w) / 1000.0) else clock
    }

    /**
     * A readable line: screen, table and file. The kind and the error mark come from the words of the message
     * unless the caller says them ([LogKind.guess], [LogKind.looksLikeError]).
     */
    fun add(message: String, kind: LogKind? = null, error: Boolean = false) {
        val now = System.currentTimeMillis()
        val s = stamp(now)
        val line = s.substringBefore(" w+") + " " + message // the screen keeps the plain clock
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        val k = kind ?: LogKind.guess(message) ?: lastKind
        lastKind = k
        pending.add(LogRow(0, now, k, error || LogKind.looksLikeError(message), message.startsWith(" "), message.trim()))
        queue.add("$s $message")
        Log.d("FujiTransfer", message)
    }

    /** A detailed line: file only (the screen stays readable). */
    fun detail(message: String) {
        queue.add(stamp() + " · " + message)
    }

    /** Writes what is waiting to the table and the file (once a second, and before an export). */
    @Synchronized
    fun flush() {
        flushRows()
        val f = file ?: return
        if (queue.isEmpty()) return
        val batch = ArrayList<String>(queue.size)
        queue.drainTo(batch)
        try {
            f.appendText(batch.joinToString("\n", postfix = "\n"))
            if (f.length() > MAX_FILE) dir?.let { file = newFile(it) }
        } catch (e: Exception) {
            Log.w("FujiTransfer", "registro su file non scritto: ${e.message}")
        }
    }

    private fun flushRows() {
        val st = store ?: return
        if (pending.isEmpty()) return
        val batch = ArrayList<LogRow>(pending.size)
        pending.drainTo(batch)
        try {
            st.insert(batch)
            revision++
            insertedSincePrune += batch.size
            if (insertedSincePrune > 2000) {
                insertedSincePrune = 0
                st.prune()
            }
        } catch (e: Exception) {
            Log.w("FujiTransfer", "registro nel database non scritto: ${e.message}")
        }
    }

    /** The newest [limit] lines of the table that match, oldest first; call it off the main thread. */
    fun rows(kind: LogKind?, errorsOnly: Boolean, limit: Int): List<LogRow> {
        flush()
        return try {
            store?.query(kind, errorsOnly, limit).orEmpty()
        } catch (e: Exception) {
            Log.w("FujiTransfer", "registro nel database non letto: ${e.message}")
            emptyList()
        }
    }

    /**
     * The recent log files (this start of the app and the ones before, oldest first, up to 20 MB) in one text
     * file under cache/share, with a header; null if there is nothing to export.
     */
    fun export(context: Context, header: String): File? {
        flush()
        val files = dir?.listFiles { f -> f.name.startsWith("registro-") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return null
        val chosen = ArrayList<File>()
        var total = 0L
        for (f in files.reversed()) {
            if (total + f.length() > 20L * 1024 * 1024 && chosen.isNotEmpty()) break
            chosen.add(0, f)
            total += f.length()
        }
        val out = File(File(context.cacheDir, "share").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // only the last export is kept
        }, "fuji-transfer-registro-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt")
        return try {
            out.bufferedWriter().use { w ->
                w.write(header)
                w.write("\n")
                for (f in chosen) {
                    w.write("\n===== ${f.name} =====\n")
                    f.bufferedReader().use { r -> r.copyTo(w) }
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun isEmpty(): Boolean = synchronized(lines) { lines.isEmpty() }

    /** Empties the screen's lines and the table; the file keeps everything (a marker shows where). */
    fun clear() {
        synchronized(lines) { lines.clear() }
        pending.clear()
        try {
            store?.clear()
        } catch (e: Exception) {
            Log.w("FujiTransfer", "registro nel database non svuotato: ${e.message}")
        }
        revision++
        detail("registro a schermo svuotato dall'utente")
    }

    fun hex(b: ByteArray?, max: Int = 32): String {
        if (b == null) return "null"
        val s = b.take(max).joinToString(" ") { "%02x".format(it) }
        return if (b.size > max) "$s … (${b.size} B)" else "$s (${b.size} B)"
    }

    /** First 8 hex digits of a UUID, enough to recognise Fuji characteristics in the log. */
    fun short(uuid: String): String = uuid.take(8).uppercase(Locale.ROOT)
}
