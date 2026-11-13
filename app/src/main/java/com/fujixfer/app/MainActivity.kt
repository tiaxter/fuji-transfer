package com.fujixfer.app

import android.Manifest
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.fujixfer.core.FujiBle
import com.fujixfer.core.ObjectInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The screens of the "Fuji Transfer · Material You" design, as plain Android views: connect, connecting,
 * connection error, gallery (day sections, three square cells per row, tonal selection), download and a
 * full-screen preview. Colours come from res/values*: the design's green palette, and the wallpaper colours
 * (Material You) on Android 12 and later.
 */
class MainActivity : ComponentActivity(), TransferState.Listener {
    private companion object {
        const val ACTION_USB_PERMISSION = "com.fujixfer.app.USB_PERMISSION"
    }

    private enum class Filter { NEW, ALL, PHOTO, RAW, VIDEO }

    // screens: one root (AppRoot), the layers shown or hidden from onChanged and the user's actions
    private var showConnect by mutableStateOf(true)
    private var showConnecting by mutableStateOf(false)
    private var showError by mutableStateOf(false)
    private var showGallery by mutableStateOf(false)
    private var showDownload by mutableStateOf(false)
    private var dateOpen by mutableStateOf(false)
    private var previewOpen by mutableStateOf(false)
    /** The "Registro tecnico" screen, over everything but the dialogs. */
    private var logOpen by mutableStateOf(false)
    private var immichOpen by mutableStateOf(false)
    /** "Caricamenti": the Immich queue file by file, over everything but the dialogs. */
    private var uploadsOpen by mutableStateOf(false)
    /** "Download finito" (or the drop card), over the gallery after a batch ends. */
    private var doneOpen by mutableStateOf(false)
    private var doneUi by mutableStateOf(DoneUi())
    /** The last batch already shown or ignored (TransferState.lastBatch.id). */
    private var seenBatchId = 0L
    /** After "Riconnetti e riprendi": the files to fetch once the camera is back (keys name|date), null = none. */
    private var resumeKeys: List<String>? = null
    /** 0 = disconnecting first, 1 = connecting, 2 = connected (waiting for the listing). */
    private var resumeStep = 0
    private var resumePosted = false

    // The listing saved at the last connection, shown as the gallery before the camera is there ("Dalla cache"):
    // from "Apri la galleria salvata" (offline) or "Sfoglia intanto la galleria salvata" (while connecting).
    private var cacheBrowse by mutableStateOf(false)
    private var cache: List<ObjectInfo>? = null
    private var cacheKey: String? = null
    /** Files chosen in the saved listing (keys name|date): chosen again as the camera's own list brings them. */
    private var pendingSelKeys: Set<String>? = null
    /** The files the gallery is made of: the camera's list, or the saved one while browsing it offline. */
    private val shownList: List<ObjectInfo>
        get() = if (inCache) cache.orEmpty() else TransferState.objects
    private val inCache: Boolean get() = cacheBrowse && !TransferState.connected && cache != null

    // How long each step of the connection takes ("5,6 s"), and when it ended, for the connecting and error screens
    private var attemptStart = 0L
    private var stepStart = 0L
    private var lastStep = -1
    private val stepTook = arrayOfNulls<Long>(4)
    private val stepEndedAt = arrayOfNulls<Long>(4)
    private var wasConnecting = false
    /** The step the last attempt stopped at (-1 = it did not fail while connecting), and when. */
    private var failedStep = -1
    private var failedAt = 0L
    /** "Annullare il download?" and "Scollegare la X-T5?" sheets. */
    private var cancelInfo by mutableStateOf<CancelInfo?>(null)
    private var disconnectInfo by mutableStateOf<DisconnectInfo?>(null)
    /** When the camera got connected (for "Collegata da 6 min"). */
    private var connectedAtMs = 0L
    private var wasConnected = false
    // "Tieni lo schermo acceso": while a batch runs the screen stays on, and after a while without touches the
    // light goes to the minimum behind an almost black screen (DimScreen)
    private var dimmed by mutableStateOf(false)
    private var awakeArmed = false
    private val dimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dimRunnable = Runnable { if (awakeArmed) applyDim(true) }
    /** A confirmation, over everything (null = none). */
    private var appDialog by mutableStateOf<AppDialog?>(null)

    private sealed class AppDialog {
        /** [onDismiss] runs on the negative button and when the dialog is cancelled. */
        class Confirm(
            val title: String, val message: String, val confirm: String, val dismiss: String,
            val onConfirm: () -> Unit, val onDismiss: () -> Unit = {},
        ) : AppDialog()

    }
    /** The user went back to the gallery: downloads go on, a strip (and the notification) bring them back. */
    private var downloadsHidden = false
    /** A batch started by "Immich": it runs behind the gallery (strip), not on the download screen. */
    private var quietBatchPending = false
    private var mapScreen: MapScreen? = null
    /** Gallery limited to an area picked on the map (keys name|date), null = no area filter. */
    /** Days picked in the date filter ("yyyyMMdd".."yyyyMMdd"), null = all. */
    private var dateRange: Pair<String, String>? = null
    /** The date filter's layer: each opening brings a new session (the layer stays composed while hidden). */
    private var dateSession by mutableStateOf<DateSession?>(null)
    private var dateSessionId = 0
    /** The file whose details sheet is open over the viewer (null = closed). */
    /** The offline preview, null until the first one. */
    private var previewUi by mutableStateOf<PreviewUi?>(null)
    private var dateLabel = ""
    private var areaKeys: Set<String>? = null
    private var areaLabel = ""
    /** Film simulations chosen in the filter (names as Exif gives them); empty = no film filter. */
    private var films: Set<String> = emptySet()
    private var filmSheet by mutableStateOf(false)
    /** Place name of each file in the place filter (for the day headers: "· Duomo, 48 foto"). */
    private var placeNames: Map<String, String> = emptyMap()

    // connect, connecting and error screens (Compose): what they show, rebuilt by updateConnect / updateConnecting / updateError
    private var connectUi by mutableStateOf(ConnectUi())
    private var connectingUi by mutableStateOf(ConnectingUi())
    private var errorUi by mutableStateOf(ErrorUi())
    // text boxes of the manual connection
    private var ssidText by mutableStateOf("FUJIFILM")
    private var passText by mutableStateOf("")

    // gallery
    /** Where each grid cell on screen is (the viewer grows out of the cell and shrinks back into it). */
    private val tileCoords = HashMap<Long, androidx.compose.ui.layout.LayoutCoordinates>()
    /** The viewer closing onto a file whose cell is not drawn asks the grid to bring its row into view. */
    private var gridScrollToken by mutableIntStateOf(0)
    private var gridScrollRow by mutableIntStateOf(-1)
    /** The open viewer's state and actions (a layer over everything), null = closed. */
    private var viewerHost by mutableStateOf<Pair<ViewerState, ViewerActions>?>(null)
    /** Shown over the grid when it has nothing to show (a note on why); empty = hidden. */
    private var emptyText by mutableStateOf("")
    /** The gallery has nothing to show: 1 = every file is on the phone already ("Nessuna foto nuova"), 2 = the
     *  card is empty; 0 = [emptyText] (or nothing) says it. */
    private var emptyKind by mutableStateOf(0)

    // "Impostazioni", the battery screen, the first-start permissions and the low-storage dialog
    private var settingsOpen by mutableStateOf(false)
    /** Bumped when something the settings show changed (pairing, battery, saved data). */
    private var settingsTick by mutableStateOf(0)
    /** "Cartella di salvataggio" is open over the settings. */
    private var folderOpen by mutableStateOf(false)
    private var savedDataBytes = 0L
    /** The battery screen is shown; its two buttons run this afterwards. */
    private var batteryThen by mutableStateOf<(() -> Unit)?>(null)
    private var permsOpen by mutableStateOf(false)
    /** Bumped after Android's permission dialog, so the permissions screen reads the state again. */
    private var permTick by mutableStateOf(0)
    private var lowStorage by mutableStateOf<LowStorageInfo?>(null)
    // top (bar, banner, chips, work bar) and bottom (download strip, selection bar) of the gallery: Compose
    private var galleryTopUi by mutableStateOf(GalleryTopUi())
    private var galleryBottomUi by mutableStateOf(GalleryBottomUi())

    // download screen (Compose): what it shows, rebuilt by updateDownload
    private var downloadUi by mutableStateOf(DownloadUi())

    private var useWifi = true
    private var manualOpen = false
    private var filter = Filter.NEW
    private var shownObjects: List<ObjectInfo>? = null
    private var lastBusy = false
    private var files: List<ObjectInfo> = emptyList()
    /** Rows of the gallery grid; [gridTick] changes when what the cells show (selection, downloads, thumbnails) changes. */
    private var rows by mutableStateOf<List<GridRow>>(emptyList())
    private var gridTick by mutableIntStateOf(0)
    /** Indexes of the grid rows on screen. */
    private var visibleRows: List<Int> = emptyList()
    private var done: Set<String> = emptySet()
    private var doneVersion = -1
    /** Camera files ("name|date") waiting for Immich, and those known to be there. */
    private var immichQueued: Set<String> = emptySet()
    private var immichDone: Set<String> = emptySet()
    private var immichVersion = -1
    /** The last "Immich" from the gallery, for Annulla; [uploadSnack] is its message ("" = none). */
    private var lastQueued: List<ObjectInfo> = emptyList()
    private var uploadSnack = ""
    private val snackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideSnack = Runnable {
        uploadSnack = ""
        updateBottom()
    }
    private var thumbRefreshPosted = false
    private val selected = HashSet<Long>()
    /** Error text the user closed with "back": not shown again until a different error arrives. */
    private var dismissedError: String? = null

    // speed estimate (exponential moving average)
    private var lastT = 0L
    private var lastBytes = 0L
    private var speed = 0.0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun color(res: Int) = getColor(res)

    /** Vector icon at a given size and colour, for compound drawables. */
    private fun icon(res: Int, sizeDp: Int, colorRes: Int): Drawable {
        val d = getDrawable(res)!!.mutate()
        d.setTint(color(colorRes))
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        return d
    }

    // ------------------------------------------------------------------ gallery rows

    // ------------------------------------------------------------------ gallery grid cells

    /** How a cell looks now: selection, downloaded mark, badge, and the thumbnail (requested by the grid when missing). */
    private fun tileUi(i: ObjectInfo): TileUi {
        val sel = i.handle in selected
        val isDone = key(i) in done
        var badge: String? = null
        var badgeIcon: Int? = null
        when (kindOf(i)) {
            Kind.RAW -> badge = "RAW"
            Kind.VIDEO -> {
                badge = "VIDEO"
                badgeIcon = R.drawable.ic_play
            }
            Kind.OTHER -> badge = i.filename.substringAfterLast('.', "").uppercase(Locale.ROOT)
            Kind.PHOTO -> {
                val film = TransferState.meta[MetaStore.key(i.filename, i.captureDate)]?.film
                if (film != null) {
                    badge = Films.label(this, film)
                    badgeIcon = R.drawable.ic_film
                }
            }
        }
        val bmp = Thumbs.get(i)
        val missing = bmp == null && Thumbs.isMissing(i)
        return TileUi(
            bitmap = bmp,
            selected = sel,
            done = isDone,
            onImmich = key(i) in immichDone,
            immichQueued = key(i) in immichQueued,
            badge = badge,
            badgeIcon = badgeIcon,
            placeholder = if (missing) getString(kindOf(i).label) else "",
            loading = bmp == null && !missing && TransferState.connected,
            want = bmp == null && !missing && inCache, // offline: the thumbnails already on disk
            description = i.filename + (if (sel) getString(R.string.cd_tile_selected) else "") + (if (isDone) getString(R.string.cd_tile_on_phone) else "") +
                (if (key(i) in immichQueued) getString(R.string.cd_tile_queued) else if (key(i) in immichDone) getString(R.string.cd_tile_on_immich) else ""),
        )
    }

    /** A tap on a cell, like Google Photos: opens the photo; once something is selected (selection mode) it selects. */
    private fun tileTapped(i: ObjectInfo) {
        if (selected.isEmpty() && !inCache) openViewer(i) else toggle(i)
    }

    // bulk selection by dragging (touch and hold, then drag over other cells)
    private var flatTiles: List<ObjectInfo> = emptyList()
    private var flatIndex: Map<Long, Int> = emptyMap()
    private var dragBase: Set<Long> = emptySet()
    private var dragAnchor = -1
    private var dragAdd = true
    /** The user has selected by dragging once: the hint about it is no longer shown. */
    private val dragUsedInit by lazy { getSharedPreferences("fuji", MODE_PRIVATE).getBoolean("dragUsed", false) }
    private var dragUsedSet: Boolean? = null
    private var dragUsed: Boolean
        get() = dragUsedSet ?: dragUsedInit
        set(v) { dragUsedSet = v }
    private var dragLast = -1

    private fun dragStart(i: ObjectInfo) {
        if (!dragUsed) {
            dragUsed = true
            getSharedPreferences("fuji", MODE_PRIVATE).edit().putBoolean("dragUsed", true).apply()
        }
        dragBase = HashSet(selected)
        dragAnchor = flatIndex[i.handle] ?: -1
        dragAdd = i.handle !in selected // started on a selected cell: the drag removes
        dragLast = -1
        dragApply(dragAnchor)
    }

    private fun dragOver(i: ObjectInfo) {
        dragApply(flatIndex[i.handle] ?: return)
    }

    /** The selection is what it was before the drag, plus (or minus) every cell from where it started to [idx]. */
    private fun dragApply(idx: Int) {
        if (dragAnchor < 0 || idx < 0 || idx == dragLast) return
        dragLast = idx
        selected.clear()
        selected.addAll(dragBase)
        for (k in minOf(dragAnchor, idx)..maxOf(dragAnchor, idx)) {
            val h = flatTiles.getOrNull(k)?.handle ?: continue
            if (dragAdd) selected.add(h) else selected.remove(h)
        }
        refreshVisibleRows(animate = true)
        updateBottom()
        viewer?.paintSelect()
    }

