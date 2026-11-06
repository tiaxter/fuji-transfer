package com.fujixfer.app

import androidx.compose.foundation.layout.height
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fujixfer.core.ObjectInfo

/** A row of the gallery: a day header, or up to three cells. */
sealed class GridRow {
    class Header(val title: String, val sub: String, val items: List<ObjectInfo>) : GridRow()

    /** Up to 3 cells: the files, then [ghosts] grey cells for files of [day] that the listing has not reached yet. */
    class Tiles(val items: List<ObjectInfo>, val ghosts: Int = 0, val day: String = "") : GridRow()
}

/** How one cell looks right now (MainActivity.tileUi fills it from the selection, the downloads and the thumbnail cache). */
/** The cell the viewer's picture is flying out of or into: drawn empty meanwhile (-1 = none). */
var coverHandle by androidx.compose.runtime.mutableLongStateOf(-1L)

class TileUi(
    val bitmap: Bitmap?,
    val selected: Boolean,
    /** Already saved on the phone. */
    val done: Boolean,
    /** Known to be on the Immich server. */
    val onImmich: Boolean = false,
    /** Asked with "Carica", not sent yet. */
    val immichQueued: Boolean = false,
    /** "RAW", "VIDEO", the extension, or the film simulation; null = no badge. */
    val badge: String?,
    val badgeIcon: Int?,
    /** Text on an empty cell when the camera has no picture for it. */
    val placeholder: String,
    /** A pulsing cell until the picture arrives. */
    val loading: Boolean,
    val description: String,
)

class GridActions(
    val tileUi: (ObjectInfo) -> TileUi,
    val allSelected: (List<ObjectInfo>) -> Boolean,
    /** A tap on a cell: opens the photo, or toggles it once something is selected. */
    val onTap: (ObjectInfo) -> Unit,
    /**
     * Touch and hold on a cell, then drag across others (select in bulk, like Google Photos): started on the cell
     * held, then told each cell the finger reaches; the range from the first to the current one is (de)selected.
     */
    val onDragStart: (ObjectInfo) -> Unit,
    val onDragOver: (ObjectInfo) -> Unit,
    val onDragEnd: () -> Unit,
    val onHeader: (GridRow.Header) -> Unit,
    val onRequest: (ObjectInfo) -> Unit,
    val onCancel: (ObjectInfo) -> Unit,
    /** Indexes of the rows on screen. */
    val onVisible: (List<Int>) -> Unit,
    /** A cell's place on screen, or null when the cell leaves (the viewer grows out of it and shrinks back into it). */
    val onTileCoords: (Long, LayoutCoordinates?) -> Unit,
)

/**
 * The gallery's grid: day headers and rows of three square cells, 2 dp apart. [tick] changes when something the cells
 * show (selection, downloads, thumbnails) changed outside Compose, so the visible rows are drawn again.
 */
