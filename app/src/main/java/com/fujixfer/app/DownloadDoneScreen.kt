package com.fujixfer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fujixfer.core.ObjectInfo

/** The Immich part of the "Download finito" screen. */
enum class DoneImmich {
    /** Immich not set up, or "Solo quando lo chiedo": nothing to ask. */
    None,
    /** "Chiedi a fine download": "Caricarli anche su Immich?". */
    Ask,
    /** The files went to the queue (answer yes, or "Sempre"). */
    Queued,
    /** "Non ora". */
    Declined,
}

/**
 * What "Download finito" shows (design "Download finito · chiede se caricare", "scegli cosa caricare" and
 * "Download · caduta a schermo spento"), built from the batch's [BatchResult].
 */
data class DoneUi(
    val id: Long = 0,
    val saved: List<ObjectInfo> = emptyList(),
    val batchSize: Int = 0,
    val failed: Int = 0,
    /** "512 MB in 3 min 4 s · Galleria › Fujifilm". */
    val summary: String = "",
    val immich: DoneImmich = DoneImmich.None,
    val queuedCount: Int = 0,
    /** The camera switched its Wi-Fi off and the batch stopped. */
    val dropped: Boolean = false,
    val droppedScreenOff: Boolean = false,
    val partialName: String = "",
    val partialPercent: Int = 0,
    val left: Int = 0,
    val keepAwake: Boolean = true,
    val thumbVersion: Int = 0,
)

class DoneActions(
    val onBack: () -> Unit,
    val onNo: () -> Unit,
    val onUpload: (List<ObjectInfo>) -> Unit,
    val onSeeUploads: () -> Unit,
    val onReconnect: () -> Unit,
    val onKeepAwake: (Boolean) -> Unit,
    val size: (ObjectInfo) -> String,
)

