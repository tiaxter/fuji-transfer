package com.fujixfer.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Material 3 Expressive wavy progress indicator, drawn by hand.
 *
 * The shape follows the box: about as wide as tall → circular, otherwise linear. A linear bar too thin for a wave
 * (the 4 dp work bar) is drawn flat. A finished bar is drawn flat too. The wave moves only while the bar is on
 * screen and system animations are on.
 *
 * @param progress 0..[max]; ignored when [indeterminate].
 */
@Composable
fun WavyBar(progress: Int, max: Int, color: Color, track: Color, modifier: Modifier, indeterminate: Boolean = false) {
    val fraction by animateFloatAsState(
        if (max > 0) (progress.toFloat() / max).coerceIn(0f, 1f) else 0f, tween(150), label = "wavy",
    )
    // one clock for the wave and the indeterminate sweep; it ticks per frame only while the bar is shown
    val animated = remember { ValueAnimator.areAnimatorsEnabled() }
    val view = LocalView.current
    val clock = remember { mutableLongStateOf(0L) }
    if (animated) {
        LaunchedEffect(Unit) {
            while (true) {
                if (view.isShown) withFrameNanos { clock.longValue = it / 1_000_000L } else delay(250)
            }
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val timeMs = clock.longValue
        val phase = (timeMs % 1000L) / 1000f
        if (abs(w - h) < 4 * density) drawRing(min(w, h), fraction, indeterminate, color, track, phase, timeMs)
        else drawBar(w, h, fraction, indeterminate, color, track, phase, timeMs)
    }
}

private fun DrawScope.drawBar(w: Float, h: Float, f: Float, indeterminate: Boolean, color: Color, track: Color, phase: Float, timeMs: Long) {
    val d = density
    val s = min(4 * d, h)
    val amp = ((h - s) / 2f).coerceIn(0f, 3 * d)
    val lam = 20 * d
    val half = s / 2f
    val cy = h / 2f
    val x0 = half
    val x1 = w - half
    if (indeterminate) {
        val t = (timeMs % 1800L) / 1800f
        val len = (x1 - x0) * 0.4f
        val a = x0 - len + (x1 - x0 + len) * t
        val from = a.coerceAtLeast(x0)
        val to = (a + len).coerceAtMost(x1)
        val gap = 4 * d
        // the track only where the wave is not, with a gap between them (the caps stick out half a stroke)
        if (to <= from) {
            drawLine(track, Offset(x0, cy), Offset(x1, cy), s, StrokeCap.Round)
        } else {
            if (from > x0 && from - s - gap > x0) drawLine(track, Offset(x0, cy), Offset(from - s - gap, cy), s, StrokeCap.Round)
            if (to < x1 && to + s + gap < x1) drawLine(track, Offset(to + s + gap, cy), Offset(x1, cy), s, StrokeCap.Round)
        }
        if (to < x1) drawCircle(color, half, Offset(x1, cy)) // stop indicator at the end of the track
        wave(from, to, cy, amp, lam, s, color, phase)
        return
    }
    val end = x0 + (x1 - x0) * f
    val gap = 4 * d
    val trackStart = if (f > 0f) end + gap + s else x0
    if (trackStart < x1) drawLine(track, Offset(trackStart, cy), Offset(x1, cy), s, StrokeCap.Round)
    if (f < 1f) drawCircle(color, half, Offset(x1, cy)) // stop indicator at the end of the track
    if (f > 0f) wave(x0, end, cy, if (f >= 1f) 0f else amp, lam, s, color, phase)
}

private fun DrawScope.wave(a: Float, b: Float, cy: Float, amp: Float, lam: Float, s: Float, color: Color, phase: Float) {
    if (b <= a) return
    if (amp <= 0f) {
        drawLine(color, Offset(a, cy), Offset(b, cy), s, StrokeCap.Round)
        return
    }
    val p = phase * 2 * PI
    val path = Path()
    var x = a
    path.moveTo(x, cy + amp * sin(x / lam * 2 * PI - p).toFloat())
    while (x < b) {
        x = min(x + density, b)
        path.lineTo(x, cy + amp * sin(x / lam * 2 * PI - p).toFloat())
    }
    drawPath(path, color, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawRing(size: Float, f: Float, indeterminate: Boolean, color: Color, track: Color, phase: Float, timeMs: Long) {
    val d = density
    val s = (size * 0.08f).coerceIn(3 * d, 5 * d)
    val amp = (size * 0.03f).coerceIn(1.2f * d, 4 * d)
    val cx = size / 2f
    val cy = size / 2f
    val r = size / 2f - s / 2f - amp
    if (r <= 0f) return
    val topLeft = Offset(cx - r, cy - r)
    val arcSize = Size(2 * r, 2 * r)
    val waves = (2 * PI * r / max(12 * d, r * 0.4f)).roundToInt().coerceAtLeast(6)
    val start: Float
    val sweep: Float
    var a = amp
    if (indeterminate) {
        val t = (timeMs % 1600L) / 1600f
        sweep = 30f + 220f * (sin(t * 2 * PI) * 0.5 + 0.5).toFloat()
        start = -90f + t * 720f
        // the track only where the wave is not, with a gap on both sides
        val gapDeg = Math.toDegrees(((4 * d + s) / r).toDouble()).toFloat()
        val ts = start + sweep + gapDeg
        val te = start + 360f - gapDeg
        if (te > ts) drawArc(track, ts, te - ts, false, topLeft, arcSize, style = Stroke(width = s, cap = StrokeCap.Round))
    } else {
        start = -90f
        sweep = 360f * f
        if (f < 1f) {
            val gapDeg = if (f > 0f) Math.toDegrees(((4 * d + s) / r).toDouble()).toFloat() else 0f
            val ts = start + sweep + gapDeg
            val te = start + 360f - gapDeg
            if (te > ts) drawArc(track, ts, te - ts, false, topLeft, arcSize, style = Stroke(width = s, cap = StrokeCap.Round))
        } else {
            a = 0f
        }
        if (f <= 0f) return
    }
    val p = phase * 2 * PI
    val n = max(8, (sweep / 2f).toInt())
    val path = Path()
    for (i in 0..n) {
        val th = Math.toRadians((start + sweep * i / n).toDouble())
        val rr = r + a * sin(waves * th - p).toFloat()
        val x = cx + rr * cos(th).toFloat()
        val y = cy + rr * sin(th).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