@Composable
fun GalleryGrid(rows: List<GridRow>, tick: Int, selectionMode: Boolean, a: GridActions, scrollToken: Int = 0, scrollRow: Int = -1) {
    val listState = rememberLazyListState()
    // one pulse shared by every loading cell, so the gallery breathes together instead of flickering;
    // it runs only while some cell is loading
    val pulse = remember { mutableFloatStateOf(1f) }
    val loadingCount = remember { mutableIntStateOf(0) }
    val pulsing by remember { derivedStateOf { loadingCount.intValue > 0 } }
    LaunchedEffect(pulsing) {
        if (pulsing) {
            while (true) {
                withFrameNanos { ns ->
                    val phase = (ns / 1_000_000L % 1500L) / 750f
                    pulse.floatValue = 1f - 0.55f * (if (phase <= 1f) phase else 2f - phase)
                }
            }
        }
    }
    // the viewer is closing onto a file whose cell is not drawn: bring its row into view
    LaunchedEffect(scrollToken) {
        if (scrollToken > 0 && scrollRow >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == scrollRow }) {
            listState.scrollToItem(scrollRow)
        }
    }
    // days with grey cells on screen: the service reads their files next
    LaunchedEffect(listState, rows) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }.collect { a.onVisible(it) }
    }

    // touch and hold on a cell, then drag: selects the cells the finger crosses; near the top or bottom edge the
    // grid scrolls by itself
    val haptic = LocalHapticFeedback.current
    val rowsNow by rememberUpdatedState(rows)
    val gridWidth = remember { mutableIntStateOf(0) }
    val gridHeight = remember { mutableIntStateOf(0) }
    val dragPos = remember { mutableStateOf(Offset.Zero) }
    val autoSpeed = remember { mutableFloatStateOf(0f) }
    fun hit(p: Offset): ObjectInfo? {
        val w = gridWidth.intValue
        if (w <= 0) return null
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { p.y >= it.offset && p.y < it.offset + it.size } ?: return null
        val r = rowsNow.getOrNull(item.index) as? GridRow.Tiles ?: return null
        return r.items.getOrNull((p.x / (w / 3f)).toInt().coerceIn(0, 2))
    }
    val autoScrolling by remember { derivedStateOf { autoSpeed.floatValue != 0f } }
    LaunchedEffect(autoScrolling) {
        if (autoScrolling) {
            while (true) {
                withFrameNanos { }
                listState.scrollBy(autoSpeed.floatValue)
                hit(dragPos.value)?.let { a.onDragOver(it) } // the cell under the finger changed with the scroll
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().onSizeChanged {
            gridWidth.intValue = it.width
            gridHeight.intValue = it.height
        }.pointerInput(Unit) {
            awaitEachGesture {
                // watched before the cells see it (Initial pass), so a long press that turns into a drag is ours
                // and the cell under the finger never gets a click out of it
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val start = hit(down.position) ?: return@awaitEachGesture
                val heldLongEnough = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        // let go, or moved: a tap or a scroll, not ours
                        if (!ch.pressed || (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                } == null
                if (!heldLongEnough) return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                a.onDragStart(start)
                dragPos.value = down.position
                try {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ev.changes.forEach { it.consume() }
                        if (!ch.pressed) break
                        dragPos.value = ch.position
                        hit(ch.position)?.let { a.onDragOver(it) }
                        val edge = 72.dp.toPx()
                        val h = gridHeight.intValue
                        autoSpeed.floatValue = when {
                            ch.position.y < edge -> -(edge - ch.position.y) / edge * 28f
                            ch.position.y > h - edge -> (ch.position.y - (h - edge)) / edge * 28f
                            else -> 0f
                        }
                    }
                } finally {
                    autoSpeed.floatValue = 0f
                    a.onDragEnd()
                }
            }
        },
        state = listState,
    ) {
        items(rows.size, contentType = { if (rows[it] is GridRow.Header) 0 else 1 }) { i ->
            when (val r = rows[i]) {
                is GridRow.Header -> HeaderRow(r, a, tick)
                is GridRow.Tiles -> TilesRow(r, a, tick, selectionMode, pulse, loadingCount)
            }
        }
    }
}

@Composable
private fun HeaderRow(r: GridRow.Header, a: GridActions, tick: Int) {
    val c = MaterialTheme.colorScheme
    val all = a.allSelected(r.items)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append(r.title)
                append("  ")
                withStyle(SpanStyle(color = c.onSurfaceVariant, fontWeight = FontWeight.Normal)) { append(r.sub) }
            },
            Modifier.weight(1f), color = c.onSurface, style = type(14, 20, medium = true),
        )
        FujiButton(if (all) "Deseleziona" else "Seleziona tutte", { a.onHeader(r) }, kind = ButtonKind.Text, height = 40.dp)
    }
}

@Composable
private fun TilesRow(
    r: GridRow.Tiles, a: GridActions, tick: Int, selectionMode: Boolean, pulse: FloatState, loadingCount: MutableIntState,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (k in 0 until 3) {
            val slot = Modifier.weight(1f).aspectRatio(1f)
            when {
                k < r.items.size -> Tile(r.items[k], a, tick, selectionMode, slot, pulse, loadingCount)
                k < r.items.size + r.ghosts -> GhostTile(slot, pulse, loadingCount)
                else -> Spacer(slot)
            }
        }
    }
}

/** Registers a loading cell for as long as it is on screen, so the shared pulse runs. */
@Composable
private fun CountWhileLoading(loading: Boolean, loadingCount: MutableIntState) {
    DisposableEffect(loading) {
        if (loading) loadingCount.intValue++
        onDispose { if (loading) loadingCount.intValue-- }
    }
}

