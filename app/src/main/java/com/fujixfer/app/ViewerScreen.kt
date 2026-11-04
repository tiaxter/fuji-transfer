package com.fujixfer.app

import android.graphics.Bitmap
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.fujixfer.core.ObjectInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Everything the full-size viewer draws; the Viewer of MainActivity keeps it up to date, the screen reads it. */
class ViewerState {
    var items by mutableStateOf<List<ObjectInfo>>(emptyList())
    /** The open file. */
    var index by mutableIntStateOf(0)
    /** The pictures of the three pages (previous, current, next); null = nothing to show yet. */
    var prev by mutableStateOf<Bitmap?>(null)
    var cur by mutableStateOf<Bitmap?>(null)
    var next by mutableStateOf<Bitmap?>(null)
    var imageVisible by mutableStateOf(true)
    var videoVisible by mutableStateOf(false)
    var player by mutableStateOf<Player?>(null)
    /** A video is fetched only when asked: the play button is shown until then. */
    var playVisible by mutableStateOf(false)
    var loadingVisible by mutableStateOf(false)
    /** Bars shown (a tap on the picture hides them). */
    var chrome by mutableStateOf(true)
    /** The video's controls are up: the strip makes room for its seek bar. */
    var controllerShown by mutableStateOf(false)
    var top by mutableStateOf(ViewerTopUi())
    var load by mutableStateOf(ViewerLoadUi())
    /** Changes when thumbnails arrive (the strip reads them from Thumbs when it draws). */
    var stripTick by mutableIntStateOf(0)
    /** The grid cell of the file the viewer opened on (screen px), null if it was not on screen: the picture grows out of it. */
    var origin by mutableStateOf<Rect?>(null)
    /** Raised to ask the screen to close with its animation (back button, arrow). */
    var closeToken by mutableIntStateOf(0)
}

class ViewerActions(
    val onBack: () -> Unit,
    val onHd: () -> Unit,
    val onSelect: () -> Unit,
    val onInfo: () -> Unit,
    val onPlay: () -> Unit,
    /** A single tap on the picture: show or hide the bars. */
    val onTap: () -> Unit,
    /** The pages settled on a neighbour (+1 next, -1 previous): the viewer moves its pictures one page over. */
    val onSettled: (Int) -> Unit,
    val onJump: (Int) -> Unit,
    /** The closing animation is over: take the viewer away. */
    val onDismiss: () -> Unit,
    /** Where the grid cell of file [index] is on screen (px), null if it is not drawn. */
    val originFor: (Int) -> Rect?,
    /** Scrolls the grid so that file [index] has a cell on screen (the viewer is closing onto it). */
    val ensureVisible: (Int) -> Unit,
)

private const val MAX_ZOOM = 8f

/** Zoom of the open picture: scale relative to "fits the screen", and the pan of its centre in px. */
private class ZoomState {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
}

private class Gesture {
    var job: Job? = null
    var tapJob: Job? = null
    var lastTapAt = 0L
    var lastTapPos = Offset.Zero
    var closing = false
}

