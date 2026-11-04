package com.fujixfer.app

import android.app.Dialog
import android.location.Geocoder
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fujixfer.core.ObjectInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Filtra per luogo", as in the design (MapFilter): the photos with a position grouped into zones on an
 * OpenStreetMap map (MapLibre + OpenFreeMap); tap zones to select them (several at once), the places outside
 * the visible area as chips, the photos without a position as a switch, then "Mostra N foto".
 *
 * A Dialog holding one Compose screen; the map itself is a MapView inside AndroidView, the zones are Compose
 * circles drawn over it and moved from the map's camera.
 *
 * @param onApply keys to show (null = remove the place filter), the label for the gallery chip, and for each
 *        key the name of its place (for the day headers).
 */
class MapScreen(
    private val activity: ComponentActivity,
    private val initial: Set<String>?,
    private val onApply: (keys: Set<String>?, label: String, places: Map<String, String>) -> Unit,
) {
    /**
     * A group of photos drawn as one circle. [splittable]: its photos are far enough apart to become several
     * circles when zoomed in (a tap zooms); otherwise it is one spot (a tap selects it). [fine]: named by
     * neighbourhood (the zones on the map), not by town (the chips of the places outside).
     */
    private class Zone(
        val keys: List<String>, val lat: Double, val lon: Double,
        val splittable: Boolean, val fine: Boolean, val isNew: Boolean,
    ) {
        val id: String = keys.first() + "#" + keys.size
        val cell: String = String.format(Locale.ROOT, if (fine) "%.3f,%.3f" else "%.1f,%.1f", lat, lon) + (if (fine) "f" else "c")
    }

    /** The selection sheet: open (everything), compact (summary + button) or hidden (just its handle). */
    private enum class SheetState { OPEN, COMPACT, HIDDEN }

    private val d = Dialog(activity, R.style.PreviewDialog)
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null

    // what the screen shows
    private var zones by mutableStateOf<List<Zone>>(emptyList())
    private var outside by mutableStateOf<List<Zone>>(emptyList())
    /** Screen position (px, in the map) of each zone, by id: follows the map's camera. */
    private var zonePos by mutableStateOf<Map<String, Offset>>(emptyMap())
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private var noGps by mutableStateOf(false)
    private var placeNames by mutableStateOf<Map<String, String>>(emptyMap())
    /** Shown over the map when no photo has a position; empty = hidden. */
    private var emptyNote by mutableStateOf("")

    // the sheet: its height never changes, the three states are only positions
    private var sheetState = SheetState.OPEN
    private var sheetOffset by mutableFloatStateOf(0f)
    private var sheetH by mutableIntStateOf(0)
    private var bodyH by mutableIntStateOf(0)
    private var headerH = 0
    private var sheetDragging = false
    private var sheetJob: Job? = null
    private var sheetScope: CoroutineScope? = null
    private var mapW = 0
    private var mapH = 0

    private var shownVersion = -1
    private var framed = false
    var closed = false
        private set

    private val geo = Executors.newSingleThreadExecutor()
    private val requested = HashSet<String>()
    private val density = activity.resources.displayMetrics.density

    fun open() {
        MapLibre.getInstance(activity)
        val view = androidx.compose.ui.platform.ComposeView(activity)
        d.setContentView(view)
        d.composeOwners(activity)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.statusBarColor = activity.getColor(R.color.md_background)
        initial?.let { keys ->
            val withPos = points().map { it.first }.toSet()
            selected = keys.filter { it in withPos }.toSet()
            noGps = keys.any { it !in withPos }
        }
        view.setContent { FujiTheme { Content() } }
        d.setOnDismissListener {
            closed = true
            geo.shutdownNow()
            mapView?.let {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }
        }
        d.show()
    }

    /** New positions from the background scan: zones and counts follow. */
    fun update() {
        if (closed || TransferState.metaVersion == shownVersion) return
        shownVersion = TransferState.metaVersion
        if (!framed) frameAll()
        rebuildZones()
    }

    // ------------------------------------------------------------------ data

    private fun points(): List<Pair<String, Meta>> {
        val meta = TransferState.meta
        return TransferState.objects.mapNotNull { o ->
            val k = MetaStore.key(o.filename, o.captureDate)
            meta[k]?.takeIf { it.hasPosition }?.let { k to it }
        }
    }

    private fun noGpsKeys(): Set<String> {
        val meta = TransferState.meta
        return TransferState.objects.map { MetaStore.key(it.filename, it.captureDate) }
            .filter { meta[it]?.hasPosition != true }.toSet()
    }

    private fun nameOf(z: Zone): String = placeNames[z.cell] ?: ""

    // ------------------------------------------------------------------ zones

    /**
     * Groups the photos on screen into zones about 56 dp apart (closer ones merge), like the design's circles;
     * the ones off screen into places of about 10 km for the "Fuori da questa zona" chips.
     */
    private fun rebuildZones() {
        val m = map ?: return
        val pts = points()
        emptyNote = if (pts.isNotEmpty()) "" else if (TransferState.metaScanning) {
            "Sto leggendo le posizioni dalle foto (${TransferState.metaDone} di ${TransferState.metaTotal})…"
        } else {
            "Nessuna foto con posizione. La X-M5 non ha un GPS: la posizione c'è solo nelle foto scattate " +
                "mentre il telefono gliela mandava via Bluetooth."
        }
        // "on screen" = in the part of the map the sheet leaves visible
        val visibleBottom = (mapH - covered()).toFloat()
        val radius = 56 * density
        val inside = ArrayList<Pair<String, Meta>>()
        val out = HashMap<String, MutableList<Pair<String, Meta>>>()
        for (p in pts) {
            val sp = m.projection.toScreenLocation(LatLng(p.second.lat, p.second.lon))
            if (sp.x >= 0 && sp.x <= mapW && sp.y >= 0 && sp.y <= visibleBottom) inside.add(p)
            else out.getOrPut(String.format(Locale.ROOT, "%.1f,%.1f", p.second.lat, p.second.lon)) { ArrayList() }.add(p)
        }
        // greedy screen-space grouping
        class Acc(var x: Float, var y: Float) {
            val members = ArrayList<Pair<String, Meta>>()
            var minX = x; var maxX = x; var minY = y; var maxY = y
        }
        val acc = ArrayList<Acc>()
        for (p in inside) {
            val s = m.projection.toScreenLocation(LatLng(p.second.lat, p.second.lon))
            val hit = acc.firstOrNull { Math.hypot((it.x - s.x).toDouble(), (it.y - s.y).toDouble()) < radius }
            if (hit != null) {
                val n = hit.members.size
                hit.x = (hit.x * n + s.x) / (n + 1)
                hit.y = (hit.y * n + s.y) / (n + 1)
                hit.members.add(p)
                hit.minX = minOf(hit.minX, s.x); hit.maxX = maxOf(hit.maxX, s.x)
                hit.minY = minOf(hit.minY, s.y); hit.maxY = maxOf(hit.maxY, s.y)
            } else {
                acc.add(Acc(s.x, s.y).also { it.members.add(p) })
            }
        }
        // how much bigger the spread of a group gets at the deepest zoom: past one circle's reach, it splits
        val grow = Math.pow(2.0, MAX_ZOOM - m.cameraPosition.zoom)
        val before = zones.map { it.keys.toSet() }.toSet()
        val newZones = acc.map { a ->
            val spread = Math.hypot((a.maxX - a.minX).toDouble(), (a.maxY - a.minY).toDouble()) * grow
            val keys = a.members.map { it.first }
            Zone(
                keys, a.members.map { it.second.lat }.average(), a.members.map { it.second.lon }.average(),
                splittable = spread >= radius * 0.75, fine = true, isNew = keys.toSet() !in before,
            )
        }
        val newOutside = out.values.sortedByDescending { it.size }.take(8).map { g ->
            Zone(
                g.map { it.first }, g.map { it.second.lat }.average(), g.map { it.second.lon }.average(),
                splittable = false, fine = false, isNew = false,
            )
        }
        zones = newZones
        outside = newOutside
        for (z in newZones + newOutside) requestName(z)
        placeZones()
    }

    /** Keeps the circles on their spot while the map moves. */
    private fun placeZones() {
        val m = map ?: return
        zonePos = zones.associate { z ->
            val p = m.projection.toScreenLocation(LatLng(z.lat, z.lon))
            z.id to Offset(p.x, p.y)
        }
    }

    /**
     * Tap on a circle. A group that splits: the map zooms onto its photos (Google Maps style), the circles that
     * appear can be tapped again. A single spot (or a group that cannot split any more): select / deselect it.
     */
    private fun tapZone(z: Zone) {
        val m = map ?: return
        if (!z.splittable || m.cameraPosition.zoom >= MAX_ZOOM - 0.1) {
            toggle(z.keys)
            return
        }
        val meta = TransferState.meta
        val ll = z.keys.mapNotNull { meta[it]?.takeIf { x -> x.hasPosition } }.map { LatLng(it.lat, it.lon) }
        if (ll.size < 2) {
            toggle(z.keys)
            return
        }
        val b = LatLngBounds.Builder()
        ll.forEach { b.include(it) }
        val pad = (88 * density).toInt()
        val top = pad + (56 * density).toInt() // below the search bar
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), pad, top, pad, pad + covered()), 450)
    }

    private fun toggle(keys: List<String>) {
        selected = if (selected.containsAll(keys)) selected - keys.toSet() else selected + keys
    }

    /** Names of the zones and places fully selected, map first. */
    private fun pickedNames(): List<String> =
        (zones + outside).filter { it.keys.isNotEmpty() && selected.containsAll(it.keys) }
            .map { nameOf(it).ifEmpty { "Zona" } }.distinct()

    private fun apply() {
        if (selected.isEmpty() && !noGps) {
            d.dismiss()
            onApply(null, "", emptyMap())
            return
        }
        val keys = HashSet(selected)
        if (noGps) keys.addAll(noGpsKeys())
        val places = HashMap<String, String>()
        for (z in zones + outside) {
            val name = nameOf(z)
            if (name.isNotEmpty()) for (k in z.keys) if (k in selected) places[k] = name
        }
        val picked = pickedNames()
        val label = when {
            picked.isEmpty() -> "Luogo · ${keys.size}"
            picked.size <= 2 -> picked.joinToString(", ")
            else -> picked.take(2).joinToString(", ") + " +${picked.size - 2}"
        }
        d.dismiss()
        onApply(keys, label, places)
    }

    // ------------------------------------------------------------------ places

    /**
     * Place name for a zone from Android's geocoder (neighbourhood on the map, town for the far ones), fetched in
     * the background and cached; empty until known. The phone reaches the internet over mobile data even while
     * it is on the camera's Wi-Fi.
     */
    private fun requestName(z: Zone) {
        if (placeNames.containsKey(z.cell) || !requested.add(z.cell)) return
        if (!Geocoder.isPresent()) return
        try {
            geo.execute {
                val name = try {
                    @Suppress("DEPRECATION")
                    val a = Geocoder(activity, Locale.getDefault()).getFromLocation(z.lat, z.lon, 1)?.firstOrNull()
                    if (z.fine) a?.subLocality ?: a?.locality ?: a?.subAdminArea else a?.locality ?: a?.subAdminArea ?: a?.adminArea
                } catch (e: Exception) {
                    null
                }
                if (name != null) {
                    activity.runOnUiThread {
                        if (!closed) placeNames = placeNames + (z.cell to name)
                    }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // closed
        }
    }

    private fun searchPlace(q: String) {
        if (q.isBlank()) return
        try {
            geo.execute {
                val a = try {
                    @Suppress("DEPRECATION")
                    Geocoder(activity, Locale.getDefault()).getFromLocationName(q, 1)?.firstOrNull()
                } catch (e: Exception) {
                    null
                }
                activity.runOnUiThread {
                    if (closed) return@runOnUiThread
                    if (a == null) {
                        Toast.makeText(activity, "Luogo non trovato", Toast.LENGTH_SHORT).show()
                    } else {
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(a.latitude, a.longitude), 12.0))
                    }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // closed
        }
    }

    /** All photos with a position on screen. */
    private fun frameAll() {
        val m = map ?: return
        val pts = points()
        if (pts.isEmpty()) return
        framed = true
        val first = pts[0].second
        if (pts.all { it.second.lat == first.lat && it.second.lon == first.lon }) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.lat, first.lon), 13.0))
            return
        }
        val b = LatLngBounds.Builder()
        for ((_, meta) in pts) b.include(LatLng(meta.lat, meta.lon))
        val pad = (72 * density).toInt()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), pad, pad, pad, pad + covered()))
    }

    // ------------------------------------------------------------------ sheet

    /** Height of the map hidden behind the sheet right now (follows drags and animations). */
    private fun covered(): Int =
        if (sheetH == 0 || mapH == 0) 0 else (sheetH - sheetOffset).coerceIn(0f, mapH.toFloat()).toInt()

    /**
     * Where the sheet sits for a state: it never changes height, only moves down. Open = all of it; compact =
     * the details below the button out of sight; hidden = only the handle strip.
     */
    private fun offsetFor(state: SheetState): Float = when (state) {
        SheetState.OPEN -> 0f
        SheetState.COMPACT -> bodyH.toFloat()
        SheetState.HIDDEN -> (sheetH - 36 * density).coerceAtLeast(0f)
    }

    private fun snapTo(target: SheetState, animated: Boolean = true) {
        sheetState = target
        sheetJob?.cancel()
        val to = offsetFor(target)
        val from = sheetOffset
        if (!animated || abs(to - from) < 1f) {
            sheetOffset = to
            return
        }
        sheetJob = sheetScope?.launch {
            animate(from, to, animationSpec = tween(300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { v, _ -> sheetOffset = v }
            // what counts as "outside this zone" depends on what the sheet covers
            if (!closed) rebuildZones()
        }
    }

    // ------------------------------------------------------------------ screen

    @Composable
    private fun Content() {
        val c = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
            // map area: from under the top bar to the bottom; the sheet lies over it
            Box(
                Modifier.fillMaxSize().padding(top = 64.dp).onSizeChanged {
                    mapW = it.width
                    mapH = it.height
                },
            ) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> createMapView(ctx) })
                ZoneLayer()
                SearchBar(Modifier.align(Alignment.TopCenter))
                MapControls(Modifier.align(Alignment.TopEnd))
                if (emptyNote.isNotEmpty()) {
                    Text(
                        emptyNote,
                        Modifier.align(Alignment.Center).padding(horizontal = 32.dp).offset { IntOffset(0, -covered() / 2) }
                            .clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp),
                        color = c.onSurface, textAlign = TextAlign.Center, style = type(14, 20),
                    )
                }
                // the attribution stays above the sheet
                Text(
                    "© OpenStreetMap contributors · OpenFreeMap",
                    Modifier.align(Alignment.BottomEnd).padding(bottom = 10.dp).offset { IntOffset(0, -covered()) }
                        .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)).background(Color(0xCCFFFFFF))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color(0xFF333333), style = type(10, 14),
                )
            }
            // top app bar
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(c.background).padding(start = 4.dp, end = 8.dp).align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { d.dismiss() }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Chiudi", tint = c.onSurface)
                }
                Text("Filtra per luogo", Modifier.weight(1f).padding(start = 4.dp), color = c.onSurface, style = type(22, 28))
                FujiButton("Azzera", {
                    selected = emptySet()
                    noGps = false
                }, kind = ButtonKind.Text, height = 40.dp)
            }
            SheetLayer(Modifier.align(Alignment.BottomCenter))
        }
    }

    private fun createMapView(ctx: android.content.Context): MapView {
        val mv = MapView(ctx)
        mapView = mv
        mv.onCreate(null)
        mv.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false // the design's own "© OpenStreetMap" label is shown
            m.setMaxZoomPreference(MAX_ZOOM)
            m.setStyle(Style.Builder().fromUri(STYLE)) { style ->
                MapLook.apply(activity, style) // Google Maps colours on the OpenStreetMap data
                frameAll()
                rebuildZones()
            }
            m.addOnCameraMoveListener { placeZones() }
            m.addOnCameraIdleListener { rebuildZones() }
        }
        mv.onStart()
        mv.onResume()
        return mv
    }

    @Composable
    private fun ZoneLayer() {
        Box(Modifier.fillMaxSize()) {
            for (z in zones) key(z.id) { ZoneCircle(z) }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ZoneCircle(z: Zone) {
        val c = MaterialTheme.colorScheme
        val haptic = LocalHapticFeedback.current
        val n = z.keys.size
        val size = (if (n >= 100) 48 else if (n >= 10) 42 else 36).dp
        val all = selected.containsAll(z.keys)
        val some = !all && z.keys.any { it in selected }
        // the circles that appear after a zoom pop in, the ones already there stay still
        val pop = remember { Animatable(if (z.isNew) 0.4f else 1f) }
        LaunchedEffect(Unit) {
            if (z.isNew) pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
        val fill = if (all) c.primary else if (some) c.primaryContainer else c.background
        val stroke = if (all) c.onPrimary else c.primary
        val text = if (all) c.onPrimary else if (some) c.onPrimaryContainer else c.primary
        val what = if (z.splittable) "tocca per ingrandire, tieni premuto per sceglierle tutte" else "tocca per scegliere"
        val description = "${nameOf(z).ifEmpty { "Zona" }}, $n foto, " + (if (all) "selezionata" else if (some) "in parte selezionata" else what)
        Box(
            Modifier.offset {
                val p = zonePos[z.id]
                if (p == null) IntOffset(-100000, -100000)
                else IntOffset((p.x - 56.dp.toPx()).roundToInt(), (p.y - 56.dp.toPx()).roundToInt())
            }.size(112.dp),
            contentAlignment = Alignment.Center,
        ) {
            // selected: a soft halo around the circle
            if (all) Box(Modifier.size(size + 28.dp).background(c.primary.copy(alpha = 0.14f), CircleShape))
            // a group that splits when zoomed: a second ring, like a stack of photos
            if (z.splittable) {
                Box(Modifier.size(size + 10.dp).background(c.background.copy(alpha = 0.8f), CircleShape).border(1.5.dp, c.primary, CircleShape))
            }
            Box(
                Modifier.size(size).graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    alpha = ((pop.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                }.shadow(3.dp, CircleShape).background(fill, CircleShape).border(2.dp, stroke, CircleShape).clip(CircleShape)
                    .combinedClickable(
                        onClick = { tapZone(z) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            toggle(z.keys)
                        },
                    ).semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (n >= 1000) "${n / 1000}k" else n.toString(), color = text,
                    style = type(if (n >= 100) 13 else 14, 20, medium = true),
                )
            }
        }
    }

    @Composable
    private fun SearchBar(modifier: Modifier) {
        val c = MaterialTheme.colorScheme
        val focus = LocalFocusManager.current
        var query by remember { mutableStateOf("") }
        Row(
            modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).fillMaxWidth().height(56.dp)
                .background(c.surfaceContainerHigh, RoundedCornerShape(28.dp)).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(24.dp), tint = c.onSurfaceVariant)
            BasicTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                singleLine = true,
                textStyle = type(16, 24).copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    searchPlace(query)
                    focus.clearFocus()
                }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Cerca un luogo", color = c.onSurfaceVariant, style = type(16, 24), maxLines = 1)
                        inner()
                    }
                },
            )
        }
    }

    @Composable
    private fun MapControls(modifier: Modifier) {
        val c = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(16.dp)
        Column(modifier.padding(top = 84.dp, end = 16.dp).width(48.dp).clip(shape).background(c.surfaceContainerHigh)) {
            IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = "Ingrandisci", tint = c.onSurface)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
            IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }) {
                Icon(painterResource(R.drawable.ic_minus), contentDescription = "Riduci", tint = c.onSurface)
            }
        }
        Box(
            modifier.padding(top = 196.dp, end = 16.dp).size(48.dp).clip(shape).background(c.surfaceContainerHigh)
                .clickable(role = Role.Button) {
                    framed = false
                    frameAll()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_fit_all), contentDescription = "Mostra tutte le foto con posizione", tint = c.onSurface)
        }
    }

    /**
     * The selection sheet: drag the top part (or tap it) to open it, make it compact or hide it. Its height never
     * changes: the three states are only positions (summary + button on top, details below).
     */
    @Composable
    private fun SheetLayer(modifier: Modifier) {
        val c = MaterialTheme.colorScheme
        val scope = rememberCoroutineScope()
        SideEffect { sheetScope = scope }
        // the content changed size (names, chips): stay in the same state at the new measures
        LaunchedEffect(sheetH, bodyH) {
            if (!sheetDragging && sheetJob?.isActive != true) sheetOffset = offsetFor(sheetState)
        }

        val picked = pickedNames()
        val noGpsCount = remember(zones, selected) { noGpsKeys().size }
        val count = selected.size + (if (noGps) noGpsCount else 0)
        val headline = when {
            picked.isEmpty() && selected.isEmpty() -> "Tocca un cerchio per avvicinarti"
            picked.isEmpty() -> "Zone scelte"
            picked.size <= 2 -> picked.joinToString(", ")
            else -> picked.take(2).joinToString(", ") + " e altri ${picked.size - 2}"
        }
        val subline = when {
            selected.isEmpty() && !noGps -> "Tieni premuto un cerchio per sceglierlo tutto"
            else -> "$count foto" + (if (picked.size > 1) " in ${picked.size} zone" else "") +
                (if (noGps) ", comprese quelle senza posizione" else "")
        }
        val goText = if (selected.isEmpty() && !noGps) "Mostra tutte le foto" else "Mostra $count foto"

        // what goes out of sight fades with the sheet, so every position in between looks right
        fun hiddenFraction(): Float {
            val compact = offsetFor(SheetState.COMPACT)
            val hidden = offsetFor(SheetState.HIDDEN)
            return if (hidden <= compact) 0f else ((sheetOffset - compact) / (hidden - compact)).coerceIn(0f, 1f)
        }
        fun bodyAlpha(): Float {
            val compact = offsetFor(SheetState.COMPACT)
            return if (compact <= 0f) 1f else (1f - sheetOffset / compact).coerceIn(0f, 1f)
        }
        val goEnabled by remember { derivedStateOf { hiddenFraction() < 0.5f } }
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

        Box(
            modifier.fillMaxWidth().onSizeChanged { sheetH = it.height }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val y0 = down.position.y
                    // only the top part (handle and summary) drags
                    if (y0 < sheetOffset || y0 > sheetOffset + headerH) return@awaitEachGesture
                    sheetJob?.cancel()
                    val startOffset = sheetOffset
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragging = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        val dy = ch.position.y - y0
                        if (!dragging && abs(dy) > viewConfiguration.touchSlop) {
                            dragging = true
                            sheetDragging = true
                        }
                        if (dragging) {
                            ch.consume()
                            // follows the finger; past fully open, only a little give
                            val y = (startOffset + dy).coerceAtMost(offsetFor(SheetState.HIDDEN))
                            sheetOffset = if (y < 0f) y * 0.2f else y
                        }
                        if (!ch.pressed) {
                            if (!dragging) {
                                snapTo(
                                    when (sheetState) {
                                        SheetState.OPEN -> SheetState.COMPACT
                                        SheetState.COMPACT -> SheetState.OPEN
                                        SheetState.HIDDEN -> SheetState.COMPACT
                                    },
                                )
                            } else {
                                val vy = tracker.calculateVelocity().y
                                val y = sheetOffset
                                val states = SheetState.values().sortedBy { offsetFor(it) }
                                val fling = 400 * density // px/s
                                snapTo(
                                    when {
                                        vy > fling -> states.firstOrNull { offsetFor(it) > y + 1f } ?: SheetState.HIDDEN
                                        vy < -fling -> states.lastOrNull { offsetFor(it) < y - 1f } ?: SheetState.OPEN
                                        else -> states.minByOrNull { abs(offsetFor(it) - y) }!!
                                    },
                                )
                            }
                            break
                        }
                    }
                    sheetDragging = false
                }
            },
        ) {
            Column(
                Modifier.fillMaxWidth().offset { IntOffset(0, sheetOffset.roundToInt()) }.shadow(2.dp, shape)
                    .background(c.surfaceContainerLow, shape).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                // header: handle, headline, summary
                Column(
                    Modifier.fillMaxWidth().onSizeChanged { headerH = it.height }.padding(top = 12.dp, bottom = 2.dp)
                        .semantics { contentDescription = "Selezione: tocca per aprire, comprimere o nascondere" },
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(width = 32.dp, height = 4.dp).background(c.outline, RoundedCornerShape(2.dp)))
                    Text(
                        headline,
                        Modifier.fillMaxWidth().padding(start = 8.dp, top = 14.dp, end = 8.dp).graphicsLayer { alpha = 1f - hiddenFraction() },
                        color = c.onSurface, style = type(22, 28), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subline,
                        Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, end = 8.dp).graphicsLayer { alpha = 1f - hiddenFraction() },
                        color = c.onSurfaceVariant, style = type(14, 20), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                FujiButton(
                    goText, { apply() },
                    Modifier.padding(top = 16.dp).fillMaxWidth().graphicsLayer { alpha = 1f - hiddenFraction() },
                    kind = ButtonKind.Filled, enabled = goEnabled,
                )
                // details
                Column(Modifier.onSizeChanged { bodyH = it.height }.graphicsLayer { alpha = bodyAlpha() }) {
                    Text(
                        "Fuori da questa zona", Modifier.padding(start = 8.dp, top = 14.dp, end = 8.dp),
                        color = c.onSurfaceVariant, style = type(12, 16, medium = true),
                    )
                    Row(
                        Modifier.padding(top = 8.dp).fillMaxWidth().height(32.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (outside.isEmpty()) {
                            Text(
                                if (zones.isEmpty()) "Nessun luogo" else "Tutte le foto con posizione sono qui sopra",
                                Modifier.padding(start = 8.dp), color = c.onSurfaceVariant, style = type(14, 20), maxLines = 1,
                            )
                        } else {
                            for (z in outside) {
                                val on = selected.containsAll(z.keys)
                                FilterChip(
                                    "${nameOf(z).ifEmpty { "Altro luogo" }} ${z.keys.size}", on,
                                    if (on) R.drawable.ic_check else null, null, onClick = { toggle(z.keys) },
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Switch) { noGps = !noGps }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Includi foto senza posizione", color = c.onSurface, style = type(16, 24))
                            Text("$noGpsCount foto, scattate senza geotag", color = c.onSurfaceVariant, style = type(14, 20))
                        }
                        Switch(checked = noGps, onCheckedChange = { noGps = it }, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    companion object {
        /** OpenFreeMap: OpenStreetMap data, free, no key. */
        const val STYLE = "https://tiles.openfreemap.org/styles/liberty"

        /** Deepest zoom: closer than this, photos at the same spot stay one circle and a tap selects them. */
        const val MAX_ZOOM = 17.0
    }
}
