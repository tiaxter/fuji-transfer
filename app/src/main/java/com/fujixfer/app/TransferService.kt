package com.fujixfer.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PatternMatcher
import android.os.PowerManager
import android.provider.MediaStore
import com.fujixfer.core.FujiBle
import com.fujixfer.core.FujiTcpLink
import com.fujixfer.core.FujiWifi
import com.fujixfer.core.ObjectInfo
import com.fujixfer.core.TransferEngine
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the camera connection. Runs in the foreground so Android does not kill the transfer when the
 * screen turns off, holds Wi-Fi/CPU locks, and keeps the camera's Wi-Fi bound to our sockets only.
 */
class TransferService : Service() {
    companion object {
        const val ACTION_CONNECT_WIFI = "com.fujixfer.app.CONNECT_WIFI"
        const val ACTION_CONNECT_USB = "com.fujixfer.app.CONNECT_USB"
        const val ACTION_PAIR_BLE = "com.fujixfer.app.PAIR_BLE"
        const val ACTION_CONNECT_BLE = "com.fujixfer.app.CONNECT_BLE"
        const val PREF_PAIRING = "ble_pairing"

        /** The listing saved at the last connection to [key] ("ble-" + Bluetooth address, …). */
        fun listingFile(c: Context, key: String) =
            File(c.filesDir, "listing-" + key.replace(Regex("[^A-Za-z0-9]"), "_") + ".tsv")

        /** The files of the last listing of [key], null if there is none: the gallery can show them offline. */
        fun cachedListing(c: Context, key: String): List<ObjectInfo>? = try {
            listingFile(c, key).takeIf { it.exists() }?.readLines()?.mapNotNull { ObjectInfo.fromLine(it) }
        } catch (e: IOException) {
            null
        }
        /** + listing key: the camera answers only the last of several queued requests (see TransferEngine.Options). */
        private const val PREF_NO_QUEUE_PREFIX = "no_queue_"
        const val ACTION_DOWNLOAD = "com.fujixfer.app.DOWNLOAD"
        const val ACTION_CANCEL = "com.fujixfer.app.CANCEL"
        /** "Finisci il file, poi fermati": the batch stops after the file in progress. */
        const val ACTION_STOP_AFTER = "com.fujixfer.app.STOP_AFTER"
        /** Fetch one original into the app cache to show it full size (long press on a photo). */
        const val ACTION_VIEW = "com.fujixfer.app.VIEW"
        const val ACTION_VIEW_CANCEL = "com.fujixfer.app.VIEW_CANCEL"
        /** Quick previews for the viewer (EXTRA_HANDLES, the photo on screen first, then its neighbours). */
        const val ACTION_PREVIEW = "com.fujixfer.app.PREVIEW"
        private const val PREF_PENDING_ITEMS = "pending_items"
        const val EXTRA_HANDLE = "handle"
        const val THUMBS_DURING_LISTING = true
        const val ACTION_DISCONNECT = "com.fujixfer.app.DISCONNECT"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASS = "pass"
        const val EXTRA_USB_NAME = "usbName"
        const val EXTRA_HANDLES = "handles"
        /** On the notification's intent while downloading: open the download screen. */
        const val EXTRA_SHOW_DOWNLOADS = "showDownloads"
        private const val CHANNEL = "transfer"
        private const val NOTIF_ID = 1
        private const val CAMERA_HOST = FujiTcpLink.DEFAULT_HOST
    }

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var engine: TransferEngine? = null
    @Volatile private var ble: BleGattLink? = null
    private val thumbScheduled = AtomicBoolean(false)
    /** True while the camera's list is being read: thumbnails and downloads then run between listing groups. */
    @Volatile private var listing = false
    private val pendingDownloads = java.util.concurrent.ConcurrentLinkedQueue<LongArray>()
    /** Original requested for the viewer (-1 = none); only the latest request counts. */
    private val pendingView = java.util.concurrent.atomic.AtomicLong(-1)
    /** Previews asked by the viewer; only the latest request counts (the user swiped on). */
    private val pendingPreviews = java.util.concurrent.atomic.AtomicReference<LongArray?>(null)
    /** Screen off: the listing, thumbnails and background scan wait (see holdWhileScreenOff). */
    @Volatile private var screenOff = false
    /** When the viewer last gave up an original half way (it moved to another file). */
    @Volatile private var viewCancelledAt = 0L
    /** End of the last preview read from the camera, and how many came close together (see pacePreview). */
    private var previewEndedAt = 0L
    private var previewBurst = 0
    @Volatile private var viewCancel = false
    @Volatile private var blePairing: FujiBle.Pairing? = null
    private val bleLog = object : FujiBle.Log {
        override fun log(message: String) = setStatus(message)

        override fun trace(message: String) = DebugLog.add("  $message")
    }
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cancelRequested = false
    @Volatile private var joining: CompletableFuture<Network>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLowLatency: WifiManager.WifiLock? = null
    private var lastNotification = 0L
    private lateinit var nm: NotificationManager
    private lateinit var prefs: SharedPreferences
    private lateinit var metaStore: MetaStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        // every command to the camera, its answer, time and bytes: into the log file (shared with "Condividi")
        com.fujixfer.core.PtpSession.wire = java.util.function.Consumer<String> { DebugLog.detail("ptp $it") }
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        prefs = getSharedPreferences("fuji", Context.MODE_PRIVATE)
        metaStore = MetaStore(this)
        // in the log, to tell a drop caused by the phone (screen off, app in background) from one by the camera
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(defaultNetLog)
        } catch (e: Exception) {
            // logging only
        }
        screenOff = !getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screenLog, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        try {
            @Suppress("DEPRECATION")
            registerReceiver(wifiLog, android.content.IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            })
        } catch (e: Exception) {
            DebugLog.detail("eventi Wi-Fi di Android non disponibili: ${e.message}")
        }
        if (TransferState.meta.isEmpty()) {
            TransferState.meta = try {
                metaStore.loadAll()
            } catch (e: Exception) {
                DebugLog.add("  metadati non letti dal database: ${e.message}")
                emptyMap()
            }
        }
        Thumbs.init(this)
        Previews.init(this)
        Thumbs.fetcher = { scheduleThumbs() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent ?: return START_NOT_STICKY
        when (cmd.action) {
            ACTION_CANCEL -> cancelRequested = true
            ACTION_STOP_AFTER -> {
                TransferState.stopAfterCurrent = true
                DebugLog.add("» il lotto si ferma dopo il file in corso")
                TransferState.publish()
            }
            ACTION_DISCONNECT -> {
                // The io thread may be stuck in the connection (Bluetooth, Wi-Fi request, socket): tear down from
                // a thread of its own so the blocked calls fail at once and the screen answers right away.
                cancelRequested = true
                TransferState.busy = false
                TransferState.connected = false
                TransferState.publish()
                Thread({ disconnect() }, "fuji-cancel").start()
            }
            ACTION_CONNECT_WIFI -> {
                foreground("Connessione alla camera…")
                val ssid = cmd.getStringExtra(EXTRA_SSID).orEmpty()
                val pass = cmd.getStringExtra(EXTRA_PASS).orEmpty()
                io.execute { connect("Wi-Fi") { connectWifiManual(ssid, pass) } }
            }
            ACTION_PAIR_BLE, ACTION_CONNECT_BLE -> {
                foreground("Connessione alla camera via Bluetooth…")
                val pairNew = cmd.action == ACTION_PAIR_BLE
                io.execute { connect("Wi-Fi") { connectBle(pairNew) } }
            }
            ACTION_CONNECT_USB -> {
                foreground("Connessione USB…")
                val name = cmd.getStringExtra(EXTRA_USB_NAME).orEmpty()
                io.execute { connect("USB") { connectUsb(name) } }
            }
            ACTION_VIEW -> {
                val h = cmd.getLongExtra(EXTRA_HANDLE, -1)
                if (h >= 0) {
                    viewCancel = false
                    TransferState.viewHandle = h
                    TransferState.viewFile = null
                    TransferState.viewError = ""
                    TransferState.viewDone = 0
                    TransferState.viewTotal = 0
                    TransferState.publish()
                    pendingView.set(h)
                    io.execute { runPendingView() } // during the listing it is served between two groups
                }
            }
            ACTION_PREVIEW -> {
                val hs = cmd.getLongArrayExtra(EXTRA_HANDLES)
                if (hs != null && hs.isNotEmpty()) {
                    pendingPreviews.set(hs)
                    io.execute { runPendingPreviews() } // during the listing it is served between two groups
                }
            }
            ACTION_VIEW_CANCEL -> {
                viewCancel = true
                pendingView.set(-1)
                TransferState.viewHandle = -1
                TransferState.publish()
            }
            ACTION_DOWNLOAD -> {
                foreground("Download in corso…")
                val handles = cmd.getLongArrayExtra(EXTRA_HANDLES) ?: LongArray(0)
                pendingDownloads.add(handles)
                // while the list is being read, the listing runs it between two groups (see onIdle)
                if (listing) setStatus(getString(R.string.st_download_requested))
                io.execute { runPendingDownloads() }
            }
        }
        return START_NOT_STICKY
    }

    private val screenLog = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                screenOff = true
                DebugLog.add("schermo spento")
                DebugLog.add("  " + energyState())
            } else {
                screenOff = false
                DebugLog.add("schermo acceso")
            }
        }
    }

    /**
     * Android's own Wi-Fi events, into the log file: whether the phone leaves and rejoins the camera's access
     * point at the 18 s drop (association, supplicant states) or only redoes the IP set-up. Deprecated broadcasts,
     * still sent on recent Android though some details may be hidden; the log shows what arrives.
     */
    private val wifiLog = object : android.content.BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val what = when (i.action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val ni = i.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    "stato rete ${ni?.detailedState} (${ni?.extraInfo ?: ""})"
                }
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                    val st = i.getParcelableExtra<android.net.wifi.SupplicantState>(WifiManager.EXTRA_NEW_STATE)
                    val err = i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                    "supplicant $st" + (if (err >= 0) ", errore $err" else "")
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> "Wi-Fi del telefono: stato ${i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)}"
                else -> i.action
            }
            DebugLog.detail("Android Wi-Fi: $what")
        }
    }

    override fun onDestroy() {
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(defaultNetLog)
        } catch (e: Exception) {
            // not registered
        }
        try {
            unregisterReceiver(screenLog)
        } catch (e: IllegalArgumentException) {
            // not registered
        }
        try {
            unregisterReceiver(wifiLog)
        } catch (e: IllegalArgumentException) {
            // not registered
        }
        Thumbs.fetcher = null
        io.shutdownNow()
        engine?.close()
        ble?.close()
        releaseLocks()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ connect

    /** Moments of the connection being made, for one summary line when the first files show; null after it. */
    @Volatile private var phases: MutableList<Pair<String, Long>>? = null

    private fun phase(name: String) {
        phases?.add(name to System.currentTimeMillis())
    }

    /** "tempi della connessione: Bluetooth e abbinamento 1.2 s · … · totale 8.4 s", once per connection. */
    private fun logPhases(last: String) {
        val p = phases ?: return
        phases = null
        p.add(last to System.currentTimeMillis())
        val parts = (1 until p.size).joinToString(" · ") { i ->
            String.format(Locale.ROOT, "%s %.1f s", p[i].first, (p[i].second - p[i - 1].second) / 1000.0)
        }
        DebugLog.add(String.format(Locale.ROOT, "  tempi della connessione: %s · totale %.1f s",
            parts, (p.last().second - p.first().second) / 1000.0))
    }

    private fun connect(label: String, makeConnector: () -> TransferEngine.Connector) {
        phases = mutableListOf("inizio" to System.currentTimeMillis())
        cancelRequested = false
        engine?.close()
        engine = null
        TransferState.busy = true
        TransferState.connected = false
        TransferState.objects = emptyList()
        TransferState.connectionLabel = label
        TransferState.cameraName = getString(if (label == "USB") R.string.camera_usb else R.string.camera_label)
        TransferState.connectStep = 0
        TransferState.retryNote = ""
        Thumbs.reset()
        TransferState.previewMissing = emptySet() // another body or mode may have the copies
        thumbExif = null
        setStatus(getString(R.string.st_connecting))
        acquireLocks()
        try {
            listingKey = null
            val connector = makeConnector() // sets listingKey
            val key = listingKey
            // a camera already seen answering only the last of several queued requests (X-M5): one at a time at once
            val oneAtATime = key != null && prefs.getBoolean(PREF_NO_QUEUE_PREFIX + key, false)
            if (oneAtATime) DebugLog.add("  elenco una richiesta alla volta: questa camera non accoda i comandi (visto in una connessione precedente)")
            val eng = TransferEngine(connector, TransferEngine.Options().apply { listOneAtATime = oneAtATime })
            engine = eng
            val previous = key?.let { loadListing(it) }
            if (!previous.isNullOrEmpty()) DebugLog.add("  elenco precedente: ${previous.size} file, controllo se è ancora valido")
            val started = System.currentTimeMillis()
            var lastSaved = previous?.size ?: 0
            var groupsTried = false
            val days = DayPlan()
            TransferState.dayGroups = emptyList()
            TransferState.wantedDays = emptySet()
            TransferState.unreadByDay = null
            listing = true
            val list = try {
                eng.listObjects(listener(), previous, object : TransferEngine.ListingListener {
                    override fun onPartial(soFar: List<ObjectInfo>, done: Int, total: Int) {
                        logPhases("primo elenco")
                        if (!groupsTried) {
                            groupsTried = true
                            days.load(eng, total)
                        }
                        days.check(soFar)
                        TransferState.unreadByDay = days.unread()
                        // keep what was read so far: a connection that drops at 300 of 445 resumes from there
                        if (key != null && done - lastSaved >= 50 && done < total) {
                            saveListing(key, soFar)
                            lastSaved = done
                        }
                        // newest first: show the grid, usable, while the rest of the card is still being read
                        TransferState.objects = soFar.sortedWith(newestFirst)
                        TransferState.sizesKnown = eng.listedWithRealSizes()
                        TransferState.listing = done < total
                        TransferState.listDone = done
                        TransferState.listTotal = total
                        TransferState.connected = true
                        TransferState.busy = false
                        setStatus(getString(R.string.st_listing_n, done, total), steady = true)
                    }

                    override fun priority(): List<Long>? = days.wanted(TransferState.wantedDays)

                    override fun onIdle(): Boolean {
                        if (cancelRequested) throw TransferEngine.CancelledException() // "Disconnetti" during the listing
                        quietAroundEighteen()
                        val view = runPendingView()
                        val previews = runPendingPreviews()
                        val downloads = runPendingDownloads()
                        holdWhileScreenOff(eng) // an IOException here is a listing error: reconnect and go on
                        // Thumbnails between two groups (the drops of 2 Nov came with no thumbnail in flight).
                        val thumbs = if (THUMBS_DURING_LISTING) drainThumbsNow() else false
                        return view || previews || downloads || thumbs
                    }
                }).sortedWith(newestFirst)
            } finally {
                listing = false
                TransferState.listing = false
                TransferState.dayGroups = emptyList()
                TransferState.unreadByDay = null
            }
            DebugLog.add("  elenco: ${list.size} file in ${(System.currentTimeMillis() - started) / 1000} s")
            if (key != null && eng.listingRefusedQueue()) {
                prefs.edit().putBoolean(PREF_NO_QUEUE_PREFIX + key, true).apply()
                DebugLog.add("  ricordato: questa camera non accoda i comandi, la prossima volta elenco una richiesta alla volta")
            }
            if (key != null) saveListing(key, list)
            TransferState.objects = list
            TransferState.sizesKnown = eng.listedWithRealSizes()
            TransferState.connected = true
            scheduleThumbs() // requests made during the listing that it did not get to
            io.execute { scanMeta(eng) } // positions and films, in the background, after the thumbnails
            setStatus(getString(R.string.st_connected_n, list.size), steady = true)
            updateNotification(getString(R.string.notif_connected_n, list.size), 0, 0, force = true)
        } catch (e: Exception) {
            if (cancelRequested || e is TransferEngine.CancelledException) {
                DebugLog.add("connessione annullata")
                teardown()
                TransferState.objects = emptyList()
                setStatus(getString(R.string.st_not_connected))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            logPhases("errore")
            DebugLog.add("eccezione: $e")
            TransferState.errorDetail = e.message ?: e.toString()
            setStatus(getString(R.string.st_connect_failed, e.message ?: e.toString()), error = true)
            teardown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } finally {
            TransferState.busy = false
            TransferState.publish()
        }
    }

    private val newestFirst = compareByDescending<ObjectInfo> { it.captureDate }.thenByDescending { it.filename }

    /**
     * Days of the card as XApp builds its gallery: 0xD621 gives the handles in gallery order (newest day first)
     * and the 0x9053 counts split that list into days, in order. The days show at once with grey cells for the
     * files not read yet, and the days on screen are read first. Every file read is checked against its
     * expected day: one miss and the priority is dropped (the days still show, files come newest first).
     */
    private inner class DayPlan {
        private var dayOf: Map<Long, String> = emptyMap()
        private var handlesOf: Map<String, List<Long>> = emptyMap()
        private var trusted = false

        fun load(eng: TransferEngine, total: Int) {
            val fuji = try {
                eng.session().fujiWifi()
            } catch (e: IOException) {
                null
            } ?: return
            val groups = fuji.dateGroups()
            if (groups.isEmpty()) return
            val parsed = groups.map { DayGroup(it.date.filter { c -> c.isDigit() }.take(8), it.count) }
            val sum = parsed.sumOf { it.count }
            if (sum != total || parsed.any { it.day.length < 8 }) {
                DebugLog.add("  giorni dalla camera ignorati: somma $sum invece di $total, oppure date illeggibili")
                return
            }
            TransferState.dayGroups = parsed.groupBy { it.day }.map { (d, g) -> DayGroup(d, g.sumOf { it.count }) }
                .sortedByDescending { it.day }
            val handles = fuji.importHandles()
            if (handles.size != total) {
                DebugLog.add("  galleria per giorni: ${parsed.size} giorni pronti (senza 0xD621: niente lettura prioritaria)")
                return
            }
            val d = HashMap<Long, String>()
            val h = HashMap<String, MutableList<Long>>()
            var i = 0
            for (g in parsed) { // same order as the camera sends both lists
                for (k in 0 until g.count) {
                    val handle = handles[i++]
                    d[handle] = g.day
                    h.getOrPut(g.day) { ArrayList() }.add(handle)
                }
            }
            dayOf = d
            handlesOf = h
            trusted = true
            DebugLog.add("  galleria per giorni: ${parsed.size} giorni pronti, file abbinati ai giorni come in XApp")
        }

        private val read = HashSet<Long>()
        private var misses = 0
        private var edge = 0

        private fun nextDays(a: String, b: String): Boolean = try {
            val f = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
            Math.abs(java.time.LocalDate.parse(a, f).toEpochDay() - java.time.LocalDate.parse(b, f).toEpochDay()) == 1L
        } catch (e: java.time.format.DateTimeParseException) {
            false
        }

        /**
         * Files read should be on the day 0x9053/0xD621 put them on. A wrong guess only changes which files are
         * read first, so a few misses are tolerated; past 5% of what was read the priority is dropped.
         */
        fun check(soFar: List<ObjectInfo>) {
            for (o in soFar) {
                if (!read.add(o.handle) || !trusted) continue
                val day = dayOf[o.handle]
                val actual = o.captureDate.take(8)
                if (day == actual) continue
                // on 3 Nov two shots of 31 Oct sat at the start of 1 Nov: a neighbour day only changes which
                // files come first, it does not make the mapping wrong
                if (day != null && nextDays(actual, day)) {
                    if (edge++ == 0) DebugLog.add("  ${o.filename} scattato il $actual, la galleria lo mette nel giorno vicino $day (tollerato)")
                    continue
                }
                misses++
                if (misses <= 3) DebugLog.add("  ${o.filename} (handle ${o.handle}) scattato il $actual, la galleria lo mette nel giorno $day")
                if (misses > 2 && misses * 20 > read.size) {
                    trusted = false
                    DebugLog.add("  giorni della galleria: $misses file fuori posto su ${read.size}, lettura prioritaria disattivata")
                }
            }
        }

        /**
         * Files still to read per gallery day. A shot of 31 Oct that the gallery puts in 1 Nov shows on 31 Oct
         * once read, but it was one of 1 Nov's cells: counting by handle, 1 Nov's grey cells go away when all its
         * handles are read, instead of waiting for files dated 1 Nov that will never come.
         */
        fun unread(): Map<String, Int>? {
            if (handlesOf.isEmpty()) return null
            return handlesOf.mapValues { (_, hs) -> hs.count { it !in read } }
        }

        /** Not-yet-read handles of the days on screen (at most 60 at a time). */
        fun wanted(days: Set<String>): List<Long>? {
            if (!trusted || days.isEmpty()) return null
            val out = ArrayList<Long>()
            for (d in days) {
                for (h in handlesOf[d].orEmpty()) {
                    if (h in read) continue
                    out.add(h)
                    if (out.size >= 60) return out
                }
            }
            return out
        }
    }

    /** Identifies the camera for the listing cache; set by the connector before the session opens. */
    @Volatile private var listingKey: String? = null

    private fun listingFile(key: String) = listingFile(this, key)

    private fun loadListing(key: String): List<ObjectInfo>? = cachedListing(this, key)

    private fun saveListing(key: String, list: List<ObjectInfo>) {
        try {
            listingFile(key).writeText(list.joinToString("\n") { it.toLine() })
        } catch (e: IOException) {
            DebugLog.add("  elenco non salvato: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ Bluetooth + Wi-Fi

    /**
     * Same sequence as XApp: find the body over Bluetooth, pair or re-authenticate, ask it to start its
     * access point (InCameraViewing), read SSID/password/BSSID and join that network.
     */
    private fun connectBle(pairNew: Boolean): TransferEngine.Connector {
        checkBlePermissions()
        DebugLog.add("Telefono ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        DebugLog.add("  " + energyState())
        val stored = FujiBle.Pairing.parse(prefs.getString(PREF_PAIRING, null))
        DebugLog.add("Abbinamento salvato: " + (stored?.let { "${if (it.red) "RED" else "legacy"} ${it.name} ${it.address}" } ?: "nessuno"))
        val phone = phoneName()
        val (link, pairing) = if (pairNew) pairNewCamera(phone) else connectKnownCamera(stored, phone)
        phase("Bluetooth e abbinamento")
        if (cancelRequested) throw TransferEngine.CancelledException()
        blePairing = pairing
        TransferState.cameraName = pairing.name.ifEmpty { "Camera" }
        TransferState.connectStep = 1
        TransferState.publish()
        listingKey = "ble-" + pairing.address
        prefs.edit().putString(PREF_PAIRING, pairing.serialize()).apply()
        TransferState.publish()
        launchAndJoin(link, pairing.red)
        phase("rete della camera collegata")
        return wifiConnector(bluetoothHandoff = true)
    }

    /** Registration from scratch: the camera must be showing "pairing" in its Bluetooth settings. */
    private fun pairNewCamera(phone: String): Pair<BleGattLink, FujiBle.Pairing> {
        setStatus(getString(R.string.st_pair_search))
        val found = BleScanner.find(this, 60_000) { it.pairingMode } ?: throw IOException(getString(R.string.ex_no_pairing_camera))
        setStatus(getString(R.string.st_found_linking))
        val link = openBle(found.device, 3, 15_000)
        return link to FujiBle.pair(link, found.advert, phone, bleLog)
    }

    /**
     * Camera already paired with this phone, by this app or by XApp. Tries the devices the phone already
     * knows first (XApp may hold the link, and then the camera does not advertise), then scans.
     */
    private fun connectKnownCamera(stored: FujiBle.Pairing?, phone: String): Pair<BleGattLink, FujiBle.Pairing> {
        setStatus(getString(R.string.st_known_search))
        var lastError: IOException? = null
        for (dev in BleScanner.knownCandidates(this, stored?.address)) {
            val already = BleScanner.isConnected(this, dev)
            setStatus(getString(if (already) R.string.st_trying_linked else R.string.st_trying, dev.name ?: dev.address))
            val link = try {
                openBle(dev, 1, if (already) 10_000 else 8_000)
            } catch (e: IOException) {
                DebugLog.add("candidato ${dev.address} non raggiungibile: ${e.message}")
                lastError = e
                continue
            }
            try {
                return link to handshake(link, stored, dev.address, dev.name ?: "", phone)
            } catch (e: IOException) {
                DebugLog.add("candidato ${dev.address} scartato: ${e.message}")
                lastError = e
                closeBle()
            }
        }
        setStatus(getString(R.string.st_ble_search))
        val found = BleScanner.find(this, 30_000) { adv ->
            stored == null || stored.matches(adv) || stored.address.equals(adv.address, ignoreCase = true)
        } ?: throw lastError ?: IOException(getString(R.string.ex_ble_not_found))
        setStatus(getString(R.string.st_found_linking))
        val link = openBle(found.device, 3, 15_000)
        return link to handshake(link, stored, found.device.address, found.advert.name, phone)
    }

    /** True from the camera's "access point off" notification until it reports it on again. */
    @Volatile private var cameraWifiOff = false

    /**
     * The camera keeps notifying its access-point state over Bluetooth while the photos move over Wi-Fi: when it
     * turns the access point off, say so (it is the camera ending the link, not the phone) and skip the dead
     * network on the next connection.
     */
    private fun watchBle(uuid: String, value: ByteArray) {
        if (uuid != FujiBle.CH_AP_STATE.uppercase(Locale.ROOT) || value.size < 2) return
        val state = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        val up = state == FujiBle.AP_LAUNCHED || state == FujiBle.AP_LAUNCHED_RESERVED_TRANSFER
        if (state == FujiBle.AP_NOT_LAUNCHED && !cameraWifiOff && TransferState.connected) {
            DebugLog.add("» La camera ha spento il suo Wi-Fi (notifica Bluetooth 0x${Integer.toHexString(state)})")
        }
        cameraWifiOff = !up
    }

    private fun handshake(link: BleGattLink, stored: FujiBle.Pairing?, address: String, name: String, phone: String) =
        if (stored != null && stored.address.equals(address, ignoreCase = true)) {
            FujiBle.reconnect(link, stored, phone, bleLog)
        } else {
            FujiBle.adopt(link, address, name, phone, bleLog) // pairing made by XApp: reuse it
        }

    private fun openBle(device: BluetoothDevice, attempts: Int, timeoutMs: Long): BleGattLink {
        closeBle()
        var last: IOException? = null
        for (attempt in 1..attempts) {
            val l = BleGattLink(this, device)
            l.onNotify = ::watchBle
            try {
                l.connect(timeoutMs)
                ble = l
                return l
            } catch (e: IOException) {
                DebugLog.add("BLE collegamento fallito (tentativo $attempt): ${e.message}")
                l.close()
                last = e
                if (attempt < attempts) Thread.sleep(800)
            }
        }
        throw last ?: IOException(getString(R.string.ex_ble_link))
    }

    private fun closeBle() {
        ble?.close()
        ble = null
    }

    private fun launchAndJoin(link: BleGattLink, red: Boolean) {
        TransferState.connectStep = 1
        TransferState.publish()
        val c = FujiBle.launchWifi(link, red, 25_000, bleLog)
        phase("Wi-Fi acceso dalla camera")
        TransferState.connectStep = 2
        TransferState.publish()
        if (cancelRequested) throw TransferEngine.CancelledException()
        setStatus(getString(R.string.st_joining, c.ssid))
        // Like XApp (WiFiHandOverService): always the exact name + BSSID, retried as is. A request without the
        // BSSID is a "pattern" for Android: it asks for confirmation every time and never remembers the approval,
        // which is why the confirmation kept coming back after the old name-only fallback.
        try {
            joinCameraWifi(c.ssid, PatternMatcher.PATTERN_LITERAL, c.passphrase, c.bssid)
        } catch (e: TransferEngine.CancelledException) {
            throw e
        } catch (e: IOException) {
            // 4 Nov, 13:59: the camera reported its Wi-Fi on, the phone was turned away 8 times in 5 s and the two
            // requests back to back both failed. The access point may need a few seconds more: wait, then retry.
            var last: IOException = e
            for (attempt in 2..3) {
                if (cancelRequested) throw TransferEngine.CancelledException()
                DebugLog.add("Wi-Fi non collegato (${last.message}), riprovo tra 3 s (tentativo $attempt di 3)")
                try {
                    Thread.sleep(3000)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw TransferEngine.CancelledException()
                }
                if (cancelRequested) throw TransferEngine.CancelledException()
                try {
                    joinCameraWifi(c.ssid, PatternMatcher.PATTERN_LITERAL, c.passphrase, c.bssid)
                    return
                } catch (e2: TransferEngine.CancelledException) {
                    throw e2
                } catch (e2: IOException) {
                    last = e2
                }
            }
            throw last
        }
    }

    private fun checkBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                .any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing) throw IOException(getString(R.string.ex_nearby))
        } else {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw IOException(getString(R.string.ex_location_perm))
            }
            val lm = getSystemService(LocationManager::class.java)
            if (lm != null && !lm.isLocationEnabled) {
                throw IOException(getString(R.string.ex_location_on))
            }
        }
    }

    private fun phoneName() = Build.MODEL.take(20)

    // ------------------------------------------------------------------ Wi-Fi

    /** Manual mode: the camera's Wi-Fi was started from its own menu. */
    private fun connectWifiManual(ssidPrefix: String, passphrase: String): TransferEngine.Connector {
        listingKey = "wifi-" + ssidPrefix.ifBlank { "rete-attuale" }
        if (ssidPrefix.isBlank()) {
            // The user already joined the camera in system settings: use that Wi-Fi network.
            val cm = getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val existing = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: throw IOException(getString(R.string.ex_no_wifi))
            wifiNetwork = existing
        } else {
            TransferState.connectStep = 2
            joinCameraWifi(ssidPrefix, PatternMatcher.PATTERN_PREFIX, passphrase, "")
        }
        return wifiConnector(bluetoothHandoff = false)
    }

    /** Requests the camera's Wi-Fi as a private network: Android keeps it up even without Internet. */
    private fun joinCameraWifi(ssid: String, pattern: Int, passphrase: String, bssid: String) {
        val cm = getSystemService(ConnectivityManager::class.java)
        unregisterNetwork()
        val ready = CompletableFuture<Network>()
        joining = ready
        if (cancelRequested) throw TransferEngine.CancelledException()
        // Same request as XApp (WiFiHandOverService.startWifiHandover): SSID as a literal pattern, BSSID,
        // passphrase, no Internet capability. With the BSSID given it is a single access point, so Android asks
        // for the user's approval once and then joins without asking (until the app is reinstalled or the
        // network is forgotten).
        val builder = WifiNetworkSpecifier.Builder().setSsidPattern(PatternMatcher(ssid, pattern))
        if (bssid.isNotEmpty()) builder.setBssid(MacAddress.fromString(bssid))
        if (passphrase.isNotEmpty()) builder.setWpa2Passphrase(passphrase)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                netUpAt = System.currentTimeMillis()
                DebugLog.wifiJoinedAt = netUpAt // file lines now carry w+seconds since this moment
                DebugLog.detail("rete della camera disponibile (onAvailable): $network")
                ready.complete(network)
            }

            override fun onLost(network: Network) {
                DebugLog.add("  rete della camera persa dal telefono (onLost)")
                if (wifiNetwork == network) wifiNetwork = null
            }

            // What the phone does with the camera network, to tell a drop on the phone side from one on the camera
            // side: the drops of 3 Nov came 18.1 s after onAvailable, both times, with the network still up.
            private var lastAddr = ""
            private var lastRssiLog = 0L

            private var dhcpLogged = false

            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                DebugLog.detail("rete della camera, proprietà: $lp")
                val addr = lp.linkAddresses.joinToString { it.toString() }
                if (addr != lastAddr) {
                    // the "type A" drop (18.1 s after joining): the phone removed the address and got it back
                    // 40 ms later (3 Nov, 16:41 and 16:53), so the age says whether it is the same event
                    val age = if (netUpAt > 0) " a ${(System.currentTimeMillis() - netUpAt) / 100 / 10.0} s dalla connessione" else ""
                    DebugLog.add("  rete della camera: indirizzi ${addr.ifEmpty { "nessuno" }} (${lp.interfaceName})$age")
                }
                lastAddr = addr
                if (!dhcpLogged && addr.isNotEmpty()) {
                    dhcpLogged = true
                    logDhcp(lp)
                }
            }

            private var lastCaps = ""
            private var lastCapsLog = 0L

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                val now = System.currentTimeMillis()
                // in the file: every change (at most one a second while only the signal moves)
                val w = nc.transportInfo as? android.net.wifi.WifiInfo
                if (w != null) lastWifi = w
                val caps = nc.toString().replace(Regex("SignalStrength: -?\\d+"), "")
                if (caps != lastCaps || now - lastCapsLog >= 1000) {
                    DebugLog.detail("rete della camera, capacità: segnale ${w?.rssi} dBm, collegamento ${w?.linkSpeed} Mbit/s " +
                        "(ricezione ${w?.rxLinkSpeedMbps}, trasmissione ${w?.txLinkSpeedMbps}), " +
                        "${w?.frequency} MHz" + (if (caps != lastCaps) " · $nc" else ""))
                    lastCaps = caps
                    lastCapsLog = now
                }
                if (now - lastRssiLog < 5000) return
                lastRssiLog = now
                val rssi = (nc.transportInfo as? android.net.wifi.WifiInfo)?.rssi
                DebugLog.add("  rete della camera: segnale ${rssi ?: "?"} dBm, ${nc.linkDownstreamBandwidthKbps / 1000} Mbit/s")
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                DebugLog.add("  rete della camera " + (if (blocked) "BLOCCATA dal sistema per l'app (per esempio risparmio energetico o limiti in background)" else "di nuovo consentita all'app"))
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                DebugLog.add("  il telefono sta per lasciare la rete della camera (tra $maxMsToLive ms)")
            }

            override fun onUnavailable() {
                ready.completeExceptionally(IOException(getString(R.string.ex_net_refused)))
            }
        }
        netCallback = cb
        watchLocalOnlyFailures()
        cm.requestNetwork(request, cb, 60_000)
        try {
            ready.get(70, TimeUnit.SECONDS)
        } catch (e: Exception) {
            unregisterNetwork()
            if (cancelRequested) throw TransferEngine.CancelledException()
            throw IOException(e.cause?.message ?: getString(R.string.ex_wifi_timeout))
        } finally {
            joining = null
        }
    }

    private var localOnlyWatch: Any? = null

    /** Last Wi-Fi details Android gave for the camera network (from onCapabilitiesChanged). */
    @Volatile private var lastWifi: android.net.wifi.WifiInfo? = null

    /**
     * The camera link as Android describes it: band, standard, speeds in both directions, signal. Android reports
     * the speeds it negotiated, not what goes through; values it does not know come as -1.
     */
    private fun wifiLinkSummary(): String {
        val w = lastWifi ?: return "dati del collegamento Wi-Fi non disponibili"
        val std = if (Build.VERSION.SDK_INT >= 30) when (w.wifiStandard) {
            android.net.wifi.ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            android.net.wifi.ScanResult.WIFI_STANDARD_11N -> "802.11n"
            android.net.wifi.ScanResult.WIFI_STANDARD_11AC -> "802.11ac"
            android.net.wifi.ScanResult.WIFI_STANDARD_11AX -> "802.11ax"
            else -> "standard ${w.wifiStandard}"
        } else "standard ?"
        return "Wi-Fi della camera: ${w.frequency} MHz, $std, ricezione ${w.rxLinkSpeedMbps} Mbit/s, " +
            "trasmissione ${w.txLinkSpeedMbps} Mbit/s, segnale ${w.rssi} dBm"
    }
    @Volatile private var netUpAt = 0L

    /**
     * The camera's DHCP terms, once per Wi-Fi join. Hypothesis for the 18.1 s drop (not verified): a DHCP lease of
     * about 36 s, renewed by the phone at half of it, and the renewal going wrong. WifiManager.dhcpInfo is
     * deprecated and may describe another network; the log says what it reports, nothing more.
     */
    private fun logDhcp(lp: android.net.LinkProperties) {
        try {
            fun ip(v: Int) = "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 24) and 0xFF}"
            val server = if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress ?: "?" else "?"
            @Suppress("DEPRECATION")
            val d = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo
            val lease = if (d != null && d.leaseDuration > 0) "${d.leaseDuration} s" else "non indicato"
            DebugLog.add("  DHCP della camera: server $server; WifiManager riporta lease $lease, " +
                "indirizzo ${d?.let { ip(it.ipAddress) } ?: "?"}, server ${d?.let { ip(it.serverAddress) } ?: "?"}")
        } catch (e: Exception) {
            DebugLog.add("  DHCP della camera: non leggibile (${e.message})")
        }
    }

    private fun netAge() = if (netUpAt > 0) " (rete della camera collegata da ${(System.currentTimeMillis() - netUpAt) / 100 / 10.0} s)" else ""

    /** The phone's own default network (home Wi-Fi, mobile data): a change there at the moment of a drop is a clue. */
    private val defaultNetLog = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            val kind = when {
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dati mobili"
                else -> "altro"
            }
            val key = "$network/$kind"
            if (key != lastDefault) DebugLog.add("  rete principale del telefono: $kind")
            lastDefault = key
        }

        override fun onLost(network: Network) {
            DebugLog.add("  rete principale del telefono persa")
            lastDefault = ""
        }
    }
    private var lastDefault = ""

    /** Android 14+: the reason the system gives when a local-only Wi-Fi connection fails. */
    private fun watchLocalOnlyFailures() {
        if (Build.VERSION.SDK_INT < 34 || localOnlyWatch != null) return
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val l = WifiManager.LocalOnlyConnectionFailureListener { spec, reason ->
            DebugLog.add("  Android: collegamento locale alla camera non riuscito, motivo $reason ($spec)")
        }
        try {
            wm.addLocalOnlyConnectionFailureListener(mainExecutor, l)
            localOnlyWatch = l
        } catch (e: Exception) {
            // not available on this build of Android
        }
    }

    private fun unregisterNetwork() {
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // already unregistered
            }
        }
        netCallback = null
        wifiNetwork = null
    }

    /**
     * PTP/IP session on the camera network, set up the way XApp and fudge do it (see FujiWifi): handshake,
     * OpenSession, wait for access, then pick the function (ClientState). If the access point went away,
     * ask for it again over Bluetooth.
     *
     * @param bluetoothHandoff true when the Wi-Fi was started over Bluetooth (XApp mode on recent bodies)
     */
    private fun wifiConnector(bluetoothHandoff: Boolean): TransferEngine.Connector {
        val guid = FujiTcpLink.FUJI_CLIENT_GUID.clone() // the camera requires exactly this GUID (see FujiTcpLink)
        val name = phoneName()
        val options = FujiTcpLink.Options().apply {
            onWaitingForCamera = Runnable {
                setStatus(getString(R.string.st_cam_waiting))
            }
            onTrace = java.util.function.Consumer { DebugLog.add("  $it") }
        }
        val fujiOptions = FujiWifi.Options().apply { this.bluetoothHandoff = bluetoothHandoff }
        val progress = object : FujiWifi.Progress {
            override fun trace(message: String) = DebugLog.add("  $message")

            override fun waitingForAccess() =
                setStatus(getString(R.string.st_cam_access))

            override fun slowModeChange() =
                setStatus(getString(R.string.st_cam_switching))
        }
        return TransferEngine.Connector {
            if (cancelRequested) throw TransferEngine.CancelledException()
            val cm = getSystemService(ConnectivityManager::class.java)
            // a network the system already dropped still sits in wifiNetwork until its callback arrives: binding
            // a socket to it fails with EPERM, so check it is still alive
            val net = wifiNetwork?.takeIf { !cameraWifiOff && cm.getNetworkCapabilities(it) != null } ?: rejoinViaBle()
            TransferState.connectStep = 3
            setStatus(getString(R.string.st_session_opening))
            val session = FujiTcpLink.openSession(
                net.socketFactory, CAMERA_HOST, FujiTcpLink.DEFAULT_PORT, guid, name, options, fujiOptions, progress,
            )
            phase("sessione PTP e galleria")
            val f = session.fujiWifi()
            val mode = if (f?.isXapp == true) "XApp (ClientState 20)" else "classico (ClientState ${f?.clientMode()})"
            DebugLog.add("  sessione Fuji pronta: modo $mode, stato camera ${f?.cameraState()}, ${f?.objectCount()} oggetti")
            setStatus(getString(R.string.st_session_open), steady = true)
            session
        }
    }

    private fun rejoinViaBle(): Network {
        val link = ble?.takeIf { it.isConnected } ?: throw IOException(getString(R.string.ex_wifi_lost))
        setStatus(getString(R.string.st_wifi_relaunch))
        launchAndJoin(link, blePairing?.red ?: false)
        return wifiNetwork ?: throw IOException(getString(R.string.ex_wifi_lost))
    }

    // ------------------------------------------------------------------ USB

    private fun connectUsb(deviceName: String): TransferEngine.Connector {
        TransferState.connectStep = 3
        val usb = getSystemService(UsbManager::class.java)
        val device = usb.deviceList.values.firstOrNull { it.deviceName == deviceName }
            ?: throw IOException(getString(R.string.ex_usb_missing))
        var attempts = 0
        // card-reader mode: said in the app's language before the protocol code gets to it
        if (UsbCamera.hasMassStorageOnly(device)) throw IOException(getString(R.string.ex_usb_mass_storage))
        return TransferEngine.Connector { UsbCamera.openSession(usb, device, attempts++ > 0) }
    }

    // ------------------------------------------------------------------ download

    private fun download(handles: LongArray) {
        val eng = engine
        if (eng == null) {
            setStatus(getString(R.string.st_not_connected))
            return
        }
        cancelRequested = false
        TransferState.stopAfterCurrent = false
        TransferState.busy = true
        // .part files of the old two-step save (download here, then copy): no longer used
        File(getExternalFilesDir(null), "incoming").takeIf { it.exists() }?.deleteRecursively()
        val byHandle = TransferState.objects.associateBy { it.handle }
        // The listing may hold placeholder sizes (XApp gallery mode): each file gets its real size when its turn
        // comes, with the one GetObjectInfo the download needs anyway, not with a round of requests first.
        val wanted = handles.toList().mapNotNull { byHandle[it] }
        TransferState.batchCount = wanted.size
        TransferState.batch = wanted
        TransferState.retryNote = ""
        TransferState.batchBytesTotal = wanted.sumOf { if (it.sizeOverflow()) 0L else it.size }
        TransferState.batchBytesDone = 0
        var ok = 0
        var failed = 0
        val saved = ArrayList<ObjectInfo>()
        var stoppedAfter = false
        // the camera ended the link (access point off over Bluetooth) while a file was on its way
        var droppedByCamera = false
        var droppedScreenOff = false
        var partialName = ""
        var partialPercent = 0
        if (wifiNetwork != null) DebugLog.add("  " + wifiLinkSummary())
        val batchStart = System.nanoTime()
        var batchBytes = 0L
        var batchTransfer = 0L
        // Test (4 Nov): long Bluetooth interval while the batch runs; compare the MB/s per file with the earlier logs.
        val bleDuringDownload = ble?.takeIf { it.isConnected }
        if (bleDuringDownload != null && wifiNetwork != null) {
            bleDuringDownload.lowPower()
            DebugLog.add("  Bluetooth a intervallo lungo durante il download")
        }
        try {
            for ((i, listed) in wanted.withIndex()) {
                if (cancelRequested) break
                if (TransferState.stopAfterCurrent && i > 0) {
                    stoppedAfter = true
                    DebugLog.add("  fermato dopo ${wanted[i - 1].filename}, come chiesto")
                    break
                }
                runPendingPreviews() // the viewer open during a batch: its previews between two files
                val fileStart = System.nanoTime()
                TransferState.batchIndex = i + 1
                TransferState.progressName = listed.filename
                TransferState.progressDone = 0
                TransferState.progressTotal = listed.size
                setStatus(getString(R.string.st_downloading, i + 1, wanted.size, listed.filename))
                try {
                    val info = eng.sizeForDownload(listed) // size, handle and original files in one request
                    if (info.size != listed.size || info.handle != listed.handle) {
                        TransferState.batchBytesTotal += info.size - (if (listed.sizeOverflow()) 0L else listed.size)
                        TransferState.batch = TransferState.batch.toMutableList().also { it[i] = info }
                    }
                    TransferState.progressTotal = info.size
                    saveToMediaStore(eng, info)
                    markDone(info)
                    saved.add(info)
                    TransferState.batchBytesDone += info.size
                    ok++
                    val st = eng.lastStats()
                    batchBytes += st.bytes
                    batchTransfer += st.nanos
                    logFileStats(info, st, System.nanoTime() - fileStart)
                } catch (e: TransferEngine.CancelledException) {
                    break
                } catch (e: IOException) {
                    failed++
                    setStatus("${listed.filename}: ${e.message}", error = true)
                    if (cameraWifiOff) {
                        // the camera switched its Wi-Fi off: the next files cannot come either
                        droppedByCamera = true
                        droppedScreenOff = screenOff
                        val total = TransferState.progressTotal
                        if (TransferState.progressDone > 0 && total > 0) {
                            partialName = listed.filename
                            partialPercent = (TransferState.progressDone * 100 / total).toInt().coerceIn(0, 99)
                        }
                        DebugLog.add("  lotto fermato: la camera ha spento il Wi-Fi" + if (screenOff) " (schermo del telefono spento)" else "")
                        break
                    }
                }
            }
        } finally {
            eng.galleryMode() // as XApp after an import: the listing and thumbnails go on in gallery mode
            bleDuringDownload?.relaxPriority()
            val total = System.nanoTime() - batchStart
            if (ok > 0 && total > 0) {
                DebugLog.add(String.format(Locale.ROOT,
                    "  lotto: %d file, %.1f MB in %.1f s (%.2f MB/s); trasferimento %.1f s, il resto (dimensioni, salvataggio, anteprime) %.1f s",
                    ok, batchBytes / 1e6, total / 1e9, batchBytes / 1e6 / (total / 1e9), batchTransfer / 1e9,
                    (total - batchTransfer) / 1e9))
            }
            val immichQueued = if (ok > 0) ImmichSync.afterDownload(this, saved.map { doneKey(it) }) else 0
            if (ok > 0) ImmichSync.schedule(this) // runs when the phone is back on a normal network
            val savedHandles = saved.mapTo(HashSet()) { it.handle }
            TransferState.lastBatch = BatchResult(
                id = System.nanoTime(),
                saved = saved.toList(),
                left = TransferState.batch.filter { it.handle !in savedHandles },
                failed = failed,
                cancelled = cancelRequested,
                stoppedAfterFile = stoppedAfter,
                bytes = saved.sumOf { it.size },
                millis = total / 1_000_000,
                immichQueued = immichQueued,
                cameraWifiOff = droppedByCamera,
                screenOff = droppedScreenOff,
                partialName = partialName,
                partialPercent = partialPercent,
            )
            TransferState.stopAfterCurrent = false
            TransferState.busy = false
            TransferState.progressName = ""
            val msg = when {
                cancelRequested -> getString(R.string.st_cancelled, ok)
                stoppedAfter -> getString(R.string.st_stopped, ok)
                droppedByCamera -> getString(R.string.st_dropped, ok)
                failed > 0 -> getString(R.string.st_done_errors, ok, failed)
                else -> getString(R.string.st_done, ok)
            }
            setStatus(msg, error = failed > 0, steady = !cancelRequested && !droppedByCamera && failed == 0)
            // the notification says what is left to do, in the app's words
            val onPhone = resources.getQuantityString(R.plurals.notif_on_phone, ok, ok)
            val doneTitle = resources.getQuantityString(R.plurals.notif_done_title, ok, ok)
            val (title, text) = when {
                cancelRequested -> getString(R.string.notif_cancelled) to onPhone
                droppedByCamera -> getString(R.string.drop_title) to
                    (onPhone + (if (partialName.isNotEmpty()) " " + getString(R.string.notif_partial, partialName, partialPercent) else "") +
                        " " + getString(R.string.notif_tap_resume))
                failed > 0 -> doneTitle to resources.getQuantityString(R.plurals.notif_failed, failed, failed)
                immichOffer(ok) -> doneTitle to getString(R.string.ask_send_too)
                immichQueued > 0 -> doneTitle to getString(R.string.notif_queued, immichQueued)
                else -> doneTitle to getString(R.string.notif_where, SaveFolder.describe(this))
            }
            updateNotification(text, 0, 0, force = true, title = title)
        }
    }

    /**
     * One line per downloaded file: speed of the transfer itself, how long the camera waited before each piece,
     * time spent writing, and what the file cost outside the transfer (size request, media store).
     */
    private fun logFileStats(info: ObjectInfo, st: TransferEngine.Stats, wallNanos: Long) {
        if (st.nanos <= 0 || st.bytes <= 0) return
        val sec = st.nanos / 1e9
        DebugLog.add(String.format(Locale.ROOT,
            "  %s: %.1f MB in %.1f s (%.2f MB/s); %d pezzi, attesa della camera prima dei dati %.2f s (%.0f ms a pezzo), " +
                "scrittura %.2f s; fuori dal trasferimento %.2f s%s",
            info.filename, st.bytes / 1e6, sec, st.bytes / 1e6 / sec, st.chunks, st.waitNanos / 1e9,
            if (st.chunks > 0) st.waitNanos / 1e6 / st.chunks else 0.0, st.writeNanos / 1e9,
            (wallNanos - st.nanos) / 1e9, if (st.retries > 0) ", ${st.retries} ripresa/e dopo caduta" else ""))
    }

    private fun listener() = object : TransferEngine.Listener {
        override fun onProgress(info: ObjectInfo?, done: Long, total: Long) {
            TransferState.progressDone = done
            TransferState.progressTotal = total
            val now = System.currentTimeMillis()
            if (now - lastNotification > 400) {
                TransferState.publish()
                // the bar follows the whole batch, like the download screen
                val (doneAll, all) = TransferState.batchEstimate()
                val p = if (all > 0) (doneAll * 1000 / all).toInt().coerceIn(0, 1000) else 0
                // "Download 7 di 12" / "DSCF4827.MOV · 62%", as in the design's notifications
                val filePct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                updateNotification(
                    "${info?.filename.orEmpty()} · $filePct%",
                    p, 1000, force = false,
                    title = getString(R.string.download_n_of_m, TransferState.batchIndex, TransferState.batchCount),
                )
            }
        }

        override fun onRetry(info: ObjectInfo?, attempt: Int, cause: Exception?) {
            if (info != null && TransferState.progressName.isNotEmpty()) {
                val t = TransferState.progressTotal
                val pct = if (t > 0) (TransferState.progressDone * 100 / t).toInt() else 0
                TransferState.retryNote = getString(R.string.retry_note, pct)
            }
            DebugLog.add("  caduta: ${cause?.javaClass?.simpleName}${netAge()}")
            setStatus(getString(R.string.st_unstable, attempt, cause?.message.orEmpty()))
        }

        override fun isCancelled() = cancelRequested
    }

    // ------------------------------------------------------------------ thumbnails

    /**
     * The original for the viewer, into cache/view (one file kept: the previous one is deleted). RAF and HEIF are
     * shown through what the viewer can decode; the file itself is the camera's, untouched.
     */
    private fun runPendingView(): Boolean {
        val h = pendingView.getAndSet(-1)
        if (h < 0) return false
        val eng = engine ?: return false
        val listed = TransferState.objects.firstOrNull { it.handle == h } ?: return false
        val dir = File(cacheDir, "view").apply { mkdirs() }
        try {
            // 3 Nov: three videos opened and dropped within 10 s, and the X-M5 stopped answering the next one.
            // After a transfer left half way, give the camera a moment before reading another big file.
            val since = System.currentTimeMillis() - viewCancelledAt
            if (since < 1500) {
                try {
                    Thread.sleep(1500 - since)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return true
                }
                if (TransferState.viewHandle != h || viewCancel) return true // the viewer moved on meanwhile
            }
            val info = eng.sizeForDownload(listed) // download() below reuses this request
            dir.listFiles()?.forEach { if (!it.name.startsWith(info.filename)) it.delete() }
            TransferState.viewTotal = info.size
            TransferState.publish()
            var last = 0L
            val t0 = System.currentTimeMillis()
            DebugLog.add("  originale ${info.filename}: scarico ${"%.1f".format(Locale.ROOT, info.size / 1e6)} MB")
            val file = eng.download(info, dir, object : TransferEngine.Listener {
                override fun onProgress(i: ObjectInfo?, done: Long, total: Long) {
                    TransferState.viewDone = done
                    TransferState.viewTotal = total
                    val now = System.currentTimeMillis()
                    if (now - last > 200) {
                        last = now
                        TransferState.publish()
                    }
                }

                override fun onRetry(i: ObjectInfo?, attempt: Int, cause: Exception?) {
                    DebugLog.add("  originale ${info.filename}: riprovo ($attempt) ${cause?.message.orEmpty()}")
                }

                override fun isCancelled() = viewCancel || cancelRequested || TransferState.viewHandle != h
            })
            val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
            DebugLog.add(String.format(Locale.ROOT, "  originale %s: %.1f MB in %.1f s (%.1f MB/s, %.0f Mbit/s)",
                info.filename, file.length() / 1e6, ms / 1000.0, file.length() / 1e3 / ms, file.length() * 8 / 1e3 / ms))
            if (TransferState.viewHandle == h) {
                TransferState.viewDone = TransferState.viewTotal
                TransferState.viewFile = file.absolutePath
            }
        } catch (e: TransferEngine.CancelledException) {
            // closed by the user, or another file was asked for
            viewCancelledAt = System.currentTimeMillis()
            DebugLog.add("  originale ${listed.filename}: lasciato a metà (il visualizzatore è passato ad altro)")
        } catch (e: IOException) {
            DebugLog.add("  originale ${listed.filename} non scaricato: ${e.message}")
            if (TransferState.viewHandle == h) TransferState.viewError = e.message ?: getString(R.string.download_failed)
        } finally {
            eng.galleryMode() // the listing goes on: not in the heavier original-file mode
        }
        TransferState.publish()
        return true
    }

    /**
     * Quick previews for the viewer, on the io thread between other work: the camera's size-S copy of a photo
     * (as XApp's zoom view), the JPEG inside a RAF. The photo on screen first, then its neighbours; a newer request
     * (the user swiped on) is served before the rest of the old one. A file without one is marked missing and
     * the viewer fetches its original. Stops at the first connection trouble.
     */
    private fun runPendingPreviews(): Boolean {
        var any = false
        while (true) {
            val hs = pendingPreviews.getAndSet(null) ?: return any
            val eng = engine ?: return any
            if (!TransferState.connected) return any
            val byHandle = TransferState.objects.associateBy { it.handle }
            for (h in hs) {
                if (pendingPreviews.get() != null) break // newer request: serve it first
                val info = byHandle[h] ?: continue
                if (!Previews.applies(info)) continue
                val cached = Previews.get(info)
                if (cached != null) {
                    // kept from an earlier connection: its EXIF still tells how to turn the thumbnail
                    if (Orient.known(info) == 0 && learnOrientation(info, headOf(cached))) TransferState.publish()
                    continue
                }
                val k = Previews.key(info)
                if (k in TransferState.previewMissing) continue
                if (!pacePreview()) return any
                if (pendingPreviews.get() != null) break // the user moved on while we waited
                any = true
                val raf = Previews.isRaf(info)
                // a RAF with its JPG/HEIF: the S copy of that file is the same picture, ~1.3 MB instead of ~4
                val sibling = if (raf) byHandle.values.firstOrNull { it !== info && Thumbs.key(it) == k && Previews.applies(it) && !Previews.isRaf(it) } else null
                val t0 = System.currentTimeMillis()
                var how = if (raf && sibling == null) "JPEG del RAF" else "copia S della camera"
                val data = try {
                    when {
                        !raf -> eng.smallCopy(info, null)
                        sibling != null -> eng.smallCopy(sibling, null) ?: eng.rafPreview(info, null).also { how = "JPEG del RAF" }
                        else -> eng.rafPreview(info, null)
                    }
                } catch (e: IOException) {
                    DebugLog.add("  anteprima ${info.filename} interrotta: ${e.message}")
                    return any
                } finally {
                    previewEndedAt = System.currentTimeMillis()
                }
                val ms = System.currentTimeMillis() - t0
                if (data != null && Previews.put(info, data) != null) {
                    DebugLog.add("  anteprima ${info.filename}: ${data.size / 1024} kB in $ms ms ($how" +
                        (if (sibling != null && how.startsWith("copia")) " di ${sibling.filename})" else ")"))
                    learnOrientation(info, data)
                    TransferState.previewVersion++
                } else {
                    DebugLog.add("  anteprima ${info.filename}: non disponibile, il visualizzatore scarica l'originale")
                    TransferState.previewMissing = TransferState.previewMissing + k
                }
                TransferState.publish()
            }
        }
    }

    /**
     * Reads the EXIF of the files not seen before (position, film, orientation) and keeps it in SQLite. A JPG and a RAF of the
     * same shot share it, so only one of them is read (the JPG). Paced: a few shots, then a pause, with the
     * viewer, downloads and thumbnails served first. Stops at the first connection trouble (next time it goes on
     * from there): it must never be what makes the camera drop.
     */
    private fun scanMeta(eng: TransferEngine) {
        val kinds = setOf("jpg", "jpeg", "raf")
        val known = TransferState.meta
        val shots = TransferState.objects
            .filter { it.filename.substringAfterLast('.', "").lowercase(Locale.ROOT) in kinds }
            .filter { (known[MetaStore.key(it.filename, it.captureDate)]?.orient ?: 0) == 0 } // new, or no orientation yet
            .groupBy { it.filename.substringBeforeLast('.') + "|" + it.captureDate }
            .values
            .map { files -> files.sortedBy { if (it.filename.endsWith(".RAF", ignoreCase = true)) 1 else 0 } }
        if (shots.isEmpty()) return
        TransferState.metaScanning = true
        TransferState.metaDone = 0
        TransferState.metaTotal = shots.size
        TransferState.publish()
        val t0 = System.currentTimeMillis()
        var withPosition = 0
        // Gallery mode (what XApp browses with) is ~5x lighter for the camera, but its copy of the file may
        // lose the EXIF. Check once on the first shot against the original: same film and position -> fast.
        var original = true
        try {
            val first = shots.first().first()
            val ref = eng.readExif(listOf(first), true).firstOrNull()
            val light = eng.readExif(listOf(first), false).firstOrNull()
            val same = ref != null && light != null && ref.film != null && ref.film == light.film &&
                ref.orientation == light.orientation &&
                ref.hasPosition() == light.hasPosition() &&
                (!ref.hasPosition() || (Math.abs(ref.lat - light.lat) < 1e-5 && Math.abs(ref.lon - light.lon) < 1e-5))
            original = !same
            DebugLog.add(
                "  posizioni: " + (if (same) "modo galleria (veloce), EXIF uguali all'originale"
                else "modo originale: la copia della galleria non ha gli stessi EXIF (" +
                    "originale ${ref?.film}/${ref?.hasPosition()}, galleria ${light?.film}/${light?.hasPosition()})"),
            )
        } catch (e: IOException) {
            DebugLog.add("  posizioni: prova del modo veloce non riuscita (${e.message}), uso il modo originale")
        }
        // Small chunks with a real pause: on 3 Nov the X-M5 turned its Wi-Fi off 10 s into a chunk of 12.
        val chunkSize = if (original) 3 else 4
        val pause = if (original) 800L else 500L
        var chunks = 0
        try {
            for (whole in shots.chunked(chunkSize)) {
                if (cancelRequested || engine !== eng || !TransferState.connected) break
                // shots whose thumbnail already brought the EXIF meanwhile need no read
                val chunk = whole.filter {
                    (TransferState.meta[MetaStore.key(it.first().filename, it.first().captureDate)]?.orient ?: 0) == 0
                }
                if (chunk.isEmpty()) {
                    TransferState.metaDone += whole.size
                    continue
                }
                runPendingView()
                runPendingPreviews()
                runPendingDownloads()
                quietAroundEighteen()
                try {
                    holdWhileScreenOff(eng)
                } catch (e: IOException) {
                    DebugLog.add("  posizioni: camera persa a schermo spento (${e.message}), riprendo alla prossima connessione")
                    break
                }
                drainThumbsNow()
                if (engine !== eng || !TransferState.connected) break
                val c0 = System.currentTimeMillis()
                val infos = try {
                    eng.readExif(chunk.map { it.first() }, original)
                } catch (e: IOException) {
                    if (cancelRequested || e is TransferEngine.CancelledException) {
                        DebugLog.add("  posizioni: fermate per la disconnessione, riprendo alla prossima connessione")
                        break
                    }
                    DebugLog.add(
                        "  posizioni: interrotto dopo ${System.currentTimeMillis() - c0} ms su ${chunk.size} scatti " +
                            "(${e.message}), riprendo alla prossima connessione",
                    )
                    setStatus(getString(R.string.st_link_lost))
                    break
                }
                val perShot = (System.currentTimeMillis() - c0) / chunk.size
                if (chunks++ < 3 || perShot >= 700) DebugLog.add("  posizioni: ${chunk.size} scatti, $perShot ms l'uno")

                val add = HashMap<String, Meta>()
                for ((i, files) in chunk.withIndex()) {
                    val x = infos.getOrNull(i) ?: continue
                    val m = Meta(x.lat, x.lon, x.film, if (x.orientation > 0) x.orientation else 1)
                    if (m.hasPosition) withPosition++
                    for (f in files) add[MetaStore.key(f.filename, f.captureDate)] = m
                    if (m.orient != 1) Thumbs.forget(files.first()) // decoded before its orientation was known
                }
                try {
                    metaStore.put(add)
                } catch (e: Exception) {
                    DebugLog.add("  metadati non salvati: ${e.message}")
                }
                TransferState.meta = HashMap(TransferState.meta).apply { putAll(add) }
                TransferState.metaVersion++
                TransferState.metaDone += whole.size
                TransferState.publish()
                if (perShot >= 2000) {
                    // the camera is struggling: stop before it gives up on the connection
                    DebugLog.add("  posizioni: la camera rallenta, rimando il resto alla prossima connessione")
                    break
                }
                Thread.sleep(pause) // keep the camera relaxed (see the listing in gallery mode)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            TransferState.metaScanning = false
            TransferState.publish()
            DebugLog.add(
                "  posizioni: ${TransferState.metaDone} scatti letti su ${shots.size}, $withPosition con GPS, " +
                    "${(System.currentTimeMillis() - t0) / 1000} s",
            )
        }
    }

    @Volatile private var thumbExif: Boolean? = null // learnt on the first thumbnail of the connection

    /**
     * If the camera's thumbnail is a JPEG with the EXIF (position, film), the shot needs no separate read: kept
     * like the background scan does. Learnt once per connection, logged.
     */
    private fun metaFromThumb(info: ObjectInfo, bytes: ByteArray?) {
        if (bytes == null || thumbExif == false) return
        val x = com.fujixfer.core.Exif.parse(bytes)
        val useful = x.film != null || x.hasPosition()
        if (thumbExif == null) {
            thumbExif = useful
            DebugLog.add("  miniature " + (if (useful) "con EXIF: posizione e pellicola arrivano con loro" else "senza EXIF"))
        }
        if (!useful) return
        val base = info.filename.substringBeforeLast('.') + "|" + info.captureDate
        val add = HashMap<String, Meta>()
        for (o in TransferState.objects) {
            if (o.filename.substringBeforeLast('.') + "|" + o.captureDate != base) continue
            val k = MetaStore.key(o.filename, o.captureDate)
            // a thumbnail's EXIF may lack the orientation: keep what is known (0 = the scan reads it)
            val orient = if (x.orientation > 0) x.orientation else TransferState.meta[k]?.orient ?: 0
            add[k] = Meta(x.lat, x.lon, x.film, orient)
        }
        try {
            metaStore.put(add)
        } catch (e: Exception) {
            return
        }
        TransferState.meta = HashMap(TransferState.meta).apply { putAll(add) }
        TransferState.metaVersion++
    }

    /** Runs on the io thread between other work: a few thumbnails at a time, newest requests first. */
    private fun runPendingDownloads(): Boolean {
        var any = false
        while (true) {
            val h = pendingDownloads.poll() ?: return any
            any = true
            download(h)
        }
    }

    private fun scheduleThumbs() {
        if (listing) return // served by the listing between two groups
        if (engine == null || !thumbScheduled.compareAndSet(false, true)) return
        try {
            io.execute {
                thumbScheduled.set(false)
                drainThumbs()
            }
        } catch (e: RejectedExecutionException) {
            thumbScheduled.set(false)
        }
    }

    private fun drainThumbs() {
        drainThumbsNow()
        // after a failure, wait for the next request or download instead of retrying in a tight loop
        if (Thumbs.hasRequests() && System.currentTimeMillis() - thumbFailedAt > 2000) scheduleThumbs()
    }

    @Volatile private var thumbFailedAt = 0L

    /** Fetches up to 8 requested thumbnails on the calling thread; true if it asked the camera for any. */
    private fun drainThumbsNow(): Boolean {
        val eng = engine ?: return false
        if (!TransferState.connected) return false
        var any = false
        val preferFuji = TransferState.connectionLabel != "USB" // XApp uses 0x9055 over Wi-Fi, GetThumb over USB
        for (n in 0 until 8) {
            val info = Thumbs.next() ?: return any
            any = true
            val t0 = System.currentTimeMillis()
            try {
                val bytes = eng.thumbnail(info, preferFuji)
                Thumbs.deliver(info, bytes)
                metaFromThumb(info, bytes)
                if (listing) DebugLog.add("  miniatura ${info.filename}: ${System.currentTimeMillis() - t0} ms")
            } catch (e: IOException) {
                DebugLog.add("  miniatura ${info.filename} fallita dopo ${System.currentTimeMillis() - t0} ms: ${e.message}")
                Thumbs.failed(info)
                thumbFailedAt = System.currentTimeMillis()
                return any // connection trouble: the next download or scroll will retry
            }
        }
        return any
    }

    // Name + date: the listing may carry placeholder sizes (XApp gallery mode), so the size is left out.
    private fun doneKey(i: ObjectInfo) = "${i.filename}|${i.captureDate}"

    private fun markDone(i: ObjectInfo) {
        val set = prefs.getStringSet("done", emptySet())!!.toMutableSet()
        set.add(doneKey(i))
        prefs.edit().putStringSet("done", set).apply()
        TransferState.doneVersion++
    }

    /**
     * With the screen off the listing, thumbnails and background scan wait, on the io thread between two groups.
     * 3 Nov, 16:41 and 16:42: listing with the screen off, the camera slowed from 110 to 400+ ms a file and once
     * turned its Wi-Fi off 16 s after the screen went off (Android's low-latency Wi-Fi lock works only in the
     * foreground). Downloads asked by the user still run. Every 4 s the camera's event list is read, so the
     * session is never silent for long; an IOException from that is thrown to the caller (connection lost).
     */
    private fun holdWhileScreenOff(eng: TransferEngine) {
        if (!screenOff || cancelRequested || engine !== eng) return
        val fuji = eng.session().fujiWifi() ?: return // USB: nothing to protect
        val started = System.currentTimeMillis()
        DebugLog.add("  elenco in pausa: schermo spento (download chiesti e segnale alla camera ogni 4 s continuano)")
        setStatus(getString(R.string.st_paused_screen_off))
        var lastPoll = started
        var polls = 0
        while (screenOff && !cancelRequested && engine === eng) {
            if (runPendingDownloads()) lastPoll = System.currentTimeMillis() // the download kept the session busy
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (System.currentTimeMillis() - lastPoll >= 4000) {
                lastPoll = System.currentTimeMillis()
                fuji.pollEvents()
                polls++
            }
        }
        DebugLog.add("  elenco ripreso dopo ${(System.currentTimeMillis() - started) / 1000} s a schermo spento, " +
            "$polls segnali alla camera")
        if (!cancelRequested) setStatus(getString(if (listing) R.string.st_listing else R.string.st_connected), steady = true)
    }

    /** The Wi-Fi join (DebugLog.wifiJoinedAt) whose quiet window was already logged. */
    private var quietLoggedFor = 0L

    /**
     * EXPERIMENT. Every log of 3–4 Nov shows the link stalling about 18 s after the phone joins the camera's
     * Wi-Fi (13:44: a 64 KiB read took 4.3 s across w+18; 13:57: 1 MiB pieces of 1.1 and 0.9 s at w+17.9 and
     * w+18.8, against 0.3 s around them), and three times (16:41, 16:53, 23:13) Android then dropped the socket and
     * reset the address, and the camera turned its Wi-Fi off. Cause unknown. Background work (listing, thumbnails,
     * EXIF scan, previews) waits from w+15.5 to w+21, so nothing of ours is in flight then; downloads asked by the
     * user are not held. The log says whether drops still come.
     */
    private fun quietAroundEighteen() {
        val joined = DebugLog.wifiJoinedAt
        if (joined <= 0) return
        val age = System.currentTimeMillis() - joined
        if (age < 15_500 || age >= 21_000) return
        if (quietLoggedFor != joined) {
            quietLoggedFor = joined
            DebugLog.add("  pausa di prova attorno ai 18 s dall'ingresso nel Wi-Fi della camera (fino a 21 s)")
        }
        try {
            Thread.sleep(21_000 - age)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Keeps the camera relaxed between previews. 3 Nov, 16:41: six previews back to back (8.5 s, ~17 MB, the
     * camera's mode switched at each) and the X-M5 dropped its Wi-Fi. So: 0.7 s between two previews, and 2.5 s
     * after three close together (like the background scan's pauses). False if interrupted.
     */
    private fun pacePreview(): Boolean {
        quietAroundEighteen()
        val since = System.currentTimeMillis() - previewEndedAt
        if (since > 4000) previewBurst = 0
        val need = if (previewBurst >= 3) 2500L else 700L
        if (since < need) {
            DebugLog.detail("anteprime: attendo ${need - since} ms (ritmo per la camera)")
            try {
                Thread.sleep(need - since)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        previewBurst = if (previewBurst >= 3) 1 else previewBurst + 1
        return engine != null && TransferState.connected
    }

    /**
     * The orientation in a preview's EXIF, for a shot the background scan has not read yet: the thumbnails of
     * the shot (grid, viewer strip) are decoded again, upright. True if it was new.
     */
    private fun learnOrientation(info: ObjectInfo, jpeg: ByteArray): Boolean {
        if (!Orient.learn(info, jpeg)) return false
        if (Orient.known(info) != 1) {
            Thumbs.forget(info)
            Thumbs.onLoaded?.let { cb -> android.os.Handler(mainLooper).post { cb() } } // cells and strip ask again
        }
        return true
    }

    /** First 64 KiB of a file: where a JPEG keeps its EXIF. */
    private fun headOf(f: File): ByteArray = try {
        f.inputStream().use { input ->
            val b = ByteArray(64 * 1024)
            var n = 0
            while (n < b.size) {
                val r = input.read(b, n, b.size - n)
                if (r < 0) break
                n += r
            }
            b.copyOf(n)
        }
    } catch (e: IOException) {
        ByteArray(0)
    }

    /**
     * A new pending item for [info] in the folder chosen in the settings. A folder the media store refuses for
     * this kind of file (a video in Pictures on Android 10, say) falls back to the folder for its type.
     */
    private fun insertItem(info: ObjectInfo): android.net.Uri {
        for (t in SaveFolder.targets(this, info.filename)) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, info.filename)
                put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, t.folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                parseDate(info.captureDate)?.let { put(MediaStore.MediaColumns.DATE_MODIFIED, it / 1000) }
            }
            val uri = try {
                contentResolver.insert(t.collection, values)
            } catch (e: IllegalArgumentException) {
                DebugLog.add("  ${info.filename}: ${t.folder} non accetta il file (${e.message}), uso la cartella del tipo")
                null
            }
            if (uri != null) {
                SaveFolder.used(this, t.folder)
                return uri
            }
        }
        throw IOException(getString(R.string.ex_mediastore, info.filename))
    }

    /**
     * Downloads {@code info} straight into a pending item of the media store: written once, no copy in the app's
     * folder first. An interrupted download keeps its item (pending, invisible to other apps; Android removes
     * those left over after about a week) and the next attempt resumes into it from where it stopped.
     */
    private fun saveToMediaStore(eng: TransferEngine, info: ObjectInfo) {
        val resolver = contentResolver
        val key = "${info.filename}|${info.captureDate}|${info.size}"
        var uri = pendingItem(key)?.takeIf { usable(it) }
        if (uri != null) {
            DebugLog.add("  ${info.filename}: riprendo nel file già iniziato")
        } else {
            uri = insertItem(info)
            rememberPending(key, uri)
        }
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException(getString(R.string.ex_cannot_open))
            // AutoCloseOutputStream owns the descriptor: closing the stream closes it, once
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out -> eng.download(info, out.channel, listener()) }
        } catch (e: IOException) {
            throw e // camera or connection: the item stays for the next attempt
        } catch (e: Exception) {
            forgetPending(key)
            resolver.delete(uri, null, null)
            throw IOException(getString(R.string.ex_save_failed, e.message.orEmpty()))
        }
        try {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            throw IOException(getString(R.string.ex_save_failed, e.message.orEmpty()))
        }
        forgetPending(key)
        ImmichSync.rememberMedia(this, doneKey(info), uri) // so "Carica" knows which item is this camera file
    }

    /** Media store items started and not finished, "name|date|size" -> uri. */
    private fun pendingItem(key: String): android.net.Uri? =
        prefs.getStringSet(PREF_PENDING_ITEMS, emptySet())!!
            .firstOrNull { it.substringBefore('\t') == key }
            ?.substringAfter('\t')?.let { android.net.Uri.parse(it) }

    private fun rememberPending(key: String, uri: android.net.Uri) {
        val set = prefs.getStringSet(PREF_PENDING_ITEMS, emptySet())!!.filterTo(HashSet()) { it.substringBefore('\t') != key }
        set.add(key + "\t" + uri)
        prefs.edit().putStringSet(PREF_PENDING_ITEMS, set).apply()
    }

    private fun forgetPending(key: String) {
        val set = prefs.getStringSet(PREF_PENDING_ITEMS, emptySet())!!.filterTo(HashSet()) { it.substringBefore('\t') != key }
        prefs.edit().putStringSet(PREF_PENDING_ITEMS, set).apply()
    }

    /** The item still exists and is ours (Android may have removed an old pending item). */
    private fun usable(uri: android.net.Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "rw")?.close() != null
    } catch (e: Exception) {
        false
    }

    private fun parseDate(s: String): Long? = try {
        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(s.take(15))?.time
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ lifecycle helpers

    private fun disconnect() {
        val wasOnWifi = engine != null && wifiNetwork != null
        closeSessionFirst()
        if (wasOnWifi) tellCameraUserStopped()
        teardown()
        TransferState.objects = emptyList()
        TransferState.connected = false
        TransferState.busy = false
        setStatus(getString(R.string.st_not_connected))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * On "Disconnetti": CloseSession to the camera while its Wi-Fi is still there, then the rest of the teardown.
     * 4 Nov, 13:58:50: the network was released first, CloseSession failed (connection abort), and 11 s later the
     * phone could not join the camera's Wi-Fi again (rejected 8 times); at 13:46:55 CloseSession went through.
     * CloseSession waits for the command in flight (a 2 MiB block, a preview). If that takes more than 1.5 s, the
     * socket is closed under it while the camera's network is still up: before, the network went first and the
     * socket's close never reached the camera, which then kept its connection screen for 1–2 minutes (its own
     * timeout), while XApp's never stays. 13 Nov, 20:51: the position scan (~4 s a shot on the X-M5) still held the
     * session after 1.5 s, so the scan is now stopped first, before its next command.
     */
    private fun closeSessionFirst() {
        val eng = engine ?: return
        eng.stopReads() // the position scan would otherwise hold the session for its whole group of shots
        val t = Thread({ eng.close() }, "fuji-close")
        t.start()
        t.join(1500)
        if (!t.isAlive) {
            DebugLog.detail("sessione chiusa con la camera prima di lasciare la sua rete")
            return
        }
        eng.abort()
        t.join(500)
        Thread.sleep(200) // lets the close go out before Android drops the camera's network
        DebugLog.add("CloseSession non completato in 1,5 s: chiuso il socket con la rete ancora attiva")
    }

    /** Over the Bluetooth link still open, as XApp does when the person stops; at most 2 s. */
    private fun tellCameraUserStopped() {
        val link = ble ?: return
        val red = blePairing?.red ?: return
        val t = Thread({ FujiBle.notifyUserStop(link, red, bleLog) }, "fuji-ble-stop")
        t.start()
        t.join(2000)
    }

    private fun teardown() {
        DebugLog.detail("chiusura della connessione (teardown)")
        DebugLog.wifiJoinedAt = 0L
        joining?.completeExceptionally(IOException(getString(R.string.ex_cancelled)))
        unregisterNetwork()
        closeBle()
        engine?.close() // last: it waits for a connection in progress, which the steps above make fail
        engine = null
        blePairing = null
        releaseLocks()
        TransferState.connected = false
    }

    @SuppressLint("WakelockTimeout", "WifiManagerLeak")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fujixfer:transfer").apply { acquire() }
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fujixfer:wifi").apply { acquire() }
        }
        // Low latency turns the Wi-Fi power save off, but Android applies it only while the app is in the
        // foreground with the screen on; HIGH_PERF above is deprecated and may do nothing on recent Android.
        // Neither is known to help with the screen off: the log says which locks are held, not that they work.
        if (wifiLowLatency == null) {
            wifiLowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "fujixfer:wifi-ll").apply { acquire() }
            DebugLog.add("  blocchi Wi-Fi presi: alte prestazioni, bassa latenza")
        }
    }

    /**
     * What Android says about this app and energy, as reported by the system (it does not cover vendor
     * features such as ColorOS's own background rules, which have no public API).
     */
    private fun energyState(): String {
        val pm = getSystemService(PowerManager::class.java)
        val am = getSystemService(android.app.ActivityManager::class.java)
        val cm = getSystemService(ConnectivityManager::class.java)
        val battery = if (pm.isIgnoringBatteryOptimizations(packageName)) "esclusa" else "attiva"
        val saver = if (pm.isPowerSaveMode) "acceso" else "spento"
        val restricted = if (am.isBackgroundRestricted) "sì" else "no"
        val data = when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "acceso"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "acceso, app esclusa"
            else -> "spento"
        }
        return "energia: ottimizzazione batteria $battery, risparmio energetico $saver, " +
            "app limitata in background $restricted, risparmio dati $data"
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wifiLowLatency?.let { if (it.isHeld) it.release() }
        wifiLowLatency = null
    }

    /** "Chiedi a fine download": the app asks about Immich when opened after the batch. */
    private fun immichOffer(ok: Int): Boolean {
        val im = ImmichSync.settings(this)
        return ok > 0 && im.configured && im.mode == ImmichSync.MODE_OFFER
    }

    /** [steady]: a state the gallery shows quietly ("Connesso", "Finito"), not as a passing line under the bar. */
    private fun setStatus(s: String, error: Boolean = false, steady: Boolean = false) {
        TransferState.statusSteady = steady
        DebugLog.add((if (error) "ERRORE " else "» ") + s)
        TransferState.statusIsError = error
        TransferState.status = s
        TransferState.publish()
    }

    private fun foreground(text: String) {
        val n = buildNotification(text, 0, 0)
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun buildNotification(text: String, progress: Int, max: Int, title: String = "Fuji Transfer"): Notification {
        val downloading = TransferState.busy && TransferState.progressName.isNotEmpty()
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SHOW_DOWNLOADS, downloading)
        val open = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.cancel), cancel,
                ).build())
            .apply { if (max > 0) setProgress(max, progress, false) }
            .build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int, force: Boolean, title: String = "Fuji Transfer") {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotification < 400) return
        lastNotification = now
        nm.notify(NOTIF_ID, buildNotification(text, progress, max, title))
    }
}
