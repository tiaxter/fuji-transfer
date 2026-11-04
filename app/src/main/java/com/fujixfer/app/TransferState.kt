package com.fujixfer.app

import android.os.Handler
import android.os.Looper
import com.fujixfer.core.ObjectInfo
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide state shared between TransferService (writer) and MainActivity (observer). */
object TransferState {
    interface Listener {
        fun onChanged()
    }

    @Volatile var status: String = "Non connesso"
    @Volatile var connected: Boolean = false
    @Volatile var busy: Boolean = false
    @Volatile var objects: List<ObjectInfo> = emptyList()
    @Volatile var progressName: String = ""
    @Volatile var progressDone: Long = 0
    @Volatile var progressTotal: Long = 0
    @Volatile var batchIndex: Int = 0
    @Volatile var batchCount: Int = 0
    @Volatile var statusIsError: Boolean = false
    @Volatile var batchBytesTotal: Long = 0
    @Volatile var batchBytesDone: Long = 0
    @Volatile var connectionLabel: String = ""
    /** Name shown in the gallery's top bar (Bluetooth name of the body, or a generic label). */
    @Volatile var cameraName: String = ""
    /** Connecting screen: 0 looking for the camera, 1 pairing recognised, 2 camera Wi-Fi, 3 session and list. */
    @Volatile var connectStep: Int = 0
    /** Files of the running download batch, in order (batchIndex is 1-based into it). */
    @Volatile var batch: List<ObjectInfo> = emptyList()
    /** Shown on the download screen after the connection dropped and the transfer resumed. */
    @Volatile var retryNote: String = ""
    /** False while the listing carries placeholder sizes (real ones are asked at download time). */
    @Volatile var sizesKnown: Boolean = true
    /** Bumped when a file is marked as downloaded, so the gallery re-reads the marks only then. */
    @Volatile var doneVersion: Int = 0
    /** Listing in progress (the gallery is already usable): how far it is. */
    @Volatile var listing: Boolean = false
    @Volatile var listDone: Int = 0
    @Volatile var listTotal: Int = 0

    /** Files per day from the camera (0x9053, like XApp), newest day first; empty if unavailable or not trusted. */
    @Volatile var dayGroups: List<DayGroup> = emptyList()
    /**
     * Files of each day not read yet, from the 0xD621 handles split by the 0x9053 counts; null when the camera
     * did not give the handles (then the gallery falls back to count minus files read for that day).
     */
    @Volatile var unreadByDay: Map<String, Int>? = null
    /** Days whose not-yet-read cells are on screen: the listing reads those files first. Set by the UI. */
    @Volatile var wantedDays: Set<String> = emptySet()

    // EXIF metadata (position, film), read in the background after the listing and kept in SQLite
    @Volatile var meta: Map<String, Meta> = emptyMap()
    @Volatile var metaVersion: Int = 0
    @Volatile var metaScanning: Boolean = false
    @Volatile var metaDone: Int = 0
    @Volatile var metaTotal: Int = 0

    // Viewer quick previews (camera's size-S copy, JPEG inside a RAF): bumped when one lands in Previews
    @Volatile var previewVersion: Int = 0
    /** Files whose quick preview the camera could not give (key name|date): the viewer fetches the original. */
    @Volatile var previewMissing: Set<String> = emptySet()

    // Viewer (long press): the original being fetched into the cache
    @Volatile var viewHandle: Long = -1
    @Volatile var viewDone: Long = 0
    @Volatile var viewTotal: Long = 0
    @Volatile var viewFile: String? = null
    @Volatile var viewError: String = ""

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Bytes done and total of the running batch. With placeholder sizes in the listing (gallery mode) each file
     * gets its real size only when its turn comes: the files still queued are counted at the average of those
     * sized so far, so the bar and the time left stay sensible.
     */
    fun batchEstimate(): Pair<Long, Long> {
        val done = batchBytesDone + progressDone
        if (sizesKnown || batchIndex <= 0) return done to batchBytesTotal
        val sized = batchBytesDone + progressTotal // finished files + the current one
        val avg = sized / batchIndex
        return done to sized + avg * (batchCount - batchIndex).coerceAtLeast(0)
    }

    fun add(l: Listener) = listeners.add(l)
    fun remove(l: Listener) = listeners.remove(l)

    fun publish() {
        main.post { listeners.forEach { it.onChanged() } }
    }
}

/** One day of the card: "yyyyMMdd" and how many files it holds. */
data class DayGroup(val day: String, val count: Int)
