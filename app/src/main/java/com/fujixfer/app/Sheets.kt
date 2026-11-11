package com.fujixfer.app

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------------ stringResource(R.string.cancel_dl_title)

/**
 * What cancelling would do (design "Download · cosa succede se annulli"). [doneFiles]/[doneSize]: already on the
 * phone. [current]/[currentPercent]: the file on its way; its bytes stay and the next download resumes from
 * there. [queued]: files of the batch not started.
 */
class CancelInfo(
    val doneFiles: Int, val doneSize: String, val current: String, val currentPercent: Int,
    val currentSize: String, val queued: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancelSheet(info: CancelInfo, onKeep: () -> Unit, onCancel: () -> Unit, onFinishCurrent: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onKeep, containerColor = c.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.32f), dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.cancel_dl_title), Modifier.padding(horizontal = 4.dp), color = c.onSurface, style = type(22, 28, medium = true))
            val rows = ArrayList<Triple<String, String, OutcomeKind>>()
            if (info.doneFiles > 0) rows.add(Triple(
                pluralStringResource(R.plurals.cancel_kept, info.doneFiles, info.doneFiles),
                stringResource(R.string.cancel_kept_sub, info.doneSize), OutcomeKind.Kept,
            ))
            if (info.current.isNotEmpty()) rows.add(Triple(
                stringResource(R.string.cancel_stops, info.current, info.currentPercent),
                stringResource(R.string.cancel_stops_sub, info.currentSize), OutcomeKind.Stopped,
            ))
            if (info.queued > 0) rows.add(Triple(
                pluralStringResource(R.plurals.cancel_skipped, info.queued, info.queued),
                stringResource(R.string.cancel_skipped_sub), OutcomeKind.Skipped,
            ))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEachIndexed { i, (title, sub, kind) -> OutcomeRow(title, sub, kind, i == 0, i == rows.lastIndex) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                GroupButton(
                    stringResource(R.string.continue_), first = true, container = c.primary, content = c.onPrimary,
                    onClick = onKeep, modifier = Modifier.weight(1f), height = 56.dp, textSize = 14,
                )
                GroupButton(
                    stringResource(R.string.cancel_dl), first = false, container = c.errorContainer, content = c.onErrorContainer,
                    onClick = onCancel, modifier = Modifier.weight(1f), height = 56.dp, textSize = 14,
                )
            }
            if (info.current.isNotEmpty() && info.queued > 0) {
                FujiButton(
                    stringResource(R.string.finish_then_stop, info.current), onFinishCurrent, Modifier.align(Alignment.CenterHorizontally),
                    kind = ButtonKind.Text, height = 44.dp,
                )
            }
        }
    }
}

private enum class OutcomeKind { Kept, Stopped, Skipped }

@Composable
private fun OutcomeRow(title: String, sub: String, kind: OutcomeKind, first: Boolean, last: Boolean) {
    val c = MaterialTheme.colorScheme
    val big = 20.dp
    val small = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (first) big else small, topEnd = if (first) big else small,
        bottomStart = if (last) big else small, bottomEnd = if (last) big else small,
    )
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (bg, fg, icon) = when (kind) {
            OutcomeKind.Kept -> Triple(c.primary, c.onPrimary, R.drawable.ic_check)
            OutcomeKind.Stopped -> Triple(c.tertiaryContainer, c.onTertiaryContainer, R.drawable.ic_retry)
            OutcomeKind.Skipped -> Triple(Color.Transparent, c.onSurfaceVariant, R.drawable.ic_minus)
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(bg)
                .then(if (kind == OutcomeKind.Skipped) Modifier.border(2.dp, c.outline, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = fg)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = c.onSurface, style = type(15, 22, medium = true))
            Text(sub, color = c.onSurfaceVariant, style = type(13, 18))
        }
    }
}

// ------------------------------------------------------------------ "Scollegare la X-T5?"

