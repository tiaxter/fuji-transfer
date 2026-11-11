package com.fujixfer.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/** What a log line is about; the chips of the "Registro tecnico" screen filter on it. [id] is what the table stores. */
enum class LogKind(val id: String, val label: String) {
    CAMERA("camera", "Camera"),
    WIFI("wifi", "Wi-Fi"),
    IMMICH("immich", "Immich"),
    APP("app", "App");

    companion object {
        fun fromId(id: String?): LogKind = entries.firstOrNull { it.id == id } ?: APP

        /**
         * The kind a message belongs to, from its words; null when they say nothing (the caller then keeps the kind
         * of the line before: sub-lines such as "  energia: ..." follow their parent). Callers that know better pass
         * the kind to [DebugLog.add] and never get here.
         */
        fun guess(message: String): LogKind? {
            val m = message.trim().lowercase(Locale.ROOT)
            return when {
                "immich" in m -> IMMICH
                "wi-fi" in m || "wifi" in m || "rete della" in m || "rete principale" in m || "connessione alla rete" in m ||
                    "network" in m || "joining" in m ||
                    "dhcp" in m || "onlost" in m || "onlosing" in m || "collegamento locale" in m || m.startsWith("caduta") -> WIFI
                m.startsWith("app in ") || m.startsWith("avvio") || m.startsWith("schermo") || m.startsWith("telefono") ||
                    m.startsWith("video") || m.startsWith("visore") || m.startsWith("decoder") ||
                    "ottimizzazione batteria" in m -> APP
                m.startsWith("ble") || "ptp" in m || "elenco" in m || "originale" in m || "miniatur" in m || "anteprima" in m ||
                    "posizioni" in m || "galleria" in m || "sessione" in m || "metadati" in m || "giorni" in m ||
                    "abbinamento" in m || "candidato" in m || "download" in m || "camera" in m ||
                    // status lines in English, when the app runs in English
                    "list of photos" in m || "session" in m || "pairing" in m || "preview" in m || "thumbnail" in m -> CAMERA
                else -> null
            }
        }

        /** Lines that report a failure or a drop: they get the red bar and the "Errori" chip. */
        fun looksLikeError(message: String): Boolean {
            val m = message.trim().lowercase(Locale.ROOT)
            return m.startsWith("errore") || m.startsWith("eccezione") || m.startsWith("caduta") ||
                "errore" in m || "fallit" in m || "non riuscit" in m || "timeout" in m || "socket chiuso" in m ||
                "ha spento il suo wi-fi" in m || "error" in m || "failed" in m || "timed out" in m
        }
    }
}

/** One line of the log. [sub] = it was written indented, as a detail of the line before. */
data class LogRow(val id: Long, val ts: Long, val kind: LogKind, val error: Boolean, val sub: Boolean, val message: String)

/**
 * The readable log lines (the ones [DebugLog.add] gets), kept in Android's own SQLite so they survive a restart of
 * the app and can be filtered by kind and by errors. The full text files stay the source for "Condividi".
 * Old rows go away: older than [KEEP_DAYS] days, or beyond the last [KEEP_ROWS].
 */
class LogStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "log.db", null, 1) {

    init {
        setWriteAheadLoggingEnabled(true) // the screen reads while the writer thread inserts
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL, " +
                "error INTEGER NOT NULL DEFAULT 0, sub INTEGER NOT NULL DEFAULT 0, message TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX log_kind ON log(kind, id)")
        db.execSQL("CREATE INDEX log_error ON log(error, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(rows: List<LogRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT INTO log (ts, kind, error, sub, message) VALUES (?, ?, ?, ?, ?)")
            for (r in rows) {
                st.clearBindings()
                st.bindLong(1, r.ts)
                st.bindString(2, r.kind.id)
                st.bindLong(3, if (r.error) 1 else 0)
                st.bindLong(4, if (r.sub) 1 else 0)
                st.bindString(5, r.message)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The newest [limit] lines that match, oldest first. [kind] null = every kind; [errorsOnly] = only the red ones. */
    fun query(kind: LogKind?, errorsOnly: Boolean, limit: Int): List<LogRow> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (kind != null) {
            where.add("kind = ?")
            args.add(kind.id)
        }
        if (errorsOnly) where.add("error = 1")
        val cond = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        args.add(limit.toString())
        val out = ArrayList<LogRow>()
        readableDatabase.rawQuery(
            "SELECT id, ts, kind, error, sub, message FROM (SELECT * FROM log$cond ORDER BY id DESC LIMIT ?) ORDER BY id",
            args.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(LogRow(c.getLong(0), c.getLong(1), LogKind.fromId(c.getString(2)), c.getInt(3) == 1, c.getInt(4) == 1, c.getString(5)))
            }
        }
        return out
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM log")
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.execSQL("DELETE FROM log WHERE ts < ?", arrayOf<Any>(now - KEEP_DAYS * 86_400_000L))
        db.execSQL("DELETE FROM log WHERE id <= (SELECT MAX(id) FROM log) - ?", arrayOf<Any>(KEEP_ROWS))
    }

    companion object {
        const val KEEP_DAYS = 14
        const val KEEP_ROWS = 20_000
    }
}