/** A file of a day the listing has not reached yet: a grey cell, not selectable. */
@Composable
private fun GhostTile(modifier: Modifier, pulse: FloatState, loadingCount: MutableIntState) {
    val c = MaterialTheme.colorScheme
    CountWhileLoading(true, loadingCount)
    Box(modifier.background(c.surfaceContainer).semantics { contentDescription = "Foto non ancora letta dalla camera" }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = pulse.floatValue }.background(c.surfaceContainerHighest))
    }
}

@Composable
private fun Tile(
    info: ObjectInfo, a: GridActions, tick: Int, selectionMode: Boolean, modifier: Modifier, pulse: FloatState, loadingCount: MutableIntState,
) {
    val c = MaterialTheme.colorScheme
    val ui = a.tileUi(info)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // a small press-in, like Material's pressed state on media
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, tween(if (pressed) 90 else 140), label = "press")
    // the selected look is a scale of the picture into a rounded tile (no layout pass), 120 ms like Google Photos
    val sel by animateFloatAsState(if (ui.selected) 1f else 0f, tween(120, easing = LinearOutSlowInEasing), label = "sel")

    // a cell that leaves the screen no longer needs its picture from the camera
    DisposableEffect(info) {
        onDispose {
            a.onCancel(info)
            a.onTileCoords(info.handle, null)
        }
    }
    LaunchedEffect(info, ui.loading, tick) { if (ui.loading) a.onRequest(info) }
    CountWhileLoading(ui.loading, loadingCount)

    Box(
        modifier.onGloballyPositioned { a.onTileCoords(info.handle, it) }.graphicsLayer {
            scaleX = press
            scaleY = press
            alpha = if (coverHandle == info.handle) 0f else 1f
        }
            .background(if (ui.selected) c.secondaryContainer else c.surfaceContainer)
            // a long press (and the drag after it) is the grid's: see the pointerInput of GalleryGrid
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = { a.onTap(info) })
            .semantics { contentDescription = ui.description },
    ) {
        // the picture (or the empty cell)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val s = 1f - sel * (20.dp.toPx() / size.width)
                scaleX = s
                scaleY = s
                shape = RoundedCornerShape(if (ui.selected) 14.dp else 0.dp)
                clip = true
            }.background(c.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (ui.placeholder.isNotEmpty()) {
                Text(ui.placeholder, color = c.onSurfaceVariant, style = type(12, 16, medium = true), textAlign = TextAlign.Center)
            }
            if (ui.loading) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pulse.floatValue }.background(c.surfaceContainerHighest))
            }
            val bmp = remember(ui.bitmap) { ui.bitmap?.asImageBitmap() }
            val fade by animateFloatAsState(if (bmp != null) 1f else 0f, tween(180), label = "fade")
            if (bmp != null) {
                Image(bmp, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade }, contentScale = ContentScale.Crop)
            }
        }

        // top left: on the phone, on Immich, or waiting for Immich
        if (ui.done || ui.onImmich || ui.immichQueued) {
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ui.done) {
                    Icon(
                        painterResource(R.drawable.ic_phone), contentDescription = null,
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(0x99000000)).padding(4.dp),
                        tint = Color.White,
                    )
                }
                if (ui.immichQueued) {
                    Row(
                        Modifier.height(24.dp).clip(CircleShape).background(c.tertiaryContainer).padding(start = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_cloud_queue), null, Modifier.size(16.dp), tint = c.onTertiaryContainer)
                        Spacer(Modifier.width(3.dp))
                        Text("In coda", color = c.onTertiaryContainer, style = type(11, 14, medium = true), maxLines = 1)
                    }
                } else if (ui.onImmich) {
                    Icon(
                        painterResource(R.drawable.ic_cloud_done), contentDescription = null,
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(0x99000000)).padding(4.dp),
                        tint = Color.White,
                    )
                }
            }
        }
        if (ui.badge != null) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.badgeIcon != null) {
                    Icon(painterResource(ui.badgeIcon), null, Modifier.size(12.dp), tint = Color.White)
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    ui.badge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = type(11, 16, medium = true).copy(letterSpacing = 0.04.em),
                )
            }
        }
        if (ui.selected) {
            Icon(
                painterResource(R.drawable.ic_check), contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                    .background(c.primary, CircleShape).border(2.dp, c.background, CircleShape).padding(5.dp),
                tint = c.onPrimary,
            )
        } else if (selectionMode) {
            // the empty rings only in selection mode, as in Google Photos
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp)
                    .background(Color(0x2E000000), CircleShape).border(2.dp, Color.White, CircleShape),
            )
        }
    }
}
