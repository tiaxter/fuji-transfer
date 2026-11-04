package com.fujixfer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.fujixfer.core.ObjectInfo
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thumbnail cache shared by the grid (asks) and TransferService (fetches from the camera).
 * Memory LRU + files in cacheDir, keyed by shot (name without extension + date) so they survive reconnects,
 * new handles and the switch from placeholder to real sizes; the JPG and the RAF of one shot share one.
 * The newest requests are served first: they are the cells the user is looking at.
 */
object Thumbs {
    private val mem = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val checking: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val queued = LinkedHashMap<String, ObjectInfo>() // guarded by lock
    private val inFlight = HashSet<String>() // guarded by lock
    private val lock = Any()
    private val disk = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val notifyPosted = AtomicBoolean(false)
    @Volatile private var dir: File? = null

    /** Set by TransferService: called (any thread) when there is something to fetch from the camera. */
    @Volatile var fetcher: (() -> Unit)? = null

    /** Set by the UI: called on the main thread, coalesced, when new thumbnails are available. */
    @Volatile var onLoaded: (() -> Unit)? = null

    fun init(context: Context) {
        if (dir == null) dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    }

    fun key(i: ObjectInfo) = i.filename.substringBeforeLast('.') + "|" + i.captureDate

    /**
     * Set by the UI: for a RAF, the JPG/HEIF of the same shot if the camera has one. Its thumbnail is the same
     * picture and costs about half as much to fetch.
     */
    @Volatile var sibling: ((ObjectInfo) -> ObjectInfo?)? = null

    /** The file to ask the camera for when {@code i} needs a thumbnail. */
    private fun source(i: ObjectInfo): ObjectInfo =
        if (i.filename.endsWith(".RAF", ignoreCase = true)) sibling?.invoke(i) ?: i else i

    /** The cell no longer shows {@code i} (scrolled away): drop its request if it has not left yet. */
    fun cancel(i: ObjectInfo) {
        synchronized(lock) { queued.remove(key(i)) }
    }

    fun get(i: ObjectInfo): Bitmap? = mem.get(key(i))

    fun isMissing(i: ObjectInfo) = key(i) in missing

    /** Non-blocking: shows it later through onLoaded if it can be found on disk or on the camera. */
    fun request(i: ObjectInfo) {
        val k = key(i)
        if (mem.get(k) != null || k in missing) return
        val alreadyQueued = synchronized(lock) {
            if (k in inFlight) return
            if (queued.containsKey(k)) {
                val q = queued.remove(k)!!
                queued[k] = q // move to the newest end
                true
            } else {
                false
            }
        }
        if (alreadyQueued) {
            fetcher?.invoke() // an earlier fetch may have stopped on a connection error
            return
        }
        if (!checking.add(k)) return
        disk.execute {
            try {
                val f = fileFor(k)
                val bmp = if (f != null && f.exists()) decode(f.readBytes(), i) else null
                if (bmp != null) {
                    mem.put(k, bmp)
                    notifyUi()
                } else {
                    synchronized(lock) {
                        queued.remove(k)
                        queued[k] = source(i)
                        while (queued.size > 400) queued.remove(queued.keys.first())
                    }
                    fetcher?.invoke()
                }
            } catch (e: Exception) {
                // unreadable cache file: the camera will be asked again next time
            } finally {
                checking.remove(k)
            }
        }
    }

    fun hasRequests(): Boolean = synchronized(lock) { queued.isNotEmpty() }

    /** Newest request first. */
    fun next(): ObjectInfo? = synchronized(lock) {
        val k = queued.keys.lastOrNull() ?: return null
        val info = queued.remove(k)
        inFlight.add(k)
        info
    }

    /** Result from the camera; null data means "this file has no thumbnail". */
    fun deliver(i: ObjectInfo, data: ByteArray?) {
        val k = key(i)
        val bmp = data?.let { decode(it, i) }
        if (bmp == null) {
            missing.add(k)
        } else {
            mem.put(k, bmp)
            try {
                data?.let { fileFor(k)?.writeBytes(it) }
            } catch (e: Exception) {
                // cache is best effort
            }
        }
        synchronized(lock) { inFlight.remove(k) }
        notifyUi()
    }

    /**
     * Could not fetch now (connection trouble): back in the queue at the oldest end, so it is fetched again on
     * the next round. Before, it was only dropped, and its cell kept pulsing until scrolled away and back.
     */
    fun failed(i: ObjectInfo) {
        val k = key(i)
        synchronized(lock) {
            inFlight.remove(k)
            if (!queued.containsKey(k)) {
                val rest = LinkedHashMap(queued)
                queued.clear()
                queued[k] = i
                queued.putAll(rest)
            }
        }
    }

    /**
     * Drops the decoded picture of {@code i}'s shot (its orientation became known after it was decoded): the next
     * request decodes it again from the disk cache, upright this time.
     */
    fun forget(i: ObjectInfo) {
        mem.remove(key(i))
    }

    /** New connection: forget pending work and earlier "no thumbnail" answers. */
    fun reset() {
        synchronized(lock) {
            queued.clear()
            inFlight.clear()
        }
        missing.clear()
    }

    private fun notifyUi() {
        if (notifyPosted.compareAndSet(false, true)) {
            main.postDelayed({
                notifyPosted.set(false)
                onLoaded?.invoke()
            }, 120)
        }
    }

    private fun fileFor(k: String): File? {
        val d = dir ?: return null
        val h = MessageDigest.getInstance("SHA-1").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, "$h.jpg")
    }

    /** Upright (orientation from the thumbnail's own EXIF, else from the file's, see Orient), about 480 px. */
    private fun decode(data: ByteArray, i: ObjectInfo): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 480 && bounds.outHeight / (sample * 2) >= 480) sample *= 2
        val b = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return Orient.apply(b, Orient.of(data, Orient.known(i)))
    }
}