/**
 * Full-screen viewer: three pages side by side (previous, current, next) that follow the finger together like in
 * Google Photos, pinch zoom / pan / double tap on the current picture, and a vertical drag that closes the viewer
 * (the picture follows the finger and shrinks, the black fades). Like Google Photos, the picture grows out of its
 * grid cell when the viewer opens and shrinks back into the cell of the file on screen when it closes (the
 * cell is cropped square, the picture is fitted: both are interpolated). One gesture handler decides
 * between them: a picture zoomed in moves inside itself, otherwise the first move decides paging or closing.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun ViewerScreen(s: ViewerState, a: ViewerActions) {
    val c = Color.White
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val g = remember { Gesture() }
    var boxW by remember { mutableIntStateOf(0) }
    var boxH by remember { mutableIntStateOf(0) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val zs = remember(s.cur) { ZoomState() } // a new picture starts fitting the screen
    val zoomNow by rememberUpdatedState(zs)
    val sNow by rememberUpdatedState(s)
    // opening / closing: t goes 0 → 1 → 0; closeFrom remembers where the page was (after a drag) when it started to close
    val t = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var closeOrigin by remember { mutableStateOf<Rect?>(null) }
    var closeFrom by remember { mutableStateOf(Triple(0f, 0f, 1f)) }
    var bgStart by remember { mutableFloatStateOf(1f) }
    var boxPos by remember { mutableStateOf(Offset.Zero) }
    // false until the box has been laid out once: before that boxPos is a guess and the picture would be drawn off its cell
    var placed by remember { mutableStateOf(false) }
    val gap = with(density) { 16.dp.toPx() }
    val minFling = with(density) { 350.dp.toPx() }
    val pageW = boxW + gap

    fun clampPan(zoom: Float, px: Float, py: Float): Offset {
        val b = sNow.cur ?: return Offset.Zero
        val fit = min(boxW.toFloat() / b.width, boxH.toFloat() / b.height)
        val mx = max(0f, (b.width * fit * zoom - boxW) / 2f)
        val my = max(0f, (b.height * fit * zoom - boxH) / 2f)
        return Offset(px.coerceIn(-mx, mx), py.coerceIn(-my, my))
    }

    fun settle(dir: Int) {
        val target = -dir * pageW
        g.job?.cancel()
        val from = offsetX
        g.job = scope.launch {
            val ms = (180 + 120 * abs(target - from) / max(pageW, 1f)).toInt().coerceAtMost(300)
            animate(from, target, animationSpec = tween(ms, easing = Easing { 1f - (1f - it).pow(3.2f) })) { v, _ -> offsetX = v }
            if (dir != 0) {
                sNow.let { a.onSettled(dir) } // the viewer moves the pictures one page over: nothing changes on screen
                offsetX = 0f
            }
        }
    }

    fun springBack() {
        g.job?.cancel()
        val from = offsetY
        g.job = scope.launch { animate(from, 0f, animationSpec = tween(200, easing = Easing { 1f - (1f - it).pow(3.2f) })) { v, _ -> offsetY = v } }
    }

    fun startClose() {
        if (closing) return
        closing = true
        g.closing = true
        g.job?.cancel()
        g.tapJob?.cancel()
        zoomNow.zoom = 1f // the picture goes into its cell fitted
        zoomNow.panX = 0f
        zoomNow.panY = 0f
        val p = if (boxH > 0) (abs(offsetY) / (boxH * 0.5f)).coerceIn(0f, 1f) else 0f
        closeFrom = Triple(offsetX, offsetY, 1f - 0.4f * p)
        bgStart = 1f - p
        offsetX = 0f
        offsetY = 0f
        scope.launch {
            try {
                // the grid scrolls to the file on screen if its cell is not drawn, then the picture goes into it
                var o = a.originFor(sNow.index)
                if (o == null) {
                    a.ensureVisible(sNow.index)
                    repeat(3) { withFrameNanos { } }
                    o = a.originFor(sNow.index)
                }
                closeOrigin = o
                DebugLog.detail("visore: chiusura, cella " + (if (o == null) "non disegnata" else "trovata"))
                t.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.add("visore: chiusura animata fallita: $e")
            }
            // whatever happened, the viewer goes away
            a.onDismiss()
        }
    }

    fun zoomTo(target: Float, focus: Offset) {
        val z0 = zoomNow.zoom
        val cx = focus.x - boxW / 2f
        val cy = focus.y - boxH / 2f
        val rawX = cx - (target / z0) * (cx - zoomNow.panX)
        val rawY = cy - (target / z0) * (cy - zoomNow.panY)
        val end = if (target <= 1f) Offset.Zero else clampPan(target, rawX, rawY)
        val z = zoomNow
        val sz = z.zoom
        val sx = z.panX
        val sy = z.panY
        scope.launch {
            animate(0f, 1f, animationSpec = tween(160)) { f, _ ->
                z.zoom = sz + (target - sz) * f
                z.panX = sx + (end.x - sx) * f
                z.panY = sy + (end.y - sy) * f
            }
        }
    }

    // how far the closing drag is, 0 (at rest) to 1 (gone): the black and the bars fade with it
    val progress = if (boxH > 0) (abs(offsetY) / (boxH * 0.5f)).coerceIn(0f, 1f) else 0f
    val chromeAlpha by animateFloatAsState(if (s.chrome) 1f else 0f, tween(150), label = "chrome")
    val animating = t.value < 1f || closing
    // the bars come in late when it opens and go first when it closes
    val barsAlpha = chromeAlpha * (1f - (progress * 3f).coerceAtMost(1f)) * ((t.value - 0.5f) * 2f).coerceIn(0f, 1f)
    val bgAlpha = when {
        closing -> t.value * bgStart
        t.value < 1f -> t.value
        else -> 1f - progress
    }
    val bottomAlpha by animateFloatAsState(
        if (s.chrome && !(s.videoVisible && s.controllerShown)) 1f else 0f, tween(150), label = "bottom",
    )

    // the picture grows out of its grid cell (the grid says where it is)
    LaunchedEffect(Unit) {
        s.origin = a.originFor(sNow.index)
        t.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(s.closeToken) { if (s.closeToken > 0) startClose() }
    // the grid cell stays empty while the picture flies out of it or into it
    // (only once there is a picture to draw over it: an empty viewer would leave a hole)
    val coverNow = if (animating && placed && s.cur != null) s.items.getOrNull(s.index)?.handle ?: -1L else -1L
    LaunchedEffect(s.cur) { DebugLog.detail("visore: foto ${s.cur?.width}x${s.cur?.height} a t=${(t.value * 100).roundToInt()}%") }
    LaunchedEffect(coverNow) { coverHandle = coverNow }
    DisposableEffect(Unit) { onDispose { coverHandle = -1L } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = bgAlpha.coerceIn(0f, 1f)))) {
      Box(
        Modifier.fillMaxSize().systemBarsPadding().onGloballyPositioned {
            boxPos = it.positionInRoot()
            placed = true
        }.onSizeChanged {
            boxW = it.width
            boxH = it.height
        },
      ) {
        // pages: previous, current, next; they slide together under the finger
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (g.closing || t.value < 1f) return@awaitEachGesture // opening or closing: nothing to touch
                    val takingOver = g.job?.isActive == true
                    g.job?.cancel()
                    if (offsetY != 0f) offsetY = 0f // a page still springing back from a closing drag lands at rest
                    val slop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    // 0 undecided, 1 the picture (pinch, pan), 2 paging, 3 closing
                    var mode = if (takingOver && offsetX != 0f) 2 else 0
                    var moved = false
                    val imageMode = sNow.imageVisible && sNow.cur != null
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressedCount = ev.changes.count { it.pressed }
                        if (pressedCount >= 2 && imageMode && (mode == 0 || mode == 1)) {
                            mode = 1
                            moved = true
                            val zc = ev.calculateZoom()
                            val pc = ev.calculatePan()
                            val centroid = ev.calculateCentroid()
                            val z = zoomNow
                            val z0 = z.zoom
                            val z1 = (z0 * zc).coerceIn(1f, MAX_ZOOM)
                            val cx = centroid.x - boxW / 2f
                            val cy = centroid.y - boxH / 2f
                            val p = clampPan(z1, cx + pc.x - (z1 / z0) * (cx - z.panX), cy + pc.y - (z1 / z0) * (cy - z.panY))
                            z.zoom = z1
                            z.panX = p.x
                            z.panY = p.y
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                            moved = true
                            // the first move decides: zoomed in, the picture moves; otherwise sideways pages, up or down closes
                            mode = when {
                                imageMode && zoomNow.zoom > 1.02f -> 1
                                abs(dx) > abs(dy) * 1.3f -> 2
                                else -> 3
                            }
                            DebugLog.detail("visore: gesto ${when (mode) { 1 -> "sposta la foto"; 2 -> "scorre le pagine"; else -> "chiude (su/giù)" }} " +
                                "dx=${dx.roundToInt()} dy=${dy.roundToInt()}")
                        }
                        val delta = ch.positionChange()
                        when (mode) {
                            1 -> {
                                val z = zoomNow
                                val p = clampPan(z.zoom, z.panX + delta.x, z.panY + delta.y)
                                z.panX = p.x
                                z.panY = p.y
                                ch.consume()
                            }
                            2 -> {
                                var d = delta.x
                                // past the first or the last file the page resists instead of moving freely
                                if ((offsetX + d > 0 && sNow.index <= 0) || (offsetX + d < 0 && sNow.index >= sNow.items.size - 1)) d *= 0.35f
                                offsetX += d
                                ch.consume()
                            }
                            3 -> {
                                offsetY += delta.y
                                ch.consume()
                            }
                        }
                        if (!ch.pressed) {
                            val v = tracker.calculateVelocity()
                            when (mode) {
                                2 -> {
                                    val w = boxW.toFloat()
                                    val dir = when {
                                        sNow.index < sNow.items.size - 1 && offsetX < 0 && (offsetX < -w / 3f || v.x < -minFling) -> 1
                                        sNow.index > 0 && offsetX > 0 && (offsetX > w / 3f || v.x > minFling) -> -1
                                        else -> 0
                                    }
                                    settle(dir)
                                }
                                3 -> {
                                    val far = abs(offsetY) > boxH / 8f
                                    // a fling in the direction of the drag closes it from any distance; a fling back brings it back
                                    val flung = abs(v.y) > minFling && (abs(offsetY) < 1f || (v.y > 0) == (offsetY > 0))
                                    DebugLog.detail("visore: rilascio offsetY=${offsetY.roundToInt()} h=$boxH vy=${v.y.roundToInt()} → " +
                                        if (far || flung) "chiude" else "torna a posto")
                                    if (far || flung) startClose() else springBack()
                                }
                                0 -> if (!moved && imageMode) {
                                    // a tap: one confirms after the double tap time, two zoom
                                    val timeout = viewConfiguration.doubleTapTimeoutMillis
                                    val now = ch.uptimeMillis
                                    if (g.lastTapAt != 0L && now - g.lastTapAt < timeout && (ch.position - g.lastTapPos).getDistance() < 100.dp.toPx()) {
                                        g.tapJob?.cancel()
                                        g.lastTapAt = 0L
                                        zoomTo(if (zoomNow.zoom > 1.5f) 1f else 3f, ch.position)
                                    } else {
                                        g.lastTapAt = now
                                        g.lastTapPos = ch.position
                                        g.tapJob = scope.launch {
                                            delay(timeout)
                                            g.lastTapAt = 0L
                                            a.onTap()
                                        }
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            },
        ) {
            // previous
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = offsetX - pageW }) {
                if (!animating) s.prev?.let { Page(it, "Foto precedente") }
            }
            // current: slides, follows a vertical drag (shrinking a little), zooms
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val sc = 1f - 0.4f * progress
                    translationX = offsetX
                    translationY = offsetY
                    scaleX = sc
                    scaleY = sc
                },
            ) {
                val bmp = s.cur
                if (animating && bmp != null) {
                    // shared element: grid cell ⇄ fitted picture
                    if (placed) {
                        SharedPicture(
                            bmp, if (closing) closeOrigin else s.origin, boxPos, t.value,
                            if (closing) closeFrom else Triple(0f, 0f, 1f),
                        )
                    }
                } else if (s.imageVisible && bmp != null) {
                    Page(
                        bmp, "Foto",
                        Modifier.graphicsLayer {
                            scaleX = zs.zoom
                            scaleY = zs.zoom
                            translationX = zs.panX
                            translationY = zs.panY
                        },
                    )
                }
                if (s.videoVisible && !animating) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                setControllerVisibilityListener(
                                    PlayerView.ControllerVisibilityListener { visibility -> s.controllerShown = visibility == View.VISIBLE },
                                )
                            }
                        },
                        update = { it.player = s.player },
                    )
                }
                // a video is fetched only when asked: swiping past videos costs the camera nothing
                if (s.playVisible && !animating) {
                    Box(
                        Modifier.align(Alignment.Center).size(76.dp).clip(CircleShape).background(Color(0x99000000))
                            .clickable(onClick = a.onPlay).padding(22.dp)
                            .semantics { contentDescription = "Scarica e guarda il video" },
                    ) {
                        Icon(painterResource(R.drawable.ic_play), null, Modifier.fillMaxSize(), tint = c)
                    }
                }
            }
            // next
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = offsetX + pageW }) {
                if (!animating) s.next?.let { Page(it, "Foto successiva") }
            }
        }

        // bottom: what is on its way, then the strip of thumbnails
        if (bottomAlpha > 0f) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)
                    .graphicsLayer { alpha = bottomAlpha * (1f - (progress * 3f).coerceAtMost(1f)) },
            ) {
                if (s.loadingVisible) {
                    Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) { ViewerLoading(s.load) }
                }
                ViewerStrip(s.items, s.index, s.stripTick, a.onJump)
            }
        }
        // top bar
        if (chromeAlpha > 0f) {
            Box(Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = barsAlpha }) {
                ViewerBar(s.top, a.onBack, a.onHd, a.onSelect, a.onInfo)
            }
        }
      }
    }
}

/**
 * The picture while the viewer opens or closes: it sits between the grid cell ([origin], cropped square) and the
 * fitted picture ([from]: where the page is, after a drag: x, y, scale), at progress [t] (0 = in the cell). The
 * visible window and the picture's scale and centre are interpolated, so the crop opens up into the whole picture.
 * Without a cell the picture just shrinks towards the middle and fades.
 */
