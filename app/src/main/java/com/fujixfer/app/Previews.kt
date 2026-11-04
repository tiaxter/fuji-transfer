package com.fujixfer.app

import android.content.Context
import com.fujixfer.core.ObjectInfo
import java.io.File
import java.security.MessageDigest

/**
 * Quick previews for the viewer, on disk in cacheDir: the camera's size-S copy of a photo, or the JPEG a RAF
 * embeds. One per shot (name without extension + capture date, like the thumbnails): the JPG and the RAF of a
 * shot are the same picture and share it. Handles change between connections, the shot does not, so a photo
 * seen once opens at once next time, also before the camera is connected. Only the newest files are kept.
 */
object Previews {
    private const val KEEP = 80
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        if (dir == null) dir = File(context.cacheDir, "preview").apply { mkdirs() }
    }

    fun key(i: ObjectInfo) = Thumbs.key(i)

    /** Photos and RAFs have a quick preview (S copy, embedded JPEG); videos and other files do not. */
    fun applies(i: ObjectInfo): Boolean =
        i.filename.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "heic", "heif", "hif", "raf")

    fun isRaf(i: ObjectInfo) = i.filename.endsWith(".RAF", ignoreCase = true)

    /** The cached preview of {@code i}, or null. */
    fun get(i: ObjectInfo): File? = fileFor(i)?.takeIf { it.length() > 0 }

    /** Stores a preview (written aside, then renamed: never half a file under the final name). */
    fun put(i: ObjectInfo, jpeg: ByteArray): File? {
        val f = fileFor(i) ?: return null
        return try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return null
            }
            trim()
            f
        } catch (e: Exception) {
            null // the cache is best effort
        }
    }

    private fun trim() {
        val all = dir?.listFiles { f -> f.name.endsWith(".jpg") } ?: return
        if (all.size <= KEEP) return
        all.sortedBy { it.lastModified() }.take(all.size - KEEP).forEach { it.delete() }
    }

    private fun fileFor(i: ObjectInfo): File? {
        val d = dir ?: return null
        val h = MessageDigest.getInstance("SHA-1").digest(key(i).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, "$h.jpg")
    }
}
