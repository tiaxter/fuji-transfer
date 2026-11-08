package com.fujixfer.app

import android.content.res.ColorStateList
import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fujixfer.core.ObjectInfo

/**
 * What the download screen shows, ready to draw. MainActivity builds a new one on every change of TransferState
 * (updateDownload) and hands it to Compose; the screen itself keeps no state of its own.
 */
data class DownloadUi(
    /** Files of the running batch, in order (the one in progress is batchIndex, 1-based). */
    val batch: List<ObjectInfo> = emptyList(),
    val batchIndex: Int = 0,
    val title: String = "",
    val bytes: String = "",
    /** Progress of the whole batch, 0..1000. */
    val progress: Int = 0,
    val speed: String = "…",
    val eta: String = "…",
    val retryNote: String = "",
    val counts: String = "",
    /** Bytes of the file in progress: its percentage is drawn on its ring. */
    val currentDone: Long = 0,
    val currentTotal: Long = 0,
    /** Bumped when thumbnails arrive: the rows read them from Thumbs when they are drawn. */
    val thumbVersion: Int = 0,
)

@Composable
fun DownloadScreen(
    ui: DownloadUi,
    rowMeta: (info: ObjectInfo, current: Boolean, finished: Boolean) -> String,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        // top bar
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Torna alla galleria", tint = c.onSurface)
            }
            Text("Download", Modifier.padding(start = 4.dp), color = c.onSurface, style = type(22, 28))
        }

        // summary card: files done of total, bytes, wavy bar, speed and time left
        Column(
            Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)).background(c.primaryContainer)
                .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 22.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    ui.title, Modifier.weight(1f).alignByBaseline(),
                    color = c.onPrimaryContainer, style = type(40, 48),
                )
                Text(ui.bytes, Modifier.alignByBaseline(), color = c.onPrimaryContainer, style = type(14, 20, medium = true))
            }
            WavyBar(
                progress = ui.progress, max = 1000, color = c.primary, track = c.surfaceContainerLowest,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(14.dp),
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_speed), null, Modifier.size(18.dp), tint = c.onPrimaryContainer)
                Text(ui.speed, Modifier.padding(start = 6.dp), color = c.onPrimaryContainer, style = type(14, 20))
                Spacer(Modifier.width(20.dp))
                Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(18.dp), tint = c.onPrimaryContainer)
                Text(ui.eta, Modifier.padding(start = 6.dp), color = c.onPrimaryContainer, style = type(14, 20))
            }
        }

        // the connection dropped and the transfer went on
        if (ui.retryNote.isNotEmpty()) {
            Row(
                Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(c.tertiaryContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_retry), null, Modifier.size(22.dp), tint = c.onTertiaryContainer)
                Text(ui.retryNote, Modifier.padding(start = 12.dp), color = c.onTertiaryContainer, style = type(14, 20))
            }
        }

        // list header
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("File", Modifier.weight(1f), color = c.primary, style = type(14, 20, medium = true))
            Text(ui.counts, color = c.onSurfaceVariant, style = type(12, 16))
        }

        // one row per file of the batch
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(ui.batch.size) { i ->
                val info = ui.batch[i]
                val current = i == ui.batchIndex - 1
                val finished = i < ui.batchIndex - 1
                val pct = if (current && ui.currentTotal > 0) (ui.currentDone * 100 / ui.currentTotal).toInt().coerceIn(0, 100) else 0
                FileRow(info, current, finished, pct, rowMeta(info, current, finished), ui.thumbVersion)
            }
        }

        // cancel / keep downloading in the background (back to the gallery): connected pair
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PairButton("Annulla", first = true, container = c.surfaceContainerHighest, content = c.onSurface,
                onClick = onCancel, modifier = Modifier.weight(1f))
            PairButton("Continua in background", first = false, container = c.secondaryContainer, content = c.onSecondaryContainer,
                onClick = onBackground, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FileRow(info: ObjectInfo, current: Boolean, finished: Boolean, pct: Int, meta: String, thumbVersion: Int) {
    val c = MaterialTheme.colorScheme
    // Thumbs is a plain cache, not Compose state: thumbVersion changes when thumbnails land, so the row is drawn again
    val bitmap = remember(info, thumbVersion) { Thumbs.get(info)?.asImageBitmap() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceContainer)) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(info.filename, color = c.onSurface, style = type(16, 24), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = c.onSurfaceVariant, style = type(14, 20), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            // a fixed 40 dp square: ring, number and check share the same centre
            finished -> Box(Modifier.padding(start = 8.dp).size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_check), contentDescription = "Completato",
                    Modifier.size(28.dp).clip(CircleShape).background(c.primary).padding(6.dp), tint = c.onPrimary,
                )
            }
            current -> Box(
                Modifier.padding(start = 8.dp).size(40.dp)
                    .semantics { contentDescription = "In corso, $pct per cento" },
                contentAlignment = Alignment.Center,
            ) {
                WavyBar(progress = pct, max = 100, color = c.primary, track = c.secondaryContainer, modifier = Modifier.size(40.dp))
                Text(pct.toString(), color = c.primary, style = type(10, 12, medium = true))
            }
            else -> Text("In coda", Modifier.padding(start = 8.dp), color = c.onSurfaceVariant, style = type(12, 16, medium = true), maxLines = 1)
        }
    }
}

/**
 * A button of a connected pair: wide outer corners, 8 dp inner corners; pressed, the outer corners tighten.
 * [first] is the left one.
 */
@Composable
private fun PairButton(text: String, first: Boolean, container: Color, content: Color, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val outer by animateDpAsState(if (pressed) 12.dp else 28.dp, tween(if (pressed) 120 else 220), label = "corner")
    val inner = 8.dp
    val shape = if (first) RoundedCornerShape(topStart = outer, topEnd = inner, bottomEnd = inner, bottomStart = outer)
    else RoundedCornerShape(topStart = inner, topEnd = outer, bottomEnd = outer, bottomStart = inner)
    Box(
        modifier.height(48.dp).clip(shape).background(container)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, style = type(14, 20, medium = true), maxLines = 1, textAlign = TextAlign.Center)
    }
}