    private fun headerAction(h: GridRow.Header) {
        val allSel = h.items.all { it.handle in selected }
        if (allSel) h.items.forEach { selected.remove(it.handle) } else h.items.forEach { selected.add(it.handle) }
        refreshVisibleRows(animate = true)
        updateBottom()
    }

    /** Days with grey cells on screen: the service reads their files next (like XApp, which reads what you see). */
    private fun updateWantedDays() {
        if (!TransferState.listing || TransferState.dayGroups.isEmpty()) {
            if (TransferState.wantedDays.isNotEmpty()) TransferState.wantedDays = emptySet()
            return
        }
        val days = LinkedHashSet<String>()
        for (p in visibleRows) {
            val r = rows.getOrNull(p)
            if (r is GridRow.Tiles && r.ghosts > 0) days.add(r.day)
        }
        if (days != TransferState.wantedDays) TransferState.wantedDays = days
    }

    /** Draws the cells on screen again (the grid reads selection, downloads and thumbnails when it draws them). */
    @Suppress("UNUSED_PARAMETER")
    private fun refreshVisibleRows(animate: Boolean) {
        gridTick++
    }

    // ------------------------------------------------------------------ download list

    /** Second line of a file on the download screen: size (or bytes so far for the file in progress), RAW tag. */
    private fun rowMeta(info: ObjectInfo, current: Boolean, finished: Boolean): String {
        val st = TransferState
        val size = when {
            !current && !finished && !st.sizesKnown -> getString(R.string.size_at_download) // still the listing's placeholder
            info.sizeOverflow() -> "≥ 4 GB"
            else -> formatSize(info.size)
        }
        return when {
            current && st.progressTotal > 0 -> getString(R.string.x_of_y, formatSize(st.progressDone), formatSize(st.progressTotal))
            kindOf(info) == Kind.RAW -> "$size · RAW"
            else -> size
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private val usbReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                startUsb(device)
            } else {
                TransferState.statusIsError = true
                TransferState.status = getString(R.string.usb_denied)
                TransferState.publish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        Thumbs.init(this)
        Previews.init(this)
        bindViews()
        seenBatchId = TransferState.lastBatch?.id ?: 0L
        setContent { FujiTheme { AppRoot() } }
        onBackPressedDispatcher.addCallback(this, backCallback)
        // the first start explains the two permissions before Android asks; later the "Connetti" asks if needed
        val prefs = getSharedPreferences("fuji", MODE_PRIVATE)
        if (!prefs.getBoolean("permsShown", false) && (!hasBlePermissions() || !hasNotifPermission())) permsOpen = true
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun openDate() {
        if (TransferState.objects.isEmpty()) return
        dateSession = DateSession(++dateSessionId, dateRange, dayCounts())
        dateOpen = true
    }

    private fun closeDate() {
        dateOpen = false
    }

    private fun hideDownloads() {
        downloadsHidden = true
        onChanged()
    }

    private fun showDownloads() {
        downloadsHidden = false
        onChanged()
    }

    private fun updateStrip() {
        val st = TransferState
        val (doneBytes, total) = st.batchEstimate()
        val pct = if (total > 0) (doneBytes * 100 / total).toInt().coerceIn(0, 100) else 0
        galleryBottomUi = galleryBottomUi.copy(
            stripPercent = pct,
            stripTitle = getString(R.string.download_n_of_m, st.batchIndex, st.batchCount),
            stripSub = st.progressName,
        )
    }

    @Deprecated("Still called with targetSdk 35 when no OnBackInvokedCallback is registered")
    /**
     * Back (button or gesture), through the dispatcher so it also works with Android's gesture navigation: the
     * topmost layer closes first, then each screen goes one step back. The app itself never closes on back: from
     * the connect screen (or while connecting) it only goes to the background, like Home, keeping its state and
     * any connection.
     */
    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    private fun goBack() {
        val v = viewer
        if (v != null && v.showing) {
            v.requestClose() // the viewer closes into its grid cell
            return
        }
        if (lowStorage != null) {
            lowStorage = null
            return
        }
        if (batteryThen != null) {
            batteryLater()
            return
        }
        if (logOpen) {
            logOpen = false // the log screen is over everything: back closes it first
            return
        }
        if (immichOpen) {
            immichOpen = false
            return
        }
        if (uploadsOpen) {
            uploadsOpen = false
            return
        }
        if (folderOpen) {
            folderOpen = false
            settingsTick++
            return
        }
        if (settingsOpen) {
            settingsOpen = false
            return
        }
        if (doneOpen) {
            closeDone()
            return
        }
        if (previewOpen) {
            previewOpen = false // the offline preview is over everything
            return
        }
        if (dateOpen) {
            closeDate() // the date filter is over everything: back closes it first
            return
        }
        if (showDownload) {
            hideDownloads() // back from the download screen: to the gallery, downloads go on
            return
        }
        if (showGallery && selected.isNotEmpty()) {
            clearSelection() // like Google Photos: back first drops the selection
            return
        }
        if (permsOpen) {
            moveTaskToBack(true) // the first-start screen stays until "Continua"
            return
        }
        if (cacheBrowse && !TransferState.connected) {
            leaveCache() // the saved gallery: back to the connect (or connecting) screen
            return
        }
        if (showGallery) {
            confirmDisconnect() // as the arrow at the top of the gallery: asked first
            return
        }
        if (showError) {
            dismissedError = TransferState.status // as the error screen's arrow: back to the connect screen
            onChanged()
            return
        }
        if (showConnect && manualOpen) {
            manualOpen = false
            onChanged()
            return
        }
        // connect screen, or a connection being made: the app goes to the background, nothing is cancelled
        moveTaskToBack(true)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(TransferService.EXTRA_SHOW_DOWNLOADS, false)) {
            intent.removeExtra(TransferService.EXTRA_SHOW_DOWNLOADS) // once, not on every onStart
            downloadsHidden = false
        }
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && !TransferState.connected && !TransferState.busy) {
            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
                useWifi = false
                requestUsb(it)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Thumbs.onLoaded = {
            // many thumbnails arrive in a burst: one refresh per frame, of the visible cells only
            if (!thumbRefreshPosted) {
                thumbRefreshPosted = true
                window.decorView.postOnAnimation {
                    thumbRefreshPosted = false
                    refreshVisibleRows(animate = false)
                    downloadUi = downloadUi.copy(thumbVersion = downloadUi.thumbVersion + 1)
                    viewer?.onThumbs()
                }
            }
        }
        TransferState.add(this)
        permTick++ // a permission or the battery limit may have changed in Android's settings
        settingsTick++
        onChanged()
        DebugLog.add("app in primo piano")
    }

    override fun onStop() {
        DebugLog.add("app in background")
        viewer?.player?.pause() // not on screen: no sound from a video nobody sees
        Thumbs.onLoaded = null
        TransferState.remove(this)
        super.onStop()
    }

    override fun onDestroy() {
        dimHandler.removeCallbacksAndMessages(null)
        viewer?.close() // releases the player
        sideDecoder.shutdownNow()
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun bindViews() {
        ssidText = getSharedPreferences("fuji", MODE_PRIVATE).getString("ssid", "FUJIFILM") ?: "FUJIFILM"
    }

    private val gridActions by lazy {
        GridActions(
            tileUi = this::tileUi,
            allSelected = { items -> items.all { it.handle in selected } },
            onTap = { tileTapped(it) },
            onDragStart = { dragStart(it) },
            onDragOver = { dragOver(it) },
            onDragEnd = { dragAnchor = -1 },
            onHeader = { headerAction(it) },
            onRequest = { Thumbs.request(it) },
            onCancel = { Thumbs.cancel(it) },
            onVisible = {
                visibleRows = it
                updateWantedDays() // grey cells on screen: read those days next
            },
            onTileCoords = { handle, coords -> if (coords == null) tileCoords.remove(handle) else tileCoords[handle] = coords },
        )
    }

    private fun tileBounds(handle: Long): androidx.compose.ui.geometry.Rect? =
        tileCoords[handle]?.takeIf { it.isAttached }?.boundsInRoot()

    private fun scrollGridTo(info: ObjectInfo) {
        val row = rows.indexOfFirst { it is GridRow.Tiles && it.items.any { i -> i.handle == info.handle } }
        if (row >= 0) {
            gridScrollRow = row
            gridScrollToken++
        }
    }

    private val galleryTopActions by lazy {
        GalleryTopActions(
            // the arrow at the top of the gallery leaves the camera: asked first, a new connection takes a while
            onBack = { if (cacheBrowse && !TransferState.connected) leaveCache() else confirmDisconnect() },
            onSelectAll = {
                val all = files.isNotEmpty() && files.all { it.handle in selected }
                if (all) files.forEach { selected.remove(it.handle) } else files.forEach { selected.add(it.handle) }
                refreshVisibleRows(animate = true)
                updateBottom()
            },
            onBanner = { showLog() },
            onDate = { openDate() },
            onClearDate = { clearDate() },
            onArea = { openMap() },
            onClearArea = { clearArea() },
            onFilm = { filmSheet = true },
            onClearFilm = { clearFilm() },
            onFilter = { i ->
                filter = Filter.values()[i]
                rebuildRows()
                updateChips()
            },
            onLink = {
                // the card over the saved gallery: stop the attempt, or start one
                if (TransferState.busy) sendAction(TransferService.ACTION_DISCONNECT) else connectClicked()
            },
        )
    }

    // ------------------------------------------------------------------ the root: every screen is a layer

    /** A layer fades in rising 24 dp, and fades out (Material motion); no animation at start-up. */
    @Composable
    private fun Layer(visible: Boolean, content: @Composable () -> Unit) {
        val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.roundToPx() }
        androidx.compose.animation.AnimatedVisibility(
            visible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { rise },
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) { content() }
    }

    /**
     * Screens from the "Fuji Transfer · Material You" design. One activity, one root: connect, connecting, error,
     * gallery; over the gallery the download screen, the date filter and the offline preview; over everything the
     * details sheet and the dialogs.
     */
    @Composable
    private fun AppRoot() {
        val c = androidx.compose.material3.MaterialTheme.colorScheme
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(c.background).systemBarsPadding().imePadding(),
        ) {
            Layer(showConnect) {
                ConnectScreen(
                    ui = connectUi,
                    ssid = ssidText, onSsid = { ssidText = it },
                    pass = passText, onPass = { passText = it },
                    a = ConnectActions(
                        onWifi = { useWifi = true; onChanged() },
                        onUsb = { useWifi = false; onChanged() },
                        onConnect = { connectClicked() },
                        onRepair = { startBle(TransferService.ACTION_PAIR_BLE) },
                        onForget = { confirmForget() },
                        onManualToggle = { manualOpen = !manualOpen; onChanged() },
                        onManualConnect = { startWifi() },
                        onLog = { showLog() },
                        onShareLog = { shareLog() },
                        onImmich = { showUploads() },
                        onSavedGallery = { openSavedGallery() },
                        onSettings = { openSettings() },
                    ),
                )
            }
            Layer(showConnecting) {
                ConnectingScreen(
                    connectingUi, onCancel = { sendAction(TransferService.ACTION_DISCONNECT) },
                    onBrowse = { openSavedGallery() },
                )
            }
            Layer(showError) {
                ErrorScreen(
                    ui = errorUi,
                    onBack = { dismissedError = TransferState.status; onChanged() },
                    onCopy = { copyLog(DebugLog.text().lines().filter { it.isNotBlank() }.takeLast(300).joinToString("\n")) },
                    onRetry = { dismissedError = TransferState.status; connectClicked() },
                    onRepair = { dismissedError = TransferState.status; startBle(TransferService.ACTION_PAIR_BLE) },
                )
            }
            Layer(showGallery) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    GalleryTop(galleryTopUi, galleryTopActions)
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                        GalleryGrid(rows, gridTick, selected.isNotEmpty(), gridActions, gridScrollToken, gridScrollRow)
                        if (emptyKind != 0) {
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background)) {
                                EmptyGallery(
                                    cardEmpty = emptyKind == 2, count = TransferState.objects.size,
                                    onShowAll = {
                                        filter = Filter.ALL
                                        rebuildRows()
                                        updateChips()
                                    },
                                    onDisconnect = { confirmDisconnect() },
                                )
                            }
                        } else if (emptyText.isNotEmpty()) {
                            androidx.compose.material3.Text(
                                emptyText, Modifier.align(androidx.compose.ui.Alignment.Center).fillMaxWidth().padding(32.dp),
                                color = c.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = type(14, 20),
                            )
                        }
                    }
                    GalleryBottom(
                        galleryBottomUi, onStrip = { showDownloads() }, onDownload = { downloadSelected() },
                        onUpload = { uploadSelected() }, onUndo = { undoUpload() }, onClear = { clearSelection() },
                    )
                }
            }
            // over the gallery: they take the touches, so the cells under them stay quiet
            Layer(showDownload) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    DownloadScreen(
                        ui = downloadUi,
                        rowMeta = this@MainActivity::rowMeta,
                        onBack = { hideDownloads() },
                        onCancel = { confirmCancel() },
                        onBackground = { hideDownloads() },
                        keepAwake = keepAwakeState,
                        onKeepAwake = { setKeepAwake(it) },
                    )
                }
            }
            Layer(doneOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    DownloadDoneScreen(doneUi, doneActions)
                }
            }
            Layer(dateOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    dateSession?.let { session ->
                        DateScreen(
                            session,
                            onApply = { range, label ->
                                closeDate()
                                dateRange = range
                                dateLabel = label
                                if (range != null && filter == Filter.NEW) filter = Filter.ALL // the days asked for, all of them
                                rebuildRows()
                                updateChips()
                            },
                            onClose = { closeDate() },
                        )
                    }
                }
            }
            Layer(previewOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).blockTouches()) {
                    previewUi?.let { ui ->
                        PreviewScreen(
                            ui,
                            onBack = { previewOpen = false },
                            onSelect = {
                                val info = previewInfo ?: return@PreviewScreen false
                                toggle(info)
                                info.handle in selected
                            },
                            onDownload = {
                                val info = previewInfo ?: return@PreviewScreen
                                previewOpen = false
                                downloadsHidden = false
                                startServiceForeground(TransferService.ACTION_DOWNLOAD) { it.putExtra(TransferService.EXTRA_HANDLES, longArrayOf(info.handle)) }
                            },
                            onUpload = { previewInfo?.let { toggleUpload(it) } ?: ImmichMark.None },
                        )
                    }
                }
            }
            Layer(settingsOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    SettingsScreen(settingsGroups(settingsTick, keepAwakeState), onBack = { settingsOpen = false; onChanged() })
                }
            }
            Layer(folderOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    SaveFolderScreen(onBack = { folderOpen = false; settingsTick++ })
                }
            }
            Layer(batteryThen != null) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    BatteryScreen(onAllow = { batteryAllow() }, onLater = { batteryLater() })
                }
            }
            Layer(logOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    LogScreen(
                        onBack = { logOpen = false },
                        onCopy = { copyLog(it) },
                        onShare = { shareLog() },
                        onClear = { confirmClearLog() },
                    )
                }
            }
            Layer(immichOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    ImmichScreen(
                        onBack = { immichOpen = false; onChanged() },
                        onUploads = { immichOpen = false; uploadsOpen = true },
                    )
                }
            }
            Layer(uploadsOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    UploadsScreen(
                        onBack = { uploadsOpen = false; onChanged() },
                        onSettings = { uploadsOpen = false; immichOpen = true },
                        thumb = { k -> TransferState.objects.firstOrNull { key(it) == k }?.let { Thumbs.get(it) } },
                    )
                }
            }
            Layer(permsOpen) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.background).blockTouches()) {
                    // read again after Android's answers and when coming back from its settings
                    val (near, notif) = androidx.compose.runtime.remember(permTick) { hasBlePermissions() to hasNotifPermission() }
                    PermissionsScreen(
                        near = near, notif = notif,
                        onNear = { askPermissions(nearPermissions(), "nearAsked") },
                        onNotif = { askPermissions(notifPermissions(), "notifAsked") },
                        onContinue = {
                            permsOpen = false
                            getSharedPreferences("fuji", MODE_PRIVATE).edit().putBoolean("permsShown", true).apply()
                        },
                    )
                }
            }
            lowStorage?.let { info ->
                LowStorageDialog(
                    info,
                    onFirst = {
                        lowStorage = null
                        downloadsHidden = false
                        startServiceForeground(TransferService.ACTION_DOWNLOAD) { it.putExtra(TransferService.EXTRA_HANDLES, info.fitHandles) }
                    },
                    onFree = {
                        lowStorage = null
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                        } catch (e: android.content.ActivityNotFoundException) {
                            DebugLog.add("impostazioni della memoria non disponibili su questo telefono")
                        }
                    },
                    onCancel = { lowStorage = null },
                )
            }
        }
        // the full-size viewer: over the whole window, system bars included
        viewerHost?.let { (state, actions) -> ViewerScreen(state, actions) }
        if (filmSheet) {
            FilmSheet(
                options = filmOptions(),
                initial = films,
                scanning = TransferState.metaScanning,
                onDismiss = { filmSheet = false },
                onApply = { chosen ->
                    filmSheet = false
                    applyFilms(chosen)
                },
            )
        }
        cancelInfo?.let { info ->
            CancelSheet(
                info,
                onKeep = { cancelInfo = null },
                onCancel = { cancelInfo = null; sendAction(TransferService.ACTION_CANCEL) },
                onFinishCurrent = { cancelInfo = null; sendAction(TransferService.ACTION_STOP_AFTER) },
            )
        }
        disconnectInfo?.let { info ->
            DisconnectSheet(
                info,
                onStay = { disconnectInfo = null },
                onDisconnect = { disconnectInfo = null; sendAction(TransferService.ACTION_DISCONNECT) },
            )
        }
        AppDialogs()
        if (dimmed) DimScreen(downloadUi) { wake() }
        }
    }

    private fun clearSelection() {
        selected.clear()
        refreshVisibleRows(animate = true)
        updateBottom()
    }

    private fun confirmDisconnect() {
        val st = TransferState
        val downloading = st.busy && st.progressName.isNotEmpty()
        if (!downloading) {
            val min = ((System.currentTimeMillis() - connectedAtMs) / 60_000).coerceAtLeast(0)
            val usb = st.connectionLabel == "USB"
            disconnectInfo = DisconnectInfo(
                camera = st.cameraName.ifEmpty { getString(R.string.camera_lower) },
                since = (if (connectedAtMs == 0L) getString(R.string.since_connected) else if (min < 1) getString(R.string.since_under_minute)
                else getString(R.string.since_min, min)) + " · " + (if (usb) "USB" else "Wi‑Fi"),
                usb = usb,
                immichQueued = if (ImmichSync.settings(this).configured) immichQueued.size else 0,
                wifiOnly = ImmichSync.settings(this).wifiOnly,
            )
            return
        }
        appDialog = AppDialog.Confirm(
            title = getString(if (downloading) R.string.dlg_stop_title else R.string.dlg_disconnect_title),
            message = if (downloading) getString(R.string.dlg_stop_body, (st.batchCount - st.batchIndex + 1).coerceAtLeast(1))
            else getString(R.string.dlg_disconnect_body),
            confirm = getString(if (downloading) R.string.dlg_stop else R.string.disconnect),
            dismiss = getString(if (downloading) R.string.continue_ else R.string.dlg_stay),
            onConfirm = { sendAction(if (downloading) TransferService.ACTION_CANCEL else TransferService.ACTION_DISCONNECT) },
        )
    }

    /** "Annulla" on the download screen: asked first, a long batch is easy to stop by mistake. */
    private fun confirmCancel() {
        val st = TransferState
        if (!(st.busy && st.progressName.isNotEmpty())) return
        val pct = if (st.progressTotal > 0) (st.progressDone * 100 / st.progressTotal).toInt().coerceIn(0, 99) else 0
        cancelInfo = CancelInfo(
            doneFiles = (st.batchIndex - 1).coerceAtLeast(0),
            doneSize = formatSize(st.batchBytesDone),
            current = st.progressName,
            currentPercent = pct,
            currentSize = getString(R.string.x_of_y, formatSize(st.progressDone), formatSize(st.progressTotal)),
            queued = (st.batchCount - st.batchIndex).coerceAtLeast(0),
        )
    }

    private fun connectClicked() {
        if (useWifi && askBatteryExemption { connectClicked() }) return
        if (useWifi) startBle(TransferService.ACTION_CONNECT_BLE) else connectUsbClicked()
    }

    /**
     * Once: with the screen off the camera's Wi-Fi went down twice about 18 s later (3 Nov), and the phone did
     * not bring it back until the screen was on. Excluding the app from battery optimisation may help; whether
     * it does is not known yet, so the text does not promise it. True if the dialog was shown ({@code then}
     * runs after it).
     */
    private fun askBatteryExemption(then: () -> Unit): Boolean {
        val prefs = getSharedPreferences("fuji", MODE_PRIVATE)
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("batteryAsked", false)) return false
        prefs.edit().putBoolean("batteryAsked", true).apply()
        batteryThen = then
        return true
    }

    /** "Consenti" on the battery screen: Android's own confirmation, then whatever was waiting. */
    private fun batteryAllow() {
        val then = batteryThen ?: {}
        batteryThen = null
        try {
            @android.annotation.SuppressLint("BatteryLife")
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: android.content.ActivityNotFoundException) {
            DebugLog.add("richiesta di esclusione dall'ottimizzazione batteria non disponibile su questo telefono")
        }
        settingsTick++
        then()
    }

    private fun batteryLater() {
        val then = batteryThen ?: {}
        batteryThen = null
        then()
    }

    override fun onChanged() {
        val st = TransferState
        viewer?.update()
        mapScreen?.let { if (it.closed) mapScreen = null else it.update() }

        // new data from the service
        if (st.doneVersion != doneVersion) { // re-read the marks only when a download added one
            doneVersion = st.doneVersion
            done = doneSet()
        }
        if (ImmichSync.version != immichVersion) readImmichMarks()
        if (cacheBrowse && st.connected) {
            // the camera's own list takes over from the saved one: what was chosen there stays chosen
            val chosen = cache.orEmpty().filter { it.handle in selected }.map { key(it) }
            pendingSelKeys = pendingSelKeys.orEmpty() + chosen
            selected.clear()
            cacheBrowse = false
        }
        pendingSelKeys?.let { keys ->
            val hit = st.objects.filter { key(it) in keys }
            hit.forEach { selected.add(it.handle) }
            pendingSelKeys = (keys - hit.map { key(it) }.toSet()).takeIf { it.isNotEmpty() && (st.listing || st.busy) }
        }
        val list = shownList
        if (list !== shownObjects) {
            // a RAF gets the thumbnail of its JPG/HEIF (same picture, cheaper to fetch)
            val images = HashMap<String, ObjectInfo>()
            for (o in list) if (!o.filename.endsWith(".RAF", ignoreCase = true) && kindOf(o) == Kind.PHOTO) {
                images[Thumbs.key(o)] = o
            }
            Thumbs.sibling = { raf -> images[Thumbs.key(raf)] }
            val first = shownObjects.isNullOrEmpty()
            shownObjects = list
            // the list grows while the camera is still being read: keep what the user already selected
            val present = list.mapTo(HashSet()) { it.handle }
            selected.retainAll(present)
            if (first && list.isNotEmpty()) {
                filter = if (list.any { key(it) !in done }) Filter.NEW else Filter.ALL
            }
            rebuildRows()
        } else if (lastBusy && !st.busy) {
            // batch finished: files just downloaded are no longer selected
            selected.removeAll(st.objects.filter { key(it) in done }.map { it.handle }.toSet())
            rebuildRows()
        } else {
            refreshVisibleRows(animate = false) // progress ticks: only what is on screen, no full rebind
        }
        val ended = st.lastBatch?.takeIf { it.id != seenBatchId }
        if (ended != null && !st.busy) {
            seenBatchId = ended.id
            onBatchEnded(ended, wasVisible = showDownload)
        }
        lastBusy = st.busy
        if (st.connected && !wasConnected) {
            connectedAtMs = System.currentTimeMillis()
            getSharedPreferences("fuji", MODE_PRIVATE).edit()
                .putLong("lastSessionAt", connectedAtMs).putInt("lastSessionSaved", 0).apply()
        }
        if (!st.connected && wasConnected) cacheKey = null // the list saved at this connection is newer: read it again
        wasConnected = st.connected
        resumeStepCheck()

        // which screen
        if (st.busy) dismissedError = null // a new attempt: its error, if any, must be shown
        val downloading = st.busy && st.progressName.isNotEmpty()
        val connecting = st.busy && !st.connected
        val failed = !st.connected && !st.busy && st.statusIsError && st.status != dismissedError
        trackSteps(connecting)
        if (failed) cacheBrowse = false // the error screen says why
        val browsing = inCache
        showConnect = !st.connected && !connecting && !failed && !browsing
        showConnecting = connecting && !browsing
        showError = failed
        showGallery = st.connected || browsing
        if (downloading) quietBatchPending = false
        if (!downloading && !quietBatchPending) downloadsHidden = false // the next batch opens its screen again
        showDownload = downloading && !downloadsHidden
        galleryBottomUi = galleryBottomUi.copy(strip = downloading && downloadsHidden && st.connected)
        if (downloading && downloadsHidden) updateStrip()

        updateConnect()
        if (connecting) updateConnecting()
        if (failed) updateError()
        if (st.connected) updateGalleryHeader() else if (browsing) updateCacheHeader()
        updateChips()
        updateBottom()
        if (downloading) updateDownload() else {
            lastT = 0L
            speed = 0.0
        }
        applyAwake(downloading)
    }

    // ------------------------------------------------------------------ connect screen

    private fun updateConnect() {
        val st = TransferState
        val usbStatus = if (useWifi) connectUi.usbStatus else {
            val devices = try {
                getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            val cam = devices.firstOrNull()
            if (cam == null) getString(R.string.usb_none) else getString(R.string.usb_connected, cam.productName ?: getString(R.string.camera_usb))
        }
        val paired = pairing()
        val immichSettings = ImmichSync.settings(this)
        val prefs = getSharedPreferences("fuji", MODE_PRIVATE)
        val lastAt = prefs.getLong("lastSessionAt", 0L)
        val lastSaved = prefs.getInt("lastSessionSaved", 0)
        val forImmich = if (immichSettings.configured) immichQueued.size else 0
        connectUi = ConnectUi(
            useWifi = useWifi,
            canConnect = !st.connected && !st.busy,
            cameraName = paired?.name?.ifEmpty { "Fujifilm" } ?: "Fujifilm",
            pairTag = getString(if (paired != null) R.string.paired else R.string.use_xapp_pairing),
            hint = if (paired != null) "" else getString(R.string.pair_hint),
            paired = paired != null,
            // only what the app knows: its own link to the camera's network, not the camera's radio
            wifiTag = when {
                st.connected && st.connectionLabel != "USB" -> getString(R.string.wifi_joined)
                st.busy -> getString(R.string.wifi_starting)
                else -> getString(R.string.wifi_not_joined)
            },
            wifiOn = st.connected && st.connectionLabel != "USB",
            lastWhen = if (lastAt > 0) whenLabel(lastAt) else "",
            lastText = if (lastAt <= 0) "" else (if (lastSaved == 0) getString(R.string.last_none)
            else resources.getQuantityString(R.plurals.last_saved, lastSaved, lastSaved)) +
                if (forImmich > 0) " · " + getString(R.string.last_for_immich, forImmich) else "",
            cachedCount = if (paired != null && !st.busy) savedListing()?.size ?: 0 else 0,
            manualOpen = manualOpen,
            usbStatus = usbStatus,
            backupLinked = immichSettings.configured,
            backupSub = run {
                val how = when (immichSettings.mode) {
                    ImmichSync.MODE_AUTO -> getString(R.string.backup_auto)
                    ImmichSync.MODE_OFFER -> getString(R.string.backup_offer)
                    else -> getString(R.string.backup_ask)
                }
                val waiting = immichQueued.size
                if (waiting > 0) getString(R.string.backup_how_queued, how, waiting) else how
            },
        )
    }

    // ------------------------------------------------------------------ connecting screen

    private fun updateConnecting() {
        val st = TransferState
        val usb = st.connectionLabel == "USB"
        val step = st.connectStep.coerceIn(0, 3)
        val t = typicalTimes()
        val saved = if (usb) null else savedListing()
        connectingUi = ConnectingUi(
            usb = usb,
            step = step,
            title = when {
                usb -> getString(R.string.cg_usb)
                step == 0 -> getString(R.string.cg_search)
                step == 1 -> getString(R.string.cg_wifi)
                step == 2 -> getString(R.string.cg_join)
                else -> getString(R.string.st_listing)
            },
            icon = when {
                usb -> R.drawable.ic_usb
                step == 0 -> R.drawable.ic_bluetooth
                step <= 2 -> R.drawable.ic_wifi
                else -> R.drawable.ic_photos
            },
            startedAt = attemptStart,
            stepStartedAt = stepStart,
            took = stepTook.toList(),
            typical = if (usb) listOf(null, null, null, null) else t.perStep,
            typicalTotal = if (usb) "" else t.label,
            typicalMax = if (usb) 0 else t.max,
            hasCache = !saved.isNullOrEmpty(),
            cachedCount = saved?.size ?: 0,
        )
    }

    /** Notes when each step of the attempt in progress starts and ends; a full Bluetooth connection is remembered. */
    private fun trackSteps(connecting: Boolean) {
        val st = TransferState
        val now = System.currentTimeMillis()
        if (connecting && !wasConnecting) {
            attemptStart = now
            stepStart = now
            lastStep = st.connectStep
            stepTook.fill(null)
            stepEndedAt.fill(null)
            failedStep = -1
        }
        if (connecting && st.connectStep != lastStep) {
            // a step skipped (manual Wi-Fi starts at 2) has no time of its own
            if (st.connectStep > lastStep && lastStep >= 0) {
                stepTook[lastStep] = now - stepStart
                stepEndedAt[lastStep] = now
            }
            lastStep = st.connectStep
            stepStart = now
        }
        if (wasConnecting && !connecting) {
            if (st.connected) {
                if (lastStep == 3) {
                    stepTook[3] = now - stepStart
                    stepEndedAt[3] = now
                }
                if (st.connectionLabel != "USB" && stepTook.all { it != null }) saveTimes(stepTook.map { it!! })
                failedStep = -1
            } else {
                failedStep = lastStep
                failedAt = now
            }
        }
        wasConnecting = connecting
    }

    private class Typical(val perStep: List<Long?>, val label: String, val max: Long)

    /** The last five Bluetooth connections, step by step (ms). */
    private fun pastTimes(): List<List<Long>> =
        (getSharedPreferences("fuji", MODE_PRIVATE).getString("connectTimes", "") ?: "").lines()
            .mapNotNull { line -> line.split(',').mapNotNull { it.toLongOrNull() }.takeIf { it.size == 4 } }

    private fun saveTimes(t: List<Long>) {
        val lines = (pastTimes() + listOf(t)).takeLast(5).joinToString("\n") { it.joinToString(",") }
        getSharedPreferences("fuji", MODE_PRIVATE).edit().putString("connectTimes", lines).apply()
    }

    /** What the last connections took: the median of each step, the range of the totals. Nothing before the first. */
    private fun typicalTimes(): Typical {
        val past = pastTimes()
        if (past.isEmpty()) return Typical(listOf(null, null, null, null), "", 0)
        val per = (0 until 4).map { k -> past.map { it[k] }.sorted()[past.size / 2] }
        val totals = past.map { it.sum() / 1000 }
        val lo = totals.min()
        val hi = totals.max()
        val label = when {
            past.size == 1 -> getString(R.string.typ_last, hi)
            lo == hi -> getString(R.string.typ_usually, hi)
            else -> getString(R.string.typ_range, lo, hi)
        }
        return Typical(per, label, hi * 1000)
    }

    // ------------------------------------------------------------------ error screen

    private fun updateError() {
        errorUi = errorUiFor(
            this, TransferState.errorDetail.ifEmpty { TransferState.status },
            DebugLog.text().lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
                .ifEmpty { getString(R.string.log_empty_short) },
        ).copy(events = connectEvents())
    }

    /** "Cosa è successo": the steps this attempt got through, with their time of day, then the one it stopped at. */
    private fun connectEvents(): List<ConnectEvent> {
        val k = failedStep
        if (k < 0 || TransferState.connectionLabel == "USB") return emptyList()
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val name = TransferState.cameraName.takeIf { it.isNotEmpty() && it != getString(R.string.camera_label) } ?: getString(R.string.the_camera)
        val ok = listOf(
            getString(R.string.ev_bluetooth, name),
            getString(R.string.step_camera_wifi_done),
            getString(R.string.ev_joined),
        )
        val failed = listOf(
            getString(R.string.ev_fail_bluetooth),
            getString(R.string.ev_fail_wifi),
            getString(R.string.ev_fail_join),
            getString(R.string.ev_fail_session),
        )
        val out = ArrayList<ConnectEvent>()
        for (i in 0 until k.coerceAtMost(3)) {
            val at = stepEndedAt[i] ?: continue
            out.add(ConnectEvent(true, ok[i], fmt.format(java.util.Date(at))))
        }
        out.add(ConnectEvent(false, failed[k.coerceIn(0, 3)], fmt.format(java.util.Date(failedAt))))
        return out
    }

    // ------------------------------------------------------------------ gallery

    private fun updateGalleryHeader() {
        val st = TransferState
        val count = if (st.listing && st.listTotal > 0) st.listTotal else st.objects.size // the card's total while loading
        // background work as a thin bar: the list first, then the positions
        val (label, done, total) = when {
            st.listing && st.listTotal > 0 -> Triple(getString(R.string.work_listing), st.listDone, st.listTotal)
            st.metaScanning && st.metaTotal > 0 -> Triple(getString(R.string.work_meta), st.metaDone, st.metaTotal)
            else -> Triple("", 0, 0)
        }
        val transient = !st.statusIsError && !st.statusSteady
        galleryTopUi = galleryTopUi.copy(
            cached = false,
            link = null,
            title = st.cameraName.ifEmpty { getString(R.string.camera_label) },
            info = st.connectionLabel + " · " + resources.getQuantityString(R.plurals.n_files, count, formatCount(count)),
            banner = if (st.statusIsError) st.status + "\n\n" + getString(R.string.banner_tap_log) else "",
            working = total > 0,
            workLabel = label,
            workCount = if (total > 0) getString(R.string.x_of_y, formatCount(done), formatCount(total)) else "",
            workProgress = if (total > 0) (done * 1000L / total).toInt().coerceIn(0, 1000) else 0,
            subtitle = if (transient && st.status.isNotBlank()) st.status else "",
        )
    }

    private fun rebuildRows() {
        val all = shownList
        files = all.filter { matches(it) }
        val out = ArrayList<GridRow>()
        val st = TransferState
        val skeleton = st.listing && st.dayGroups.isNotEmpty() && areaKeys == null && dateRange == null && films.isEmpty() &&
            (filter == Filter.ALL || filter == Filter.NEW)
        if (skeleton) {
            // every day of the card at once, like XApp: files read so far, grey cells for the others
            val shownByDay = files.groupBy { it.captureDate.take(8) }
            val allByDay = all.groupingBy { it.captureDate.take(8) }.eachCount()
            val known = HashSet<String>()
            for (g in st.dayGroups) {
                known.add(g.day)
                val present = shownByDay[g.day].orEmpty()
                val unread = st.unreadByDay
                val ghosts = unread?.get(g.day) ?: (g.count - (allByDay[g.day] ?: 0)).coerceAtLeast(0)
                if (present.isEmpty() && ghosts == 0) continue
                // with handles, the count is what this day really holds (files read with its date + still to read)
                val count = if (unread != null) (allByDay[g.day] ?: 0) + ghosts else g.count
                out.add(GridRow.Header(dayLabel(g.day), photosLabel(count), present))
                var k = 0
                var left = ghosts
                while (k < present.size || left > 0) {
                    val cells = present.subList(k, minOf(k + 3, present.size))
                    val g3 = minOf(3 - cells.size, left)
                    out.add(GridRow.Tiles(cells, g3, g.day))
                    k += cells.size
                    left -= g3
                }
            }
            for ((day, group) in shownByDay) if (day !in known) { // a day 0x9053 did not report (shot just now)
                out.add(GridRow.Header(dayLabel(day), photosLabel(group.size), group))
                for (k in group.indices step 3) out.add(GridRow.Tiles(group.subList(k, minOf(k + 3, group.size))))
            }
        }
        var i = if (skeleton) files.size else 0
        while (i < files.size) {
            val day = files[i].captureDate.take(8)
            var j = i
            while (j < files.size && files[j].captureDate.take(8) == day) j++
            val group = files.subList(i, j)
            val doneCount = group.count { key(it) in done }
            val places = if (areaKeys != null) {
                group.mapNotNull { placeNames[MetaStore.key(it.filename, it.captureDate)] }.distinct()
            } else {
                emptyList()
            }
            val where = if (places.isEmpty()) "" else places.take(2).joinToString(", ") + (if (places.size > 2) "…" else "")
            // "Oltrarno, Santo Spirito · 21 foto · 3 già sul telefono"
            val sub = listOfNotNull(
                where.ifEmpty { null },
                photosLabel(group.size),
                if (doneCount > 0 && filter != Filter.NEW) getString(R.string.n_on_phone, doneCount) else null,
            ).joinToString(" · ")
            out.add(GridRow.Header(dayLabel(day), sub, group))
            for (k in group.indices step 3) out.add(GridRow.Tiles(group.subList(k, minOf(k + 3, group.size))))
            i = j
        }
        rows = out
        flatTiles = out.filterIsInstance<GridRow.Tiles>().flatMap { it.items }
        flatIndex = flatTiles.withIndex().associate { it.value.handle to it.index }
        val noFilters = areaKeys == null && dateRange == null && films.isEmpty()
        emptyKind = when {
            !(files.isEmpty() && TransferState.connected) -> 0
            all.isEmpty() && !TransferState.listing -> 2
            all.isNotEmpty() && noFilters && filter == Filter.NEW && !TransferState.listing -> 1
            else -> 0
        }
        emptyText = if (!(files.isEmpty() && TransferState.connected)) "" else when {
            all.isEmpty() -> getString(R.string.empty_none)
            areaKeys != null || dateRange != null || films.isNotEmpty() -> getString(R.string.empty_filters)
            filter == Filter.NEW -> getString(R.string.empty_new)
            else -> getString(R.string.empty_category)
        }
    }

    private fun toggle(i: ObjectInfo) {
        if (!selected.remove(i.handle)) selected.add(i.handle)
        refreshVisibleRows(animate = true)
        updateBottom()
        viewer?.paintSelect()
    }

    private fun matches(o: ObjectInfo): Boolean =
        (areaKeys?.contains(MetaStore.key(o.filename, o.captureDate)) ?: true) &&
            (dateRange?.let { (a, b) -> o.captureDate.take(8) in a..b } ?: true) && matchesFilm(o) && matchesKind(o)

    private fun matchesFilm(o: ObjectInfo): Boolean {
        if (films.isEmpty()) return true
        val f = TransferState.meta[MetaStore.key(o.filename, o.captureDate)]?.film
        return f != null && f in films
    }

    private fun matchesKind(o: ObjectInfo): Boolean = when (filter) {
        Filter.NEW -> key(o) !in done
        Filter.ALL -> true
        Filter.PHOTO -> kindOf(o) == Kind.PHOTO
        Filter.RAW -> kindOf(o) == Kind.RAW
        Filter.VIDEO -> kindOf(o) == Kind.VIDEO
    }

    private fun clearDate() {
        dateRange = null
        dateLabel = ""
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        rebuildRows()
        updateChips()
    }

    private fun clearFilm() {
        films = emptySet()
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        rebuildRows()
        updateChips()
    }

    private fun applyFilms(chosen: Set<String>) {
        films = chosen
        if (chosen.isNotEmpty() && filter == Filter.NEW) filter = Filter.ALL // a film shows all its photos
        rebuildRows()
        updateChips()
    }

    /** The film simulations read so far, most used first. */
    private fun filmOptions(): List<FilmOption> {
        val counts = LinkedHashMap<String, Int>()
        for (o in TransferState.objects) {
            val f = TransferState.meta[MetaStore.key(o.filename, o.captureDate)]?.film ?: continue
            counts[f] = (counts[f] ?: 0) + 1
        }
        return counts.map { FilmOption(it.key, it.value) }.sortedByDescending { it.count }
    }

    private fun clearArea() {
        areaKeys = null
        areaLabel = ""
        placeNames = emptyMap()
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        rebuildRows()
        updateChips()
    }

    private fun openMap() {
        if (TransferState.objects.isEmpty()) return
        mapScreen = MapScreen(this, areaKeys) { keys, label, places ->
            areaKeys = keys
            areaLabel = label
            placeNames = places
            if (keys != null && filter == Filter.NEW) filter = Filter.ALL // a place shows all its photos
            rebuildRows()
            updateChips()
        }.also { it.open() }
    }

    private fun updateChips() {
        val all = shownList
        val counts = mapOf(
            Filter.NEW to all.count { key(it) !in done },
            Filter.ALL to all.size,
            Filter.PHOTO to all.count { kindOf(it) == Kind.PHOTO },
            Filter.RAW to all.count { kindOf(it) == Kind.RAW },
            Filter.VIDEO to all.count { kindOf(it) == Kind.VIDEO },
        )
        val names = mapOf(
            Filter.NEW to getString(R.string.chip_new), Filter.ALL to getString(R.string.chip_all), Filter.PHOTO to getString(R.string.chip_photos),
            Filter.RAW to "RAW", Filter.VIDEO to getString(R.string.chip_video),
        )
        galleryTopUi = galleryTopUi.copy(
            dated = dateRange != null,
            dateLabel = dateLabel,
            area = areaKeys != null,
            areaLabel = areaLabel,
            film = films.isNotEmpty(),
            filmLabel = if (films.size == 1) Films.label(this, films.first()) else getString(R.string.n_films, films.size),
            chips = Filter.values().map { f -> ChipUi("${names[f]} ${formatCount(counts[f] ?: 0)}", f == filter) },
        )
    }

    private fun updateBottom() {
        val st = TransferState
        val selObjs = shownList.filter { it.handle in selected }
        val later = inCache
        val bytes = selObjs.sumOf { if (it.sizeOverflow()) 0L else it.size }
        // chosen files that the current filters hide are still downloaded: say so
        val shown = files.mapTo(HashSet()) { it.handle }
        val hidden = selObjs.count { it.handle !in shown }
        val hiddenNote = if (hidden > 0) " · " + getString(R.string.hidden_by_filters, hidden) else ""
        galleryBottomUi = galleryBottomUi.copy(
            selTitle = if (selObjs.isEmpty()) getString(R.string.sel_none)
            else resources.getQuantityString(R.plurals.sel_n, selObjs.size, selObjs.size),
            selInfo = when {
                selObjs.isEmpty() -> getString(if (later) R.string.sel_tap_later else R.string.sel_hold)
                later && st.busy -> getString(R.string.sel_when_ready)
                later -> getString(R.string.sel_when_connected)
                !st.sizesKnown -> getString(R.string.sel_sizes_later) + hiddenNote
                else -> formatSize(bytes) + hiddenNote
            },
            canDownload = selObjs.isNotEmpty() && (st.connected || later), // during a batch the new one waits in the queue
            later = later,
            // files not on the phone yet need the camera to fetch them first
            canUpload = selObjs.isNotEmpty() && (st.connected || selObjs.all { key(it) in done }),
            snack = if (selObjs.isEmpty()) uploadSnack else "",
            hint = if (selObjs.isNotEmpty() && selObjs.size < 3 && !dragUsed) getString(R.string.drag_hint) else "",
            snackSub = getString(if (ImmichSync.settings(this).wifiOnly) R.string.snack_wifi else R.string.snack_any),
        )
        galleryTopUi = galleryTopUi.copy(allSelected = files.isNotEmpty() && files.all { it.handle in selected })
    }

    // ------------------------------------------------------------------ download screen

    private fun updateDownload() {
        val st = TransferState
        val (doneBytes, total) = st.batchEstimate() // sizes of queued files estimated in gallery mode
        val now = SystemClock.elapsedRealtime()
        if (lastT == 0L || doneBytes < lastBytes) {
            lastT = now
            lastBytes = doneBytes
            speed = 0.0
        } else if (now - lastT >= 700) {
            val inst = (doneBytes - lastBytes) * 1000.0 / (now - lastT)
            speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
            lastT = now
            lastBytes = doneBytes
        }
        val eta = if (speed > 1.0 && total > doneBytes) getString(R.string.about, formatDuration(((total - doneBytes) / speed).toLong())) else "…"
        val finished = (st.batchIndex - 1).coerceAtLeast(0)
        val queued = (st.batchCount - st.batchIndex).coerceAtLeast(0)
        downloadUi = downloadUi.copy(
            batch = st.batch,
            batchIndex = st.batchIndex,
            title = getString(R.string.n_of_m, st.batchIndex, st.batchCount),
            next = ImmichSync.settings(this).let { im ->
                if (!im.configured || im.mode != ImmichSync.MODE_AUTO) ""
                else getString(if (im.wifiOnly) R.string.next_immich_wifi else R.string.next_immich_any)
            },
            bytes = getString(if (st.sizesKnown) R.string.x_of_y else R.string.x_of_about_y, formatSize(doneBytes), formatSize(total)),
            progress = if (total > 0) (doneBytes * 1000 / total).toInt().coerceIn(0, 1000) else downloadUi.progress,
            speed = if (speed > 1.0) formatSize(speed.toLong()) + "/s" else "…",
            eta = eta,
            retryNote = st.retryNote,
            counts = getString(R.string.dl_counts, finished, queued),
            currentDone = st.progressDone,
            currentTotal = st.progressTotal,
        )
    }

    // ------------------------------------------------------------------ preview

    /** The file of the open preview. */
    private var previewInfo: ObjectInfo? = null

    private fun showPreview(info: ObjectInfo) {
        previewInfo = info
        previewUi = PreviewUi(info.filename, formatDate(info.captureDate), Thumbs.get(info), detailsUi(info))
        previewOpen = true
    }

    /** What the details sheet shows for a file. */
    private fun detailsUi(info: ObjectInfo): DetailsUi {
        val film = TransferState.meta[MetaStore.key(info.filename, info.captureDate)]?.film
        return DetailsUi(
            title = info.filename.substringBeforeLast('.'),
            format = info.filename.substringAfterLast('.', "").uppercase(Locale.ROOT) + (if (film != null) " · " + Films.label(this, film) else ""),
            date = formatDate(info.captureDate).ifEmpty { "—" },
            dims = if (info.width > 0 && info.height > 0) "${info.width} × ${info.height}" else "—",
            size = when {
                !TransferState.sizesKnown -> "—"
                info.sizeOverflow() -> "≥ 4 GB"
                else -> formatSize(info.size)
            },
            done = getString(if (key(info) in done) R.string.already_downloaded else R.string.immich_not_yet),
            canDownload = TransferState.connected,
            selected = info.handle in selected,
            megapixels = if (info.width > 0 && info.height > 0) "${(info.width * info.height + 500_000) / 1_000_000} MP" else "",
            immich = immichMark(info),
            canUpload = TransferState.connected || key(info) in done,
            kind = info.filename.substringAfterLast('.', "").uppercase(Locale.ROOT),
            film = if (film != null) Films.label(this, film) else "",
            filmIcon = if (film != null) Films.icon(film) ?: R.drawable.ic_film else null,
            place = placeNames[MetaStore.key(info.filename, info.captureDate)].orEmpty(),
            onPhone = key(info) in done,
        )
    }

    // ------------------------------------------------------------------ viewer (tap on a photo)

    private var viewer: Viewer? = null

    /** Decodes for the side pages, one at a time: the neighbours never compete with the photo on screen. */
    private val sideDecoder = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Full-screen viewer on the tapped file, swiping through the files of the gallery as shown. Photos
     * open from a quick preview (the camera's size-S copy, or the JPEG inside a RAF), kept on disk so they open
     * at once next time, also offline; "Originale" fetches the full photo. Videos, and files without a preview,
     * are fetched whole as before.
     */
    private fun openViewer(info: ObjectInfo) {
        var items = files
        var index = items.indexOfFirst { it.handle == info.handle }
        if (index < 0) {
            items = listOf(info)
            index = 0
        }
        if (!TransferState.connected && Previews.get(info) == null) {
            showPreview(info) // nothing to fetch from: show what we have
            return
        }
        viewer?.close()
        viewer = Viewer(items, index).also { it.open() }
    }

    /**
     * Three pages that slide together (ViewerScreen): the open file in the middle, zoomable or playing, its
     * neighbours on the sides already drawn, so a swipe shows the next picture coming in. The strip of thumbnails
     * at the bottom follows and opens a file with a tap; a tap on the picture hides or shows the bars.
     */
    private inner class Viewer(val items: List<ObjectInfo>, var index: Int) {
        /** The viewer is a layer of AppRoot (viewerHost) while this is true. */
        var showing = false
        /** Everything the viewer screen draws (Compose: ViewerScreen); the code below keeps it up to date. */
        val ui = ViewerState()
        var topUi: ViewerTopUi
            get() = ui.top
            set(v) { ui.top = v }
        var loadUi: ViewerLoadUi
            get() = ui.load
            set(v) { ui.load = v }
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val info: ObjectInfo get() = items[index]
        /** The last video that failed to play ("Prova lo stesso" plays it again with FFmpeg first). */
        var lastVideo: java.io.File? = null

        fun retryVideo() {
            val f = lastVideo ?: return
            loadUi = loadUi.copy(videoWhat = "", videoWhy = "", status = getString(R.string.vw_retry_sw))
            playVideo(f, ffmpegFirst = true)
        }
        /** What the current file shows: 0 nothing, 1 its thumbnail, 2 the quick preview, 3 the original. */
        var level = 0
        /** The original of the current file was asked of the service (ACTION_VIEW). */
        var waitingOriginal = false
        var decoding = false
        /** Bumped when the file changes: a decode that finishes later for the previous one is dropped. */
        var token = 0
        /** Bars shown (a tap on the picture hides them). */
        var chrome: Boolean
            get() = ui.chrome
            set(v) { ui.chrome = v }
        /** Quick previews decoded for the pages, upright, at most 2048 px: neighbours slide in already sharp. */
        val pictures = object : android.util.LruCache<Long, android.graphics.Bitmap>(48 * 1024 * 1024) {
            override fun sizeOf(key: Long, value: android.graphics.Bitmap) = value.byteCount
        }
        val sideLoading = HashSet<Long>()
        /** Which file each side page shows (index into items). */
        var prevFor = -1
        var nextFor = -1
        /** Direction of the last move (+1 forward): the one neighbour prepared ahead is on that side. */
        var lastDir = 1
        private val askLater = Runnable { askPreviewsNow() }

        fun open() {
            ui.items = items
            ui.index = index
            ui.origin = tileBounds(info.handle) // the picture grows out of this grid cell
            val actions = ViewerActions(
                onBack = { requestClose() },
                onHd = { fetchOriginal() },
                onSelect = {
                    window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    toggle(info)
                    paintSelect()
                },
                onDetailsSelect = {
                    toggle(info)
                    paintSelect()
                    info.handle in selected
                },
                onDetailsUpload = { toggleUpload(info).also { paintSelect() } },
                onDownload = {
                    downloadsHidden = false
                    startServiceForeground(TransferService.ACTION_DOWNLOAD) { it.putExtra(TransferService.EXTRA_HANDLES, longArrayOf(info.handle)) }
                },
                onPlay = {
                    ui.playVisible = false
                    fetchOriginal()
                },
                onTap = { toggleChrome() },
                onSettled = { settled(it) },
                onJump = { jump(it) },
                // the closing animation is over
                onDismiss = { close() },
                originFor = { i -> items.getOrNull(i)?.let { tileBounds(it.handle) } },
                ensureVisible = { i -> items.getOrNull(i)?.let { scrollGridTo(it) } },
                onVideoRetry = { retryVideo() },
                onVideoDownload = {
                    loadUi = loadUi.copy(videoCanDownload = false)
                    downloadsHidden = false
                    startServiceForeground(TransferService.ACTION_DOWNLOAD) { it.putExtra(TransferService.EXTRA_HANDLES, longArrayOf(info.handle)) }
                },
            )
            showing = true
            viewerHost = ui to actions
            showItem(null)
            bindSide(-1)
            bindSide(1)
        }

        /** Back button or arrow: the screen closes with its animation and calls onDismiss. */
        fun requestClose() {
            ui.closeToken++
        }

        /** The pager settled on a neighbour: the pictures move one page over, nothing changes on screen. */
        private fun settled(dir: Int) {
            val shown = ui.cur
            val incoming = if (dir > 0) ui.next else ui.prev
            leave()
            lastDir = dir
            index += dir
            ui.index = index
            showItem(incoming)
            if (shown != null) {
                setSide(-dir, shown) // the file just left, as it was on screen
                if (dir > 0) prevFor = index - 1 else nextFor = index + 1
            } else {
                bindSide(-dir)
            }
            bindSide(dir)
        }

        /** A tap on the strip. */
        private fun jump(i: Int) {
            if (i == index || i !in items.indices) return
            leave()
            lastDir = if (i > index) 1 else -1
            index = i
            ui.index = index
            showItem(null)
            bindSide(-1)
            bindSide(1)
        }

        private fun leave() {
            token++
            decoding = false
            player?.release()
            player = null
            ui.player = null
            if (waitingOriginal) sendAction(TransferService.ACTION_VIEW_CANCEL)
            waitingOriginal = false
            level = 0
            ui.playVisible = false
            ui.controllerShown = false // a video's controls may have hidden the strip
        }

        /** Side page {@code dir} (-1 previous, +1 next): the best picture there is now, a sharper one when ready. */
        private fun bindSide(dir: Int) {
            val idx = index + dir
            if (dir < 0) prevFor = idx else nextFor = idx
            val item = items.getOrNull(idx)
            if (item == null) {
                setSide(dir, null)
                return
            }
            setSide(dir, pictures.get(item.handle) ?: uprightThumb(item))
            loadSharp(item, dir, idx)
        }

        /** The picture of side page [dir] (-1 previous, +1 next). */
        private fun setSide(dir: Int, b: android.graphics.Bitmap?) {
            if (dir < 0) ui.prev = b else ui.next = b
        }

        /** The quick preview of a side file, decoded in the background, if it is on disk and not decoded yet. */
        private fun loadSharp(item: ObjectInfo, dir: Int, idx: Int) {
            if (!Previews.applies(item) || pictures.get(item.handle) != null || item.handle in sideLoading) return
            val f = Previews.get(item) ?: return
            sideLoading.add(item.handle)
            sideDecoder.execute {
                val bmp = decodePicture(f, 2048, item)
                runOnUiThread {
                    sideLoading.remove(item.handle)
                    if (bmp == null || !showing) return@runOnUiThread
                    pictures.put(item.handle, bmp)
                    if ((if (dir < 0) prevFor else nextFor) == idx) setSide(dir, bmp)
                }
            }
        }

        /**
         * The thumbnail, if it can be shown full size: a photo's thumbnail carries no EXIF, so until its
         * orientation is known (preview, background scan) it may be sideways and would turn when the preview
         * lands. Then the page waits for the preview instead; the strip still shows it small.
         */
        private fun uprightThumb(i: ObjectInfo): android.graphics.Bitmap? =
            if (!Previews.applies(i) || Orient.known(i) > 0) Thumbs.get(i) else null

        /** The ring / check of the top bar, for the file on screen. */
        fun paintSelect() {
            val on = info.handle in selected
            topUi = topUi.copy(selected = on)
            ui.panel = detailsUi(info) // the details sheet (drag up or ⓘ)
        }

        private fun showItem(start: android.graphics.Bitmap?) {
            paintSelect()
            val i = info
            DebugLog.detail("visualizzatore: ${i.filename} (${index + 1} di ${items.size})")
            topUi = topUi.copy(name = i.filename)
            topUi = topUi.copy(note = getString(R.string.n_of_m, index + 1, items.size) + " · " + formatDate(i.captureDate))
            ui.videoVisible = false
            ui.imageVisible = true
            topUi = topUi.copy(hd = false)
            ui.loadingVisible = false
            val sharp = if (Previews.applies(i)) pictures.get(i.handle) else null
            val pic = sharp ?: start ?: uprightThumb(i)
            ui.cur = pic
            level = when {
                sharp != null -> 2
                pic != null -> 1
                else -> 0
            }
            if (sharp != null) {
                afterQuick()
                askPreviews()
                return
            }
            val connected = TransferState.connected
            if (kindOf(i) == Kind.VIDEO) {
                // fetched only on a tap: 3 Nov, three videos opened and dropped one after the other made the
                // X-M5 stop answering and turn its Wi-Fi off
                if (connected) ui.playVisible = true
                else say(getString(R.string.vw_connect_video), progress = false)
                askPreviews()
                return
            }
            val cached = if (Previews.applies(i)) Previews.get(i) else null
            when {
                cached != null -> showQuick(cached)
                Previews.applies(i) && connected && Previews.key(i) !in TransferState.previewMissing ->
                    say(getString(R.string.vw_preparing), progress = false)
                connected -> fetchOriginal()
                else -> say(getString(R.string.vw_connect_file), progress = false)
            }
            askPreviews()
        }

        /**
         * Asks the camera for the preview of the file on screen once the user has stopped on it for a moment:
         * flicking through ten photos asks for the last one only, not for ten. 3 Nov: six previews back to back
         * made the X-M5 drop its Wi-Fi (the service also paces them).
         */
        private fun askPreviews() {
            handler.removeCallbacks(askLater)
            handler.postDelayed(askLater, 350)
        }

        /** This file, then the next one in the direction of travel; one per shot (JPG and RAF share it). */
        private fun askPreviewsNow() {
            if (!TransferState.connected || !showing) return
            val wanted = listOf(index, index + lastDir)
                .mapNotNull { items.getOrNull(it) }
                .filter { Previews.applies(it) && Previews.get(it) == null && Previews.key(it) !in TransferState.previewMissing }
                .distinctBy { Previews.key(it) }
            if (wanted.isEmpty()) return
            DebugLog.detail("visualizzatore: chiedo le anteprime di " + wanted.joinToString { it.filename })
            startService(Intent(this@MainActivity, TransferService::class.java).setAction(TransferService.ACTION_PREVIEW)
                .putExtra(TransferService.EXTRA_HANDLES, wanted.map { it.handle }.toLongArray()))
        }

        private fun say(text: String, progress: Boolean) {
            ui.loadingVisible = true
            loadUi = loadUi.copy(status = text)
            loadUi = loadUi.copy(progressVisible = progress)
            if (progress) loadUi = loadUi.copy(progress = 0)
            loadUi = loadUi.copy(bytes = "")
        }

        /** The full file from the camera (the service keeps one in its cache). */
        fun fetchOriginal() {
            if (!TransferState.connected || waitingOriginal) return
            DebugLog.detail("visualizzatore: chiedo l'originale di ${info.filename}")
            waitingOriginal = true
            topUi = topUi.copy(hd = false)
            say(getString(if (kindOf(info) == Kind.VIDEO) R.string.vw_fetch_video else R.string.vw_fetch_photo), progress = true)
            startService(Intent(this@MainActivity, TransferService::class.java).setAction(TransferService.ACTION_VIEW)
                .putExtra(TransferService.EXTRA_HANDLE, info.handle))
        }

        /** New thumbnails arrived (strip, pages still without a picture). */
        fun onThumbs() {
            ui.stripTick++
            if (level == 0 && ui.imageVisible) uprightThumb(info)?.let {
                ui.cur = it
                level = 1
            }
            if (ui.prev == null) items.getOrNull(index - 1)?.let { uprightThumb(it) }?.let { ui.prev = it }
            if (ui.next == null) items.getOrNull(index + 1)?.let { uprightThumb(it) }?.let { ui.next = it }
        }

        fun update() {
            val st = TransferState
            val i = info
            detailsUi(i).let { if (it != ui.panel) ui.panel = it } // downloaded, connected, queued for Immich...
            if (level < 2 && !waitingOriginal && !decoding && Previews.applies(i)) {
                val f = Previews.get(i)
                if (f != null) {
                    showQuick(f)
                } else if (Previews.key(i) in st.previewMissing && st.connected) {
                    fetchOriginal() // the camera has no quick preview for it
                }
            }
            items.getOrNull(index - 1)?.let { loadSharp(it, -1, index - 1) } // previews of the neighbours landed
            items.getOrNull(index + 1)?.let { loadSharp(it, 1, index + 1) }
            if (!waitingOriginal || st.viewHandle != i.handle) return
            when {
                st.viewError.isNotEmpty() -> {
                    waitingOriginal = false
                    loadUi = loadUi.copy(status = getString(R.string.vw_original_failed, st.viewError))
                    loadUi = loadUi.copy(progressVisible = false)
                    loadUi = loadUi.copy(bytes = "")
                }
                st.viewFile != null -> {
                    waitingOriginal = false
                    showOriginal(java.io.File(st.viewFile!!))
                }
                else -> {
                    val t = st.viewTotal
                    if (t > 0) {
                        loadUi = loadUi.copy(progress = (st.viewDone * 1000 / t).toInt())
                        loadUi = loadUi.copy(bytes = getString(R.string.x_of_y, formatSize(st.viewDone), formatSize(t)))
                    }
                }
            }
        }

        private fun afterQuick() {
            if (!waitingOriginal) ui.loadingVisible = false
            if (Previews.isRaf(info)) {
                topUi = topUi.copy(note = getString(R.string.n_of_m, index + 1, items.size) + " · " + getString(R.string.vw_raf_preview))
            } else {
                // the RAF's JPEG is all a phone shows of a RAF anyway; a photo has more in its original
                topUi = topUi.copy(hd = TransferState.connected && !waitingOriginal)
            }
        }

        private fun showQuick(f: java.io.File) {
            decoding = true
            decodeCenter(f, 2048) { bmp ->
                decoding = false
                if (bmp == null) {
                    level = 2 // tried: update() must not decode it again on every refresh
                    if (TransferState.connected) fetchOriginal() else say(getString(R.string.vw_preview_unreadable), progress = false)
                    return@decodeCenter
                }
                pictures.put(info.handle, bmp)
                ui.cur = bmp
                level = 2
                afterQuick()
            }
        }

        private fun showOriginal(f: java.io.File) {
            if (kindOf(info) == Kind.VIDEO) {
                ui.loadingVisible = false
                ui.imageVisible = false
                ui.videoVisible = true
                playVideo(f)
                return
            }
            loadUi = loadUi.copy(status = getString(R.string.vw_opening))
            loadUi = loadUi.copy(progressVisible = false)
            loadUi = loadUi.copy(bytes = "")
            decodeCenter(f, 4096) { bmp ->
                if (bmp == null) {
                    loadUi = loadUi.copy(status = getString(R.string.vw_cannot_show))
                    return@decodeCenter
                }
                ui.cur = bmp
                level = 3
                ui.loadingVisible = false
                topUi = topUi.copy(hd = false)
                if (kindOf(info) == Kind.RAW) topUi = topUi.copy(note = getString(R.string.n_of_m, index + 1, items.size) + " · " + getString(R.string.vw_raf_jpeg))
            }
        }

        /** Decodes off the main thread; {@code done} runs only if the viewer still shows the same file. */
        private fun decodeCenter(f: java.io.File, maxSide: Int, done: (android.graphics.Bitmap?) -> Unit) {
            val t = token
            val i = info
            Thread {
                val bmp = decodePicture(f, maxSide, i)
                runOnUiThread {
                    if (showing && t == token) done(bmp)
                }
            }.start()
        }

        private fun toggleChrome() {
            chrome = !chrome
        }

        /**
         * ExoPlayer with the phone's decoders first and FFmpeg (NextLib) as fallback: X-M5 MOVs in H.265 4:2:2
         * 10-bit are refused by most phone hardware decoders. The log says which decoder was really used.
         */
        @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
        private fun playVideo(f: java.io.File, ffmpegFirst: Boolean = false, startMs: Long = 0) {
            // ON: the phone's decoders first. PREFER: FFmpeg first. setEnableDecoderFallback only moves to another
            // MediaCodec decoder, never to FFmpeg: on 3 Nov c2.mtk.hevc.decoder took an hvc1.4 (4:2:2 10-bit) MOV
            // and then failed, so a decoding failure is retried here with FFmpeg first.
            val mode = if (ffmpegFirst) androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            val factory = io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(this@MainActivity)
                .setExtensionRendererMode(mode)
                .setEnableDecoderFallback(true)
            val p = androidx.media3.exoplayer.ExoPlayer.Builder(this@MainActivity, factory).build()
            player = p
            ui.player = p
            p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onVideoInputFormatChanged(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    format: androidx.media3.common.Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) {
                    DebugLog.add("video ${info.filename}: ${format.sampleMimeType} codec ${format.codecs} " +
                        "${format.width}×${format.height} ${format.frameRate} fps")
                }

                override fun onVideoDecoderInitialized(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    DebugLog.add("  decoder video: $decoderName")
                }

                override fun onAudioDecoderInitialized(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    DebugLog.add("  decoder audio: $decoderName")
                }

                override fun onDroppedVideoFrames(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    DebugLog.add("  $droppedFrames fotogrammi saltati in $elapsedMs ms (decodifica troppo lenta)")
                }
            })
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    DebugLog.add("  riproduzione fallita: ${error.errorCodeName}: ${error.cause ?: error.message}")
                    val decoderError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                    if (!ffmpegFirst && decoderError && player === p) {
                        DebugLog.add("  riprovo con il decoder software (FFmpeg) al posto di quello del telefono")
                        val at = p.currentPosition
                        p.release()
                        player = null
                        playVideo(f, ffmpegFirst = true, startMs = at)
                        return
                    }
                    ui.videoVisible = false
                    ui.loadingVisible = true
                    lastVideo = f
                    // what the file is, from the track Media3 read (nothing guessed when it read none)
                    val fmt = p.videoFormat
                    val codec = when {
                        fmt?.sampleMimeType?.contains("hevc") == true -> "H.265"
                        fmt?.sampleMimeType?.contains("avc") == true -> "H.264"
                        else -> fmt?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
                    }
                    val bits = fmt?.colorInfo?.lumaBitdepth?.takeIf { it > 8 }?.let { getString(R.string.vw_bits, it) } ?: ""
                    val size = if (fmt != null && fmt.width > 0 && fmt.height > 0) ", ${fmt.width} × ${fmt.height}" else ""
                    loadUi = loadUi.copy(
                        status = getString(R.string.vw_cannot_play, error.errorCodeName),
                        progressVisible = false,
                        bytes = "",
                        videoWhat = if (codec != null) getString(R.string.vw_what, codec + bits + size) else getString(R.string.vw_player_error, error.errorCodeName),
                        videoWhy = (if (ffmpegFirst) getString(R.string.vw_why_both) else getString(R.string.vw_why_player, error.errorCodeName)) +
                            " " + getString(R.string.vw_why_elsewhere),
                        videoCanDownload = key(info) !in done && TransferState.connected,
                    )
                }
            })
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(f)), startMs)
            p.prepare()
            p.playWhenReady = true
        }

        fun close() {
            if (viewer === this) viewer = null
            handler.removeCallbacks(askLater)
            token++
            player?.release()
            player = null
            showing = false
            if (viewerHost?.first === ui) viewerHost = null
            if (waitingOriginal) sendAction(TransferService.ACTION_VIEW_CANCEL)
            waitingOriginal = false
        }
    }

    /**
     * Upright bitmap of a picture file at most {@code maxSide} px on its long side: a JPEG (preview, original), the
     * JPEG a RAF embeds (offset and length at bytes 84 and 88 of its header), or anything else ImageDecoder reads
     * (HEIF). JPEGs are turned by Orient, from their own EXIF or the one kept for the file. Null if unreadable.
     */
    private fun decodePicture(f: java.io.File, maxSide: Int, i: ObjectInfo): android.graphics.Bitmap? = try {
        val jpeg = if (f.name.endsWith(".raf", ignoreCase = true)) rafJpeg(f) else {
            val head = ByteArray(2)
            val n = f.inputStream().use { it.read(head) }
            val jpg = n == 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xFF) == 0xD8
            if (jpg) f.readBytes() else null
        }
        if (jpeg != null) {
            Orient.decode(jpeg, maxSide, Orient.known(i))
        } else {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(f)) { decoder, imageInfo, _ ->
                val w = imageInfo.size.width
                val h = imageInfo.size.height
                val long = maxOf(w, h)
                if (long > maxSide) {
                    val k = maxSide.toDouble() / long
                    decoder.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
                }
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** The JPEG inside a RAF file, or null. */
    private fun rafJpeg(f: java.io.File): ByteArray? = java.io.RandomAccessFile(f, "r").use { r ->
        val head = ByteArray(92)
        r.readFully(head)
        if (String(head, 0, 15, Charsets.US_ASCII) != "FUJIFILMCCD-RAW") return null
        fun u32(o: Int) = ((head[o].toLong() and 0xFF) shl 24) or ((head[o + 1].toLong() and 0xFF) shl 16) or
            ((head[o + 2].toLong() and 0xFF) shl 8) or (head[o + 3].toLong() and 0xFF)
        val off = u32(84)
        val len = u32(88)
        if (len <= 0 || len > 64L * 1024 * 1024 || off + len > r.length()) return null
        val jpg = ByteArray(len.toInt())
        r.seek(off)
        r.readFully(jpg)
        jpg
    }

    // ------------------------------------------------------------------ helpers

    private enum class Kind(val label: Int) { PHOTO(R.string.kind_photo), RAW(R.string.kind_raw), VIDEO(R.string.kind_video), OTHER(R.string.kind_other) }

    private fun kindOf(o: ObjectInfo): Kind = when (o.filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "heif", "heic", "hif" -> Kind.PHOTO
        "raf", "dng", "nef", "cr2", "cr3", "arw" -> Kind.RAW
        "mov", "mp4" -> Kind.VIDEO
        else -> Kind.OTHER
    }

    private fun photosLabel(n: Int) = resources.getQuantityString(R.plurals.n_files, n, formatCount(n))

    private fun formatCount(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

    private fun formatSize(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(Locale.getDefault(), b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(Locale.getDefault(), b / 1048576.0)
        b >= 1L shl 10 -> "%d KB".format(b shr 10)
        else -> "$b B"
    }

    private fun formatDuration(sec: Long): String = when {
        sec >= 3600 -> "%d h %02d min".format(sec / 3600, (sec % 3600) / 60)
        sec >= 60 -> "%d min %02d s".format(sec / 60, sec % 60)
        else -> "$sec s"
    }

    private fun formatDate(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val d: Date = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT).parse(raw.take(15)) ?: return raw
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(d)
        } catch (e: Exception) {
            raw
        }
    }

    /** "Oggi", "Ieri" or e.g. "Domenica 18 ottobre" (with the year when it is not this year). */
    private fun dayLabel(day: String): String {
        if (day.length < 8) return getString(R.string.date_unknown)
        return try {
            val d = SimpleDateFormat("yyyyMMdd", Locale.ROOT).parse(day) ?: return day
            val c = Calendar.getInstance().apply { time = d }
            val today = Calendar.getInstance()
            fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            when {
                sameDay(c, today) -> getString(R.string.today)
                sameDay(c, yesterday) -> getString(R.string.yesterday)
                else -> {
                    val skeleton = if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "EEEEdMMMM" else "EEEEdMMMMyyyy"
                    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
                    SimpleDateFormat(pattern, Locale.getDefault()).format(d).replaceFirstChar { it.titlecase(Locale.getDefault()) }
                }
            }
        } catch (e: Exception) {
            day
        }
    }

    private fun pairing(): FujiBle.Pairing? =
        FujiBle.Pairing.parse(getSharedPreferences("fuji", MODE_PRIVATE).getString(TransferService.PREF_PAIRING, null))

    private fun startBle(action: String) {
        if (!hasBlePermissions()) {
            requestRuntimePermissions()
            TransferState.statusIsError = true
            TransferState.status = getString(R.string.need_ble_perm)
            TransferState.errorDetail = TransferState.status
            TransferState.publish()
            return
        }
        startServiceForeground(action) { it }
    }

    private fun hasBlePermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun confirmForget() {
        appDialog = AppDialog.Confirm(
            title = getString(R.string.dlg_forget_title),
            message = getString(R.string.dlg_forget_body),
            confirm = getString(R.string.forget),
            dismiss = getString(R.string.cancel),
            onConfirm = {
                getSharedPreferences("fuji", MODE_PRIVATE).edit().remove(TransferService.PREF_PAIRING).apply()
                onChanged()
            },
        )
    }

    /** [text] = the lines the log screen shows with its current chip. */
    private fun copyLog(text: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Fuji Transfer log", text))
        android.widget.Toast.makeText(this, getString(R.string.log_copied), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearLog() {
        appDialog = AppDialog.Confirm(
            title = getString(R.string.dlg_clear_log_title),
            message = getString(R.string.dlg_clear_log_body),
            confirm = getString(R.string.clear),
            dismiss = getString(R.string.cancel),
            onConfirm = { DebugLog.clear() },
        )
    }

    // ------------------------------------------------------------------ end of a batch ("Download finito")

    private val doneActions by lazy {
        DoneActions(
            onBack = { closeDone() },
            onNo = { doneUi = doneUi.copy(immich = DoneImmich.Declined) },
            onUpload = { list ->
                ImmichSync.enqueue(this, list.map { key(it) })
                ImmichSync.schedule(this)
                readImmichMarks()
                doneUi = doneUi.copy(immich = DoneImmich.Queued, queuedCount = list.size)
            },
            onSeeUploads = { closeDone(); uploadsOpen = true },
            onReconnect = { resumeAfterDrop() },
            onKeepAwake = { setKeepAwake(it) },
            size = { info -> if (kindOf(info) == Kind.RAW) formatSize(info.size) + " · RAW" else formatSize(info.size) },
        )
    }

    private fun closeDone() {
        doneOpen = false
        onChanged()
    }

    /**
     * A batch ended. Dropped because the camera switched its Wi-Fi off: the drop card, always. Otherwise, if its
     * screen was on view: "Download finito", with the Immich question in "Chiedi a fine download". A batch that
     * ran behind the gallery ("Continua in background", "Immich") only gets the toast; a cancelled one nothing.
     */
    private fun onBatchEnded(b: BatchResult, wasVisible: Boolean) {
        if (b.saved.isNotEmpty()) {
            // "Ultima sessione" on the connect screen
            val prefs = getSharedPreferences("fuji", MODE_PRIVATE)
            prefs.edit().putLong("lastSessionAt", System.currentTimeMillis())
                .putInt("lastSessionSaved", prefs.getInt("lastSessionSaved", 0) + b.saved.size).apply()
        }
        if (b.cancelled) return
        val sec = (b.millis / 1000).coerceAtLeast(1)
        val where = SaveFolder.describeFor(this, b.saved.map { it.filename })
        val s = ImmichSync.settings(this)
        val base = DoneUi(
            id = b.id, saved = b.saved, batchSize = b.saved.size + b.left.size, failed = b.failed,
            summary = getString(R.string.done_summary, formatSize(b.bytes), formatDuration(sec), where),
            keepAwake = keepAwakeState,
        )
        if (b.cameraWifiOff) {
            doneUi = base.copy(
                dropped = true, droppedScreenOff = b.screenOff, partialName = b.partialName,
                partialPercent = b.partialPercent, left = b.left.size,
            )
            pendingResume = b.left
            doneOpen = true
            return
        }
        if (!wasVisible || b.saved.isEmpty()) {
            if (b.saved.isNotEmpty() && b.failed == 0) {
                android.widget.Toast.makeText(this, getString(R.string.st_done, b.saved.size) + "\n" + getString(R.string.notif_where, SaveFolder.describeFor(this, b.saved.map { it.filename })),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        doneUi = base.copy(
            immich = when {
                !s.configured -> DoneImmich.None
                b.immichQueued > 0 -> DoneImmich.Queued
                s.mode == ImmichSync.MODE_OFFER -> DoneImmich.Ask
                else -> DoneImmich.None
            },
            queuedCount = b.immichQueued,
        )
        doneOpen = true
    }

    /** The files the drop card would fetch again. */
    private var pendingResume: List<ObjectInfo> = emptyList()

    /**
     * "Riconnetti e riprendi": the link to the camera is dead (its Wi-Fi is off), so it is closed, a new
     * connection is made, and once the camera's list is read the files left are downloaded again; the one cut
     * halfway goes on from where it stopped.
     */
    private fun resumeAfterDrop() {
        resumeKeys = pendingResume.map { key(it) }
        resumeStep = 0
        resumePosted = false
        doneOpen = false
        DebugLog.add("riconnessione per riprendere ${pendingResume.size} file")
        if (TransferState.connected || TransferState.busy) sendAction(TransferService.ACTION_DISCONNECT)
        onChanged()
    }

    private fun resumeStepCheck() {
        val keys = resumeKeys ?: return
        val st = TransferState
        when (resumeStep) {
            0 -> if (!st.connected && !st.busy && !resumePosted) {
                // the teardown ends with the service stopping itself: give it a moment before starting again
                resumePosted = true
                dimHandler.postDelayed({
                    resumeStep = 1
                    startBle(TransferService.ACTION_CONNECT_BLE)
                }, 1500)
            }
            1 -> when {
                st.connected -> resumeStep = 2
                !st.busy && st.statusIsError -> {
                    resumeKeys = null // the error screen says why
                    DebugLog.add("ripresa annullata: connessione non riuscita")
                }
            }
            2 -> if (st.connected && !st.busy && !st.listing) {
                resumeKeys = null
                val handles = st.objects.filter { key(it) in keys }.map { it.handle }
                if (handles.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.toast_files_gone), android.widget.Toast.LENGTH_LONG).show()
                } else {
                    downloadsHidden = false
                    startServiceForeground(TransferService.ACTION_DOWNLOAD) { it.putExtra(TransferService.EXTRA_HANDLES, handles.toLongArray()) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ "Tieni lo schermo acceso"

    private var keepAwakeState by mutableStateOf(true)
    private var keepAwakeRead = false

    private fun keepAwakePref(): Boolean {
        if (!keepAwakeRead) {
            keepAwakeRead = true
            keepAwakeState = getSharedPreferences("fuji", MODE_PRIVATE).getBoolean("keepAwake", true)
        }
        return keepAwakeState
    }

    private fun setKeepAwake(on: Boolean) {
        keepAwakeRead = true
        keepAwakeState = on
        getSharedPreferences("fuji", MODE_PRIVATE).edit().putBoolean("keepAwake", on).apply()
        doneUi = doneUi.copy(keepAwake = on)
        DebugLog.add(if (on) "schermo acceso durante i download: sì" else "schermo acceso durante i download: no")
        onChanged()
    }

    /** While a batch runs (and the option is on) the screen stays on; 30 s without touches dims it. */
    private fun applyAwake(downloading: Boolean) {
        val want = downloading && keepAwakePref()
        if (want) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (want && !awakeArmed) {
            awakeArmed = true
            dimHandler.removeCallbacks(dimRunnable)
            dimHandler.postDelayed(dimRunnable, 30_000)
        } else if (!want && awakeArmed) {
            awakeArmed = false
            dimHandler.removeCallbacks(dimRunnable)
            applyDim(false)
        }
    }

    private fun applyDim(on: Boolean) {
        if (dimmed == on) return
        dimmed = on
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun wake() {
        applyDim(false)
        if (awakeArmed) {
            dimHandler.removeCallbacks(dimRunnable)
            dimHandler.postDelayed(dimRunnable, 30_000)
        }
    }

    /** Any touch puts the dimming off again; while dimmed, the first tap only brings the light back. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (dimmed) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) wake()
            return true
        }
        if (awakeArmed && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            dimHandler.removeCallbacks(dimRunnable)
            dimHandler.postDelayed(dimRunnable, 30_000)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showImmich() {
        immichOpen = true
    }

    /** The Backup card and "Vedi": the uploads list once a server is set up, its settings before. */
    private fun showUploads() {
        if (ImmichSync.settings(this).configured) uploadsOpen = true else immichOpen = true
    }

    private fun showLog() {
        logOpen = true
    }

    // ------------------------------------------------------------------ dialogs

    @Composable
    private fun AppDialogs() {
        when (val d = appDialog) {
            null -> {}
            is AppDialog.Confirm -> androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    appDialog = null
                    d.onDismiss()
                },
                title = { androidx.compose.material3.Text(d.title) },
                text = { androidx.compose.material3.Text(d.message) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        appDialog = null
                        d.onConfirm()
                    }) { androidx.compose.material3.Text(d.confirm) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        appDialog = null
                        d.onDismiss()
                    }) { androidx.compose.material3.Text(d.dismiss) }
                },
            )
        }
    }

    /**
     * The full log (DebugLog's files: this run and the ones before) as one text file, shared with the app the
     * user picks (mail, chat, Drive...). A header says what phone, app and connection it comes from.
     */
    private fun shareLog() {
        val st = TransferState
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val header = buildString {
            appendLine("Fuji Transfer $version — registro tecnico")
            appendLine("Creato: " + java.text.DateFormat.getDateTimeInstance().format(Date()))
            appendLine("Telefono: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Camera: ${st.cameraName.ifEmpty { "—" }} · ${st.connectionLabel.ifEmpty { "—" }} · " +
                (if (st.connected) "connessa, ${st.objects.size} file" else "non connessa"))
            appendLine("Stato: ${st.status}")
            append("Righe con «·»: dettaglio (comandi alla camera, rete, visualizzatore). «w+N» = secondi dall'ingresso nel Wi-Fi della camera.")
        }
        val f = DebugLog.export(this, header)
        if (f == null) {
            android.widget.Toast.makeText(this, getString(R.string.log_empty_short), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uri = LogProvider.uriFor(this, f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Fuji Transfer — ${f.name}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(f.name, uri) // the chooser's targets get the grant too
        DebugLog.detail("registro condiviso: ${f.name}, ${f.length() / 1024} kB")
        startActivity(Intent.createChooser(send, getString(R.string.share_log)))
    }

    // Old marks were "name|size|date": keep matching them by name + date.
    private fun doneSet(): Set<String> =
        (getSharedPreferences("fuji", MODE_PRIVATE).getStringSet("done", emptySet()) ?: emptySet())
            .mapTo(HashSet()) { k -> k.split('|').let { p -> if (p.size == 3) "${p[0]}|${p[2]}" else k } }

    private fun key(i: ObjectInfo) = "${i.filename}|${i.captureDate}"

    // ------------------------------------------------------------------ space on the phone

    /**
     * Before a download: if the files' real sizes are known and they do not fit in the free space (keeping 50 MB
     * for Android), the low-storage dialog opens instead and this returns true. Unknown sizes: no check.
     */
    private fun checkSpace(files: List<ObjectInfo>): Boolean {
        if (!TransferState.sizesKnown || files.any { it.sizeOverflow() }) return false
        val need = files.sumOf { it.size }
        val free = try {
            android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) {
            return false
        }
        val room = free - 50L * 1024 * 1024
        if (need <= room) return false
        var sum = 0L
        val fit = ArrayList<ObjectInfo>()
        for (f in files) {
            if (sum + f.size > room) break
            sum += f.size
            fit.add(f)
        }
        DebugLog.add("spazio insufficiente: servono ${formatSize(need)}, liberi ${formatSize(free)}")
        lowStorage = LowStorageInfo(formatSize(need), formatSize(free.coerceAtLeast(0)), fit.size, formatSize(sum), fit.map { it.handle }.toLongArray())
        return true
    }

    // ------------------------------------------------------------------ permissions

    private fun nearPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun notifPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    private fun hasNotifPermission(): Boolean =
        notifPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Android's dialog for [perms]; when it no longer shows (refused twice, or "non chiedere più"), the app's page
     * in Android's settings instead.
     */
    private fun askPermissions(perms: List<String>, askedPref: String) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        val prefs = getSharedPreferences("fuji", MODE_PRIVATE)
        if (prefs.getBoolean(askedPref, false) && missing.none { shouldShowRequestPermissionRationale(it) }) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            } catch (e: android.content.ActivityNotFoundException) {
                DebugLog.add("impostazioni dell'app non disponibili")
            }
            return
        }
        prefs.edit().putBoolean(askedPref, true).apply()
        requestPermissions(missing.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permTick++
        settingsTick++
    }

    // ------------------------------------------------------------------ settings

    private fun openSettings() {
        savedDataBytes = savedDataSize()
        settingsTick++
        settingsOpen = true
    }

    /** The listings, thumbnails and previews the app keeps, in bytes. */
    private fun savedDataSize(): Long {
        fun size(f: java.io.File): Long = if (f.isDirectory) f.listFiles()?.sumOf { size(it) } ?: 0L else f.length()
        val listings = filesDir.listFiles { f -> f.name.startsWith("listing-") }?.sumOf { it.length() } ?: 0L
        return listings + size(java.io.File(cacheDir, "thumbs")) + size(java.io.File(cacheDir, "preview"))
    }

    private fun confirmClearSaved() {
        appDialog = AppDialog.Confirm(
            title = getString(R.string.dlg_clear_saved_title),
            message = getString(R.string.dlg_clear_saved_body),
            confirm = getString(R.string.clear),
            dismiss = getString(R.string.cancel),
            onConfirm = {
                filesDir.listFiles { f -> f.name.startsWith("listing-") }?.forEach { it.delete() }
                java.io.File(cacheDir, "thumbs").listFiles()?.forEach { it.delete() }
                java.io.File(cacheDir, "preview").listFiles()?.forEach { it.delete() }
                cacheKey = null
                cache = null
                DebugLog.add("elenco e miniature salvati cancellati")
                savedDataBytes = savedDataSize()
                settingsTick++
                onChanged()
            },
        )
    }

    /** The rows of "Impostazioni"; [tick] and [awake] are read so the screen follows changes. */
    private fun settingsGroups(@Suppress("UNUSED_PARAMETER") tick: Int, awake: Boolean): List<SettingGroup> {
        val st = TransferState
        val idle = !st.connected && !st.busy
        val paired = pairing()
        val pm = getSystemService(android.os.PowerManager::class.java)
        val unlimited = pm.isIgnoringBatteryOptimizations(packageName)
        val im = ImmichSync.settings(this)
        val imSub = if (!im.configured) getString(R.string.set_im_off) else listOf(
            getString(R.string.set_im_linked, im.url.trim().substringAfter("://").substringBefore('/').substringBefore(':')),
            when (im.mode) {
                ImmichSync.MODE_AUTO -> getString(R.string.set_im_auto)
                ImmichSync.MODE_OFFER -> getString(R.string.set_im_offer)
                else -> getString(R.string.set_im_ask)
            },
            getString(if (im.wifiOnly) R.string.set_im_wifi else R.string.set_im_mobile),
        ).joinToString(" · ")
        return listOf(
            SettingGroup(getString(R.string.camera_label), listOf(
                SettingRow(
                    R.drawable.ic_camera, if (paired != null) paired.name.ifEmpty { getString(R.string.set_paired_camera) } else getString(R.string.set_no_camera),
                    getString(if (paired != null) R.string.set_paired_sub else R.string.set_no_camera_sub),
                    button = if (paired != null) getString(R.string.forget) else "", onButton = { confirmForget() }, enabled = idle || paired == null,
                ),
                SettingRow(R.drawable.ic_plus, getString(R.string.pair_new_camera), onClick = {
                    settingsOpen = false
                    startBle(TransferService.ACTION_PAIR_BLE)
                }, enabled = idle),
            )),
            SettingGroup(getString(R.string.download_title), listOf(
                SettingRow(R.drawable.ic_sun, getString(R.string.keep_awake), getString(R.string.keep_awake_sub), switch = awake, onSwitch = { setKeepAwake(it) }),
                SettingRow(R.drawable.ic_folder, getString(R.string.set_folder), if (SaveFolder.isSplit(this)) getString(R.string.folder_split_sub) else SaveFolder.describe(this),
                    onClick = { folderOpen = true }),
            )),
            SettingGroup(getString(R.string.set_battery), listOf(
                SettingRow(
                    R.drawable.ic_battery, getString(R.string.set_background),
                    getString(if (unlimited) R.string.set_unlimited else R.string.set_limited),
                    onClick = {
                        if (unlimited) {
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (e: android.content.ActivityNotFoundException) {
                                DebugLog.add("impostazioni batteria non disponibili")
                            }
                        } else {
                            batteryThen = {}
                        }
                    },
                ),
            )),
            SettingGroup(getString(R.string.tile_immich), listOf(
                SettingRow(R.drawable.ic_immich, getString(R.string.tile_immich), imSub, onClick = { showImmich() }),
            )),
            SettingGroup(getString(R.string.set_saved), listOf(
                SettingRow(
                    R.drawable.ic_photos, getString(R.string.set_saved_list),
                    getString(R.string.set_saved_list_sub, formatSize(savedDataBytes)),
                    button = getString(R.string.clear), onButton = { confirmClearSaved() }, enabled = idle,
                ),
                SettingRow(R.drawable.ic_log, getString(R.string.cd_log), onClick = { showLog() }),
            )),
        )
    }

    // ------------------------------------------------------------------ saved gallery ("Dalla cache")

    /** The listing saved at the last connection to the paired camera, newest first; null = none. */
    private fun savedListing(): List<ObjectInfo>? {
        val p = pairing() ?: return null
        val k = "ble-" + p.address
        if (k != cacheKey) { // read once per camera and connection: not on every state change
            cacheKey = k
            cache = TransferService.cachedListing(this, k)?.sortedByDescending { it.captureDate }?.takeIf { it.isNotEmpty() }
        }
        return cache
    }

    private fun openSavedGallery() {
        if (savedListing() == null) return
        selected.clear()
        shownObjects = null
        cacheBrowse = true
        onChanged()
    }

    private fun leaveCache() {
        cacheBrowse = false
        selected.clear()
        shownObjects = null
        onChanged()
    }

    private fun updateCacheHeader() {
        val st = TransferState
        val connecting = st.busy && !st.connected
        galleryTopUi = galleryTopUi.copy(
            title = pairing()?.name?.ifEmpty { null } ?: getString(R.string.camera_label),
            info = getString(R.string.from_cache) + " · " + photosLabel(cache?.size ?: 0),
            cached = true,
            banner = "",
            working = false,
            subtitle = "",
            link = if (connecting) GalleryLink(
                getString(R.string.link_connecting), getString(R.string.link_connecting_sub, st.connectStep.coerceIn(0, 3) + 1, 4),
                attemptStart, getString(R.string.cancel),
            ) else GalleryLink(getString(R.string.link_off), getString(R.string.link_off_sub), 0L, getString(R.string.connect)),
        )
    }

    /** "Oggi, 18:42", "Ieri, 18:42", "3 nov, 18:42". */
    private fun whenLabel(ms: Long): String {
        val day = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val today = java.util.Calendar.getInstance()
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(ms))
        fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay(day, today)) return getString(R.string.today_at, time)
        today.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (sameDay(day, today)) return getString(R.string.yesterday_at, time)
        return java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), Locale.getDefault())
            .format(java.util.Date(ms)) + ", " + time
    }

    // ------------------------------------------------------------------ actions

    private fun startWifi() {
        val name = ssidText.trim()
        getSharedPreferences("fuji", MODE_PRIVATE).edit().putString("ssid", name).apply()
        val password = passText
        startServiceForeground(TransferService.ACTION_CONNECT_WIFI) {
            it.putExtra(TransferService.EXTRA_SSID, name).putExtra(TransferService.EXTRA_PASS, password)
        }
    }

    private fun connectUsbClicked() {
        val usb = getSystemService(UsbManager::class.java)
        val device = UsbCamera.findDevice(usb)
        if (device == null) {
            TransferState.statusIsError = true
            TransferState.status = getString(R.string.usb_not_found)
            TransferState.errorDetail = TransferState.status
            TransferState.publish()
            return
        }
        requestUsb(device)
    }

    private fun requestUsb(device: UsbDevice) {
        val usb = getSystemService(UsbManager::class.java)
        if (usb.hasPermission(device)) {
            startUsb(device)
        } else {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE,
            )
            usb.requestPermission(device, pi)
        }
    }

    private fun startUsb(device: UsbDevice) {
        startServiceForeground(TransferService.ACTION_CONNECT_USB) { it.putExtra(TransferService.EXTRA_USB_NAME, device.deviceName) }
    }

    private fun downloadSelected() {
        if (inCache) {
            // chosen in the saved list: fetched once the camera is connected and its list read
            val keys = shownList.filter { it.handle in selected }.map { key(it) }
            if (keys.isEmpty()) return
            resumeKeys = keys
            if (TransferState.busy) resumeStep = 1 else {
                resumeStep = 0
                resumePosted = false
            }
            DebugLog.add("download dalla galleria salvata: ${keys.size} file, appena la camera è collegata")
            android.widget.Toast.makeText(
                this, resources.getQuantityString(R.plurals.queued_for_camera, keys.size, keys.size),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            onChanged()
            return
        }
        val chosen = TransferState.objects.filter { it.handle in selected }
        val handles = chosen.map { it.handle }
        if (handles.isEmpty()) return
        if (checkSpace(chosen)) return
        downloadsHidden = false
        startServiceForeground(TransferService.ACTION_DOWNLOAD) { it.putExtra(TransferService.EXTRA_HANDLES, handles.toLongArray()) }
    }

    // ------------------------------------------------------------------ Immich ("Immich")

    private fun readImmichMarks() {
        immichVersion = ImmichSync.version
        immichQueued = ImmichSync.queued(this)
        immichDone = ImmichSync.onServer(this)
        refreshVisibleRows(animate = false)
        updateBottom()
    }

    private fun immichMark(i: ObjectInfo): ImmichMark = when (key(i)) {
        in immichQueued -> ImmichMark.Queued
        in immichDone -> ImmichMark.Done
        else -> ImmichMark.None
    }

    /**
     * Queues [list] for Immich: files already on the phone wait only for the network; the others are downloaded
     * first, behind the gallery. Without a server set up, opens the Immich screen instead and returns false.
     */
    private fun queueForImmich(list: List<ObjectInfo>): Boolean {
        if (!ImmichSync.settings(this).configured) {
            previewOpen = false
            immichOpen = true
            android.widget.Toast.makeText(this, getString(R.string.toast_link_immich), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        ImmichSync.enqueue(this, list.map { key(it) })
        val missing = list.filter { key(it) !in done }
        if (missing.isNotEmpty() && TransferState.connected) {
            downloadsHidden = true
            quietBatchPending = true
            startServiceForeground(TransferService.ACTION_DOWNLOAD) { it.putExtra(TransferService.EXTRA_HANDLES, missing.map { m -> m.handle }.toLongArray()) }
        }
        ImmichSync.schedule(this) // the ones already on the phone; a batch schedules again when it ends
        readImmichMarks()
        return true
    }

    /** "Immich" in the selection bar: the selection closes and a message says what happens next, with Annulla. */
    private fun uploadSelected() {
        val list = shownList.filter { it.handle in selected && key(it) !in immichDone }
        if (list.isEmpty()) {
            android.widget.Toast.makeText(this, getString(R.string.toast_all_on_immich), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!queueForImmich(list)) return
        lastQueued = list
        selected.clear()
        uploadSnack = resources.getQuantityString(R.plurals.snack_queued, list.size, list.size)
        snackHandler.removeCallbacks(hideSnack)
        snackHandler.postDelayed(hideSnack, 8000)
        refreshVisibleRows(animate = true)
        updateBottom()
        viewer?.paintSelect()
    }

    /** Annulla: out of the Immich queue and selected again (a download already started goes on). */
    private fun undoUpload() {
        ImmichSync.dequeue(this, lastQueued.map { key(it) })
        lastQueued.forEach { selected.add(it.handle) }
        lastQueued = emptyList()
        uploadSnack = ""
        snackHandler.removeCallbacks(hideSnack)
        readImmichMarks()
        refreshVisibleRows(animate = true)
        updateBottom()
    }

    /** "Immich" on one file (details sheet, preview): queue it, or take it out of the queue. */
    private fun toggleUpload(info: ObjectInfo): ImmichMark = when (immichMark(info)) {
        ImmichMark.Queued -> {
            ImmichSync.dequeue(this, listOf(key(info)))
            readImmichMarks()
            ImmichMark.None
        }
        ImmichMark.None -> if (queueForImmich(listOf(info))) ImmichMark.Queued else ImmichMark.None
        ImmichMark.Done -> ImmichMark.Done
    }

    private fun sendAction(action: String) {
        startService(Intent(this, TransferService::class.java).setAction(action))
    }

    private fun startServiceForeground(action: String, extras: (Intent) -> Intent) {
        val i = extras(Intent(this, TransferService::class.java).setAction(action))
        startForegroundService(i)
    }

    private fun requestRuntimePermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            wanted.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add(Manifest.permission.BLUETOOTH_SCAN)
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
