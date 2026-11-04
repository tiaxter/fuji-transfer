package com.fujixfer.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.fujixfer.core.Exif
import com.fujixfer.core.ObjectInfo

/**
 * Upright bitmaps from camera JPEGs. The EXIF Orientation of the file (read from the JPEG itself, or kept from
 * the background scan) is applied by hand, at decode time, so a picture is never shown sideways first and turned
 * a moment later. A quarter turn is applied only to a landscape bitmap: the camera keeps its pixels landscape and
 * says how to turn them, so a bitmap already portrait was turned by the camera (some copies are) and stays.
 */
object Orient {
    /**
     * Orientations learnt from quick previews (their EXIF), by shot (name without extension|date): known long
     * before the background scan reaches the file. In memory only; the scan writes the database.
     */
    private val learnt = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Orientation of {@code i} (EXIF 1..8), 0 when not known yet. */
    fun known(i: ObjectInfo): Int {
        val o = TransferState.meta[MetaStore.key(i.filename, i.captureDate)]?.orient ?: 0
        return if (o > 0) o else learnt[Thumbs.key(i)] ?: 0
    }

    /**
     * Keeps the orientation a preview's EXIF gives for {@code i}'s shot. True when it was not known before: the
     * caller then drops the thumbnail decoded without it (Thumbs.forget), so it comes back upright.
     */
    fun learn(i: ObjectInfo, jpeg: ByteArray): Boolean {
        if (known(i) > 0) return false
        val o = of(jpeg, 0)
        if (o <= 0) return false
        learnt[Thumbs.key(i)] = o
        return true
    }

    /** The orientation in a JPEG's own EXIF, or {@code fallback} when it has none. */
    fun of(jpeg: ByteArray, fallback: Int): Int {
        val o = try {
            Exif.parse(jpeg).orientation
        } catch (e: Exception) {
            0
        }
        return if (o > 0) o else fallback
    }

    /** {@code b} turned (and mirrored) as {@code orientation} says; the same bitmap when nothing to do. */
    fun apply(b: Bitmap, orientation: Int): Bitmap {
        val quarter = orientation in 5..8
        if (orientation <= 1 || orientation > 8 || (quarter && b.height > b.width)) return b
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(-90f)
        }
        return try {
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (r !== b) b.recycle()
            r
        } catch (e: OutOfMemoryError) {
            b // sideways rather than nothing
        }
    }

    /**
     * Decodes a JPEG at most {@code maxSide} px on its long side (power-of-two steps, then exact), upright.
     * Null when it is not a picture BitmapFactory can read.
     */
    fun decode(jpeg: ByteArray, maxSide: Int, fallback: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val long = maxOf(b.width, b.height)
        if (long > maxSide) {
            val k = maxSide.toFloat() / long
            val s = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
            if (s !== b) b.recycle()
            b = s
        }
        return apply(b, of(jpeg, fallback))
    }

    fun isJpeg(d: ByteArray) = d.size > 3 && (d[0].toInt() and 0xFF) == 0xFF && (d[1].toInt() and 0xFF) == 0xD8
}
