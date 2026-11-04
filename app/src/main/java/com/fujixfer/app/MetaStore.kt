package com.fujixfer.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * What the EXIF of a file gave: position (NaN when none), film simulation and orientation (EXIF 1..8; 0 = not
 * read yet, for rows written before orientation was kept: the background scan reads those files again once).
 */
data class Meta(val lat: Double, val lon: Double, val film: String?, val orient: Int = 0) {
    val hasPosition: Boolean get() = !lat.isNaN() && !lon.isNaN() && !(lat == 0.0 && lon == 0.0)
}

/**
 * Metadata read once per file and kept across connections (Android's own SQLite, no extra library). The key
 * is name + capture date, like the "already downloaded" marks: camera handles change when files are deleted.
 * A row exists also for files without a position, so they are not read again.
 */
class MetaStore(context: Context) : SQLiteOpenHelper(context, "meta.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, lat REAL, lon REAL, film TEXT, orient INTEGER DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 1 -> 2: orientation; old rows get 0 (not read) and are read again by the background scan
        if (oldVersion < 2) db.execSQL("ALTER TABLE meta ADD COLUMN orient INTEGER DEFAULT 0")
    }

    fun loadAll(): HashMap<String, Meta> {
        val out = HashMap<String, Meta>()
        readableDatabase.rawQuery("SELECT k, lat, lon, film, orient FROM meta", null).use { c ->
            while (c.moveToNext()) {
                val lat = if (c.isNull(1)) Double.NaN else c.getDouble(1)
                val lon = if (c.isNull(2)) Double.NaN else c.getDouble(2)
                out[c.getString(0)] = Meta(lat, lon, if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) 0 else c.getInt(4))
            }
        }
        return out
    }

    fun put(entries: Map<String, Meta>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((k, m) in entries) {
                val v = ContentValues()
                v.put("k", k)
                if (m.lat.isNaN()) v.putNull("lat") else v.put("lat", m.lat)
                if (m.lon.isNaN()) v.putNull("lon") else v.put("lon", m.lon)
                if (m.film == null) v.putNull("film") else v.put("film", m.film)
                v.put("orient", m.orient)
                db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        fun key(filename: String, captureDate: String) = "$filename|$captureDate"
    }
}
