package com.fujixfer.app

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Details of one file, ready to draw (MainActivity.detailsUi fills it). */
data class DetailsUi(
    val title: String,
    /** "RAF · Provia", "JPG"... */
    val format: String,
    val date: String,
    val dims: String,
    val size: String,
    val done: String,
    val canDownload: Boolean,
    val selected: Boolean,
    /** "26 MP" next to the format ("" = unknown). */
    val megapixels: String = "",
    val immich: ImmichMark = ImmichMark.None,
    val canUpload: Boolean = false,
)

/** Where a camera file stands with Immich. */
enum class ImmichMark(val label: String) { None("Non ancora"), Queued("In coda"), Done("Sì") }

/**
 * The details as a real sheet over the full-size viewer (a window of its own: the viewer stays visible and live
 * behind it, a tap outside or a drag down closes it).
 *
 * @param onSelect toggles the selection of the file and returns whether it is now selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(ui: DetailsUi, onDismiss: () -> Unit, onSelect: () -> Boolean, onDownload: () -> Unit, onUpload: () -> ImmichMark) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { SheetHandle() },
    ) {
        DetailsContent(ui, onSelect, onDownload, onUpload)
    }
}

@Composable
fun SheetHandle() {
    val c = MaterialTheme.colorScheme
    Box(Modifier.padding(top = 12.dp).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline))
}

/**
 * Title, format tags, the four facts and the select / upload / download group: the inside of the sheet, also used
 * by the offline preview. [onUpload] queues the file for Immich (or takes it out of the queue) and returns the new mark.
 */
@Composable
fun DetailsContent(ui: DetailsUi, onSelect: () -> Boolean, onDownload: () -> Unit, onUpload: () -> ImmichMark) {
    val c = MaterialTheme.colorScheme
    var selected by remember(ui) { mutableStateOf(ui.selected) }
    var immich by remember(ui) { mutableStateOf(ui.immich) }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.title, Modifier.weight(1f), color = c.onSurface, style = type(22, 28),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier.padding(start = 8.dp).height(28.dp).clip(RoundedCornerShape(8.dp)).background(c.secondaryContainer)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(ui.format, color = c.onSecondaryContainer, style = type(12, 16, medium = true), maxLines = 1)
            }
            if (ui.megapixels.isNotEmpty()) {
                Box(
                    Modifier.padding(start = 8.dp).height(28.dp).clip(RoundedCornerShape(8.dp))
                        .border(1.dp, c.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ui.megapixels, color = c.onSurface, style = type(12, 16, medium = true), maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.weight(1f)) {
                Field("Scattata", ui.date)
                Field("Su Immich", immich.label, Modifier.padding(top = 14.dp), if (immich == ImmichMark.Done) c.primary else null)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Field("Dimensione", ui.size)
                Field("Sul telefono", ui.done, Modifier.padding(top = 14.dp))
            }
        }
        // Seleziona (icon only) | Carica | Scarica ora
        Row(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            GroupButton(
                "", first = true,
                container = if (selected) c.secondaryContainer else c.surfaceContainerHighest,
                content = if (selected) c.onSecondaryContainer else c.onSurface,
                onClick = { selected = onSelect() }, modifier = Modifier.width(56.dp),
                icon = if (selected) R.drawable.ic_check else R.drawable.ic_select_all,
                description = if (selected) "Selezionata, tocca per togliere" else "Seleziona",
            )
            Box(Modifier.size(2.dp))
            GroupButton(
                when (immich) {
                    ImmichMark.None -> "Immich"
                    ImmichMark.Queued -> "In coda"
                    ImmichMark.Done -> "Su Immich"
                },
                first = false, middle = true,
                container = when (immich) {
                    ImmichMark.None -> c.secondaryContainer
                    ImmichMark.Queued -> c.tertiaryContainer
                    ImmichMark.Done -> c.surfaceContainerHighest
                },
                content = when (immich) {
                    ImmichMark.None -> c.onSecondaryContainer
                    ImmichMark.Queued -> c.onTertiaryContainer
                    ImmichMark.Done -> c.onSurfaceVariant
                },
                onClick = { immich = onUpload() }, modifier = Modifier.weight(1f),
                enabled = immich != ImmichMark.Done && (ui.canUpload || immich == ImmichMark.Queued),
                icon = when (immich) {
                    ImmichMark.None -> R.drawable.ic_cloud_upload
                    ImmichMark.Queued -> R.drawable.ic_cloud_queue
                    ImmichMark.Done -> R.drawable.ic_cloud_done
                },
                textSize = 14,
                description = when (immich) {
                    ImmichMark.None -> "Invia a Immich"
                    ImmichMark.Queued -> "In coda per Immich, tocca per annullare"
                    ImmichMark.Done -> "Già su Immich"
                },
            )
            Box(Modifier.size(2.dp))
            GroupButton(
                "Scarica ora", first = false, container = c.primary, content = c.onPrimary,
                onClick = onDownload, modifier = Modifier.weight(1.2f), enabled = ui.canDownload, icon = R.drawable.ic_download,
                textSize = 14,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    val c = MaterialTheme.colorScheme
    Column(modifier) {
        Text(label, color = c.onSurfaceVariant, style = type(12, 16))
        Text(value, color = valueColor ?: c.onSurface, style = type(16, 24), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