/** [since]: "Collegata da 6 min · Wi‑Fi". [immichQueued]: files waiting for Immich (0 = no line about them). */
class DisconnectInfo(val camera: String, val since: String, val usb: Boolean, val immichQueued: Int, val wifiOnly: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisconnectSheet(info: DisconnectInfo, onStay: () -> Unit, onDisconnect: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onStay, containerColor = c.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.32f), dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(24.dp), tint = c.onPrimaryContainer)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(stringResource(R.string.disconnect_title, info.camera), color = c.onSurface, style = type(22, 28, medium = true))
                    Text(info.since, color = c.onSurfaceVariant, style = type(14, 20))
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (info.usb) {
                    Line(R.drawable.ic_usb, stringResource(R.string.disc_usb))
                } else {
                    Line(R.drawable.ic_wifi, stringResource(R.string.disc_wifi))
                }
                Line(R.drawable.ic_retry, stringResource(R.string.disc_again))
                if (info.immichQueued > 0) {
                    Line(
                        R.drawable.ic_cloud_queue,
                        pluralStringResource(if (info.wifiOnly) R.plurals.disc_immich_wifi else R.plurals.disc_immich_any, info.immichQueued, info.immichQueued),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                GroupButton(
                    stringResource(R.string.stay_connected), first = true, container = c.surfaceContainerHighest, content = c.onSurface,
                    onClick = onStay, modifier = Modifier.weight(1f), height = 56.dp, textSize = 14,
                )
                GroupButton(
                    stringResource(R.string.disconnect_short), first = false, container = c.primary, content = c.onPrimary,
                    onClick = onDisconnect, modifier = Modifier.weight(1f), height = 56.dp, textSize = 14,
                )
            }
        }
    }
}

@Composable
private fun Line(icon: Int, text: String) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = c.primary)
        Text(text, Modifier.padding(start = 12.dp), color = c.onSurface, style = type(14, 20))
    }
}

// ------------------------------------------------------------------ screen on, light at the minimum

/**
 * Over everything while a download runs with "Tieni lo schermo acceso" (design "Download · schermo acceso, luce
 * bassa"): almost black, the progress in dim green; the window's brightness is lowered by the activity. A tap
 * anywhere brings the normal screen back.
 */
@Composable
fun DimScreen(ui: DownloadUi, onWake: () -> Unit) {
    val dimText = Color(0xFF7FA592)
    val pct = if (ui.currentTotal > 0) (ui.currentDone * 100 / ui.currentTotal).toInt().coerceIn(0, 100) else 0
    val name = ui.batch.getOrNull(ui.batchIndex - 1)?.filename ?: ""
    Box(
        Modifier.fillMaxSize().background(Color(0xFF050806))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onWake),
    ) {
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 28.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF101814)).padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_phone), null, Modifier.size(16.dp), tint = dimText)
            Text(stringResource(R.string.dim_keep), Modifier.padding(start = 8.dp), color = dimText, style = type(13, 18, medium = true))
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                Text("${ui.batchIndex}", Modifier.alignByBaseline(), color = Color(0xFFA9D3BC), style = type(72, 80))
                Text(stringResource(R.string.of_m, ui.batch.size), Modifier.alignByBaseline(), color = Color(0xFF6F9583), style = type(32, 40))
            }
            if (name.isNotEmpty()) Text("$name · $pct%", color = dimText, style = type(15, 22))
            WavyBar(
                progress = ui.progress, max = 1000, color = Color(0xFF5E8F76), track = Color(0xFF18231D),
                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp).width(260.dp).height(18.dp),
            )
            Text("${ui.bytes} · ${ui.eta}", color = Color(0xFF6F9583), style = type(14, 20))
        }
        Text(
            stringResource(R.string.dim_hint),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), color = Color(0xFF557564),
            textAlign = TextAlign.Center, style = type(13, 18),
        )
    }
}
