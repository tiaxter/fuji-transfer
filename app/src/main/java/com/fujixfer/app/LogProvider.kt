package com.fujixfer.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands the exported log file (cache/share) to the app the user shares it with (mail, chat, Drive...), read only,
 * for that one share: no storage permission, nothing left in the phone's Downloads.
 */
class LogProvider : ContentProvider() {
    companion object {
        fun uriFor(context: Context, f: File): Uri = Uri.parse("content://${context.packageName}.logs/${Uri.encode(f.name)}")
    }

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        val f = File(File(context!!.cacheDir, "share"), name)
        return f.takeIf { it.isFile }
    }

    override fun getType(uri: Uri) = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        val f = fileFor(uri) ?: return c
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