@Composable
private fun SharedPicture(bitmap: Bitmap, origin: Rect?, boxPos: Offset, t: Float, from: Triple<Float, Float, Float>) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = if (origin == null) t.coerceIn(0f, 1f) else 1f }) {
        val w = size.width
        val h = size.height
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        // the full page, where it is now
        val sc = from.third
        val fcx = w / 2f + from.first
        val fcy = h / 2f + from.second
        val fw = w * sc
        val fh = h * sc
        val fitScale = min(w / bw, h / bh) * sc
        // the cell, in this box's own pixels (or a shrunk page when there is none)
        val cell = origin?.translate(-boxPos) ?: Rect(fcx - fw * 0.3f, fcy - fh * 0.3f, fcx + fw * 0.3f, fcy + fh * 0.3f)
        val coverScale = max(cell.width / bw, cell.height / bh)
        fun lerp(a: Float, b: Float) = a + (b - a) * t
        val left = lerp(cell.left, fcx - fw / 2f)
        val top = lerp(cell.top, fcy - fh / 2f)
        val right = lerp(cell.right, fcx + fw / 2f)
        val bottom = lerp(cell.bottom, fcy + fh / 2f)
        val cx = lerp(cell.center.x, fcx)
        val cy = lerp(cell.center.y, fcy)
        val scale = lerp(if (origin == null) fitScale * 0.6f else coverScale, fitScale)
        val dw = bw * scale
        val dh = bh * scale
        clipRect(left, top, right, bottom) {
            drawImage(
                img, dstOffset = IntOffset((cx - dw / 2f).roundToInt(), (cy - dh / 2f).roundToInt()),
                dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** One picture fitted to the screen. */
@Composable
private fun Page(bitmap: Bitmap, description: String, modifier: Modifier = Modifier) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        img, contentDescription = description, modifier = modifier.fillMaxSize(), contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Medium, // the same filter as SharedPicture: no change of sharpness when the animation hands over
    )
}

/**
 * The row of thumbnails under the viewer's picture, as in the design: rounded squares, the open file outlined and
 * kept in the middle. Lazy, so it costs the same for 10 files or 2,000. Drag or fling to look along it, tap one to
 * open it.
 */
@Composable
fun ViewerStrip(items: List<ObjectInfo>, current: Int, tick: Int, onPick: (Int) -> Unit) {
    val state = rememberLazyListState()
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(current, items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        val i = current.coerceIn(0, items.size - 1)
        if (first[0] || abs(i - state.firstVisibleItemIndex) > 12) state.scrollToItem(i) else state.animateScrollToItem(i)
        first[0] = false
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Altre foto" }) {
        LazyRow(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(horizontal = (maxWidth - 48.dp) / 2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(items.size) { i -> StripCell(items[i], i == current, tick) { onPick(i) } }
        }
    }
}

@Composable
private fun StripCell(info: ObjectInfo, open: Boolean, tick: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val bmp = Thumbs.get(info)
    // a thumbnail missing now is drawn on the next refresh, when it arrives
    LaunchedEffect(info, tick) { if (Thumbs.get(info) == null) Thumbs.request(info) }
    Box(
        Modifier.size(48.dp).drawWithContent {
            drawContent()
            if (open) {
                // the open file's ring, just outside the cell
                val o = 2.dp.toPx()
                drawRoundRect(
                    Color.White, Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o),
                    CornerRadius(8.dp.toPx() + o), style = Stroke(2.dp.toPx()),
                )
            }
        }.clip(shape).background(Color(0x33FFFFFF)).clickable(onClick = onClick),
    ) {
        if (bmp != null) {
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(
                img, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (open) 1f else 0.78f },
                contentScale = ContentScale.Crop,
            )
        }
    }
}