@Composable
fun DownloadDoneScreen(ui: DoneUi, a: DoneActions) {
    val c = MaterialTheme.colorScheme
    var picking by remember(ui.id) { mutableStateOf(false) }
    var chosen by remember(ui.id) { mutableStateOf(emptySet<Long>()) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (picking) { picking = false; chosen = emptySet() } else a.onBack() }) {
                Icon(
                    painterResource(if (picking) R.drawable.ic_close else R.drawable.ic_back),
                    contentDescription = if (picking) "Annulla" else "Torna alla galleria", tint = c.onSurface,
                )
            }
            Text(if (picking) "Scegli cosa caricare" else "Download", Modifier.padding(start = 4.dp), color = c.onSurface, style = type(22, 28))
        }

        if (ui.dropped) {
            DroppedPart(ui, a)
        } else if (!picking) {
            // summary
            Row(
                Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(c.primaryContainer).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(28.dp), tint = c.onPrimary)
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        if (ui.saved.size == 1) "1 file sul telefono" else "${ui.saved.size} file sul telefono",
                        color = c.onPrimaryContainer, style = type(28, 36, medium = true),
                    )
                    Text(ui.summary, color = c.onPrimaryContainer, style = type(14, 20))
                    if (ui.failed > 0) {
                        Text(
                            if (ui.failed == 1) "1 file non scaricato: è nel registro" else "${ui.failed} file non scaricati: sono nel registro",
                            color = c.onPrimaryContainer, style = type(14, 20, medium = true),
                        )
                    }
                }
            }
            when (ui.immich) {
                DoneImmich.Ask -> AskCard(onNo = a.onNo, onPick = { picking = true; chosen = emptySet() })
                DoneImmich.Queued -> Row(
                    Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(c.tertiaryContainer).padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_cloud_queue), null, Modifier.size(22.dp), tint = c.onTertiaryContainer)
                    Text(
                        (if (ui.queuedCount == 1) "1 file in coda" else "${ui.queuedCount} file in coda") +
                            " per Immich: partono appena c'è la rete giusta",
                        Modifier.weight(1f).padding(start = 12.dp), color = c.onTertiaryContainer, style = type(14, 20),
                    )
                    FujiButton("Vedi", a.onSeeUploads, kind = ButtonKind.Text, height = 40.dp, contentColor = c.onTertiaryContainer)
                }
                DoneImmich.Declined -> Text(
                    "Niente su Immich. Puoi caricarle più tardi dalla galleria: selezionale e tocca Carica.",
                    Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(c.surfaceContainer).padding(16.dp),
                    color = c.onSurfaceVariant, style = type(14, 20),
                )
                DoneImmich.None -> Unit
            }
        } else {
            Text(
                "Tocca i file da mandare su Immich. Quelli che ha già vengono saltati.",
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp), color = c.onSurfaceVariant, style = type(14, 20),
            )
        }

        // list header
        val all = ui.saved.isNotEmpty() && chosen.size == ui.saved.size
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (!picking) "File" else when (chosen.size) { 0 -> "Nessuno scelto"; 1 -> "1 scelto"; else -> "${chosen.size} scelti" },
                Modifier.weight(1f), color = c.onSurface, style = type(16, 24, medium = true),
            )
            if (picking) {
                FujiButton(
                    if (all) "Deseleziona tutti" else "Seleziona tutti",
                    { chosen = if (all) emptySet() else ui.saved.mapTo(HashSet()) { it.handle } },
                    kind = ButtonKind.Text, height = 40.dp, contentColor = c.tertiary,
                )
            } else {
                Text(
                    if (ui.dropped) "${ui.saved.size} di ${ui.batchSize}" else "${ui.saved.size} completati",
                    Modifier.padding(end = 12.dp), color = c.onSurfaceVariant, style = type(13, 18, medium = true),
                )
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(ui.saved, key = { it.handle }) { info ->
                val on = info.handle in chosen
                DoneRow(info, a.size(info), picking, on, ui.thumbVersion) {
                    chosen = if (on) chosen - info.handle else chosen + info.handle
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp)) {
            if (picking) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupButton(
                        "Annulla", first = true, container = c.surfaceContainerHighest, content = c.onSurface,
                        onClick = { picking = false; chosen = emptySet() }, modifier = Modifier.weight(1f), height = 56.dp, textSize = 14,
                    )
                    val mb = ui.saved.filter { it.handle in chosen }.sumOf { it.size }
                    GroupButton(
                        when (chosen.size) {
                            0 -> "Scegli i file"
                            1 -> "Carica 1 file · " + formatMb(mb)
                            else -> "Carica ${chosen.size} file · " + formatMb(mb)
                        },
                        first = false, container = if (chosen.isEmpty()) c.surfaceContainerHighest else c.tertiary,
                        content = if (chosen.isEmpty()) c.onSurfaceVariant else c.onTertiary,
                        onClick = {
                            a.onUpload(ui.saved.filter { it.handle in chosen })
                            picking = false
                        },
                        modifier = Modifier.weight(1.6f), enabled = chosen.isNotEmpty(), height = 56.dp, textSize = 14,
                    )
                }
            } else if (!ui.dropped) {
                FujiButton("Torna alla galleria", a.onBack, Modifier.fillMaxWidth(), kind = ButtonKind.Filled)
            }
        }
    }
}

@Composable
private fun AskCard(onNo: () -> Unit, onPick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(c.tertiaryContainer).padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.tertiary), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_cloud_upload), null, Modifier.size(22.dp), tint = c.onTertiary)
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text("Caricarli anche su Immich?", color = c.onTertiaryContainer, style = type(18, 24, medium = true))
                Text(
                    "Partono appena c'è la rete giusta, anche ad app chiusa. Quelli che Immich ha già vengono saltati.",
                    Modifier.padding(top = 2.dp), color = c.onTertiaryContainer, style = type(14, 20),
                )
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GroupButton(
                "Non ora", first = true, container = c.surface, content = c.onSurface,
                onClick = onNo, modifier = Modifier.weight(1f), height = 48.dp, textSize = 14,
            )
            GroupButton(
                "Carica file", first = false, container = c.tertiary, content = c.onTertiary,
                onClick = onPick, modifier = Modifier.weight(1.6f), height = 48.dp, textSize = 14,
            )
        }
    }
}

/** The camera switched its Wi-Fi off during the batch: what is safe, what is left, and how to go on. */
@Composable
private fun DroppedPart(ui: DoneUi, a: DoneActions) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(c.surfaceContainer).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("${ui.saved.size}", Modifier.alignByBaseline(), color = c.onSurface, style = type(40, 48, medium = true))
            Text(" di ${ui.batchSize}", Modifier.weight(1f).alignByBaseline(), color = c.onSurface, style = type(22, 28))
        }
        Text("Interrotto", Modifier.padding(top = 4.dp), color = c.onSurfaceVariant, style = type(14, 20))
    }
    Column(
        Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(c.errorContainer).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.error), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_wifi_off), null, Modifier.size(22.dp), tint = c.onError)
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text("La camera ha spento il Wi‑Fi", color = c.onErrorContainer, style = type(18, 24, medium = true))
                Text(
                    if (ui.droppedScreenOff) "È successo mentre lo schermo del telefono era spento." else "È successo durante il download.",
                    Modifier.padding(top = 2.dp), color = c.onErrorContainer, style = type(14, 20),
                )
            }
        }
        Column(Modifier.padding(start = 58.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.saved.isNotEmpty()) {
                Bullet(R.drawable.ic_check, if (ui.saved.size == 1) "1 file è già sul telefono" else "${ui.saved.size} file sono già sul telefono")
            }
            if (ui.partialName.isNotEmpty()) Bullet(R.drawable.ic_retry, "${ui.partialName} riparte dal ${ui.partialPercent}%")
            val rest = ui.left - (if (ui.partialName.isNotEmpty()) 1 else 0)
            if (rest > 0) Bullet(R.drawable.ic_download, if (rest == 1) "1 altro file da scaricare" else "$rest altri file da scaricare")
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GroupButton(
                "Più tardi", first = true, container = c.surface, content = c.onSurface,
                onClick = a.onBack, modifier = Modifier.weight(1f), height = 48.dp, textSize = 14,
            )
            GroupButton(
                "Riconnetti e riprendi", first = false, container = c.primary, content = c.onPrimary,
                onClick = a.onReconnect, modifier = Modifier.weight(1.5f), height = 48.dp, textSize = 14, icon = R.drawable.ic_retry,
            )
        }
    }
    KeepAwakeRow(ui.keepAwake, a.onKeepAwake, Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
}

/** "Tieni lo schermo acceso · Durante i download, a luce minima". */
@Composable
fun KeepAwakeRow(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainer)
            .clickable(role = Role.Switch) { onChange(!on) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tieni lo schermo acceso", color = c.onSurface, style = type(16, 22))
            Text("Durante i download, a luce minima", color = c.onSurfaceVariant, style = type(13, 18))
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            thumbContent = if (on) {
                { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(SwitchDefaults.IconSize)) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun Bullet(icon: Int, text: String) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = c.onErrorContainer)
        Text(text, Modifier.padding(start = 8.dp), color = c.onErrorContainer, style = type(14, 20))
    }
}

@Composable
private fun DoneRow(info: ObjectInfo, size: String, picking: Boolean, on: Boolean, thumbVersion: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (on) 20.dp else 12.dp)
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(shape)
            .then(if (picking) Modifier.background(if (on) c.tertiaryContainer else c.surface).clickable(role = Role.Checkbox, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            val bmp = remember(info.handle, thumbVersion) { Thumbs.get(info)?.asImageBitmap() }
            if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(painterResource(R.drawable.ic_photos), null, Modifier.size(22.dp), tint = c.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(info.filename, color = c.onSurface, style = type(16, 22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(size, color = c.onSurfaceVariant, style = type(14, 20))
        }
        if (picking) {
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .then(if (on) Modifier.background(c.tertiary) else Modifier.border(2.dp, c.outline, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = c.onTertiary)
            }
        } else {
            Box(Modifier.size(32.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = "Sul telefono", Modifier.size(16.dp), tint = c.onPrimary)
            }
        }
    }
}

private fun formatMb(b: Long): String =
    if (b >= 100_000_000) "${b / 1_000_000} MB" else String.format(java.util.Locale.ITALY, "%.1f MB", b / 1e6)
