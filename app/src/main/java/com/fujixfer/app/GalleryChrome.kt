package com.fujixfer.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The parts of the gallery around the grid of photos: the top (bar, error banner, filter chips, work bar) and the
// bottom (download strip, selection bar). The grid itself is still a ListView in activity_main.xml.

data class ChipUi(val label: String, val selected: Boolean)

data class GalleryTopUi(
    val title: String = "Camera",
    val info: String = "",
    /** Every shown file is selected: the select-all button turns into "deselect". */
    val allSelected: Boolean = false,
    /** Error while connected (a failed download, a lost connection); empty = no banner. */
    val banner: String = "",
    val dated: Boolean = false,
    val dateLabel: String = "",
    val area: Boolean = false,
    val areaLabel: String = "",
    val film: Boolean = false,
    val filmLabel: String = "",
    /** New, All, Photo, RAW, Video, in this order. */
    val chips: List<ChipUi> = emptyList(),
    val working: Boolean = false,
    val workLabel: String = "",
    val workCount: String = "",
    /** 0..1000 */
    val workProgress: Int = 0,
    val subtitle: String = "",
)

class GalleryTopActions(
    val onBack: () -> Unit,
    val onSelectAll: () -> Unit,
    val onShareLog: () -> Unit,
    val onLog: () -> Unit,
    val onImmich: () -> Unit,
    val onDisconnect: () -> Unit,
    val onBanner: () -> Unit,
    val onDate: () -> Unit,
    val onClearDate: () -> Unit,
    val onArea: () -> Unit,
    val onClearArea: () -> Unit,
    val onFilter: (Int) -> Unit,
    val onFilm: () -> Unit = {},
    val onClearFilm: () -> Unit = {},
)

@Composable
fun GalleryTop(ui: GalleryTopUi, a: GalleryTopActions) {
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // top bar: leave the camera, name and connection, select all, more
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = a.onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Disconnetti", tint = c.onSurface)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(ui.title, color = c.onSurface, style = type(22, 28), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 4.dp).size(8.dp).clip(CircleShape).background(c.primary))
                    Text(ui.info, color = c.onSurfaceVariant, style = type(12, 16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = a.onSelectAll) {
                Icon(
                    painterResource(R.drawable.ic_select_all),
                    contentDescription = if (ui.allSelected) "Deseleziona tutto" else "Seleziona tutto",
                    tint = if (ui.allSelected) c.primary else c.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = "Altre opzioni", tint = c.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Invia a Immich") }, onClick = { menu = false; a.onImmich() })
                    DropdownMenuItem(text = { Text("Condividi il registro") }, onClick = { menu = false; a.onShareLog() })
                    DropdownMenuItem(text = { Text("Registro tecnico") }, onClick = { menu = false; a.onLog() })
                    DropdownMenuItem(text = { Text("Disconnetti") }, onClick = { menu = false; a.onDisconnect() })
                }
            }
        }

        // errors while connected
        if (ui.banner.isNotEmpty()) {
            Text(
                ui.banner,
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(c.errorContainer).clickable(role = Role.Button, onClick = a.onBanner).padding(14.dp),
                color = c.onErrorContainer, style = type(14, 20),
            )
        }

        // filter chips: date, place | new, all, photos, RAW, video
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                if (ui.dated) ui.dateLabel else "Data", ui.dated, R.drawable.ic_calendar,
                if (ui.dated) R.drawable.ic_close else R.drawable.ic_chevron_down, onClick = a.onDate,
                onTrailing = if (ui.dated) a.onClearDate else null, trailingDescription = "Togli il filtro data",
            )
            FilterChip(
                if (ui.area) ui.areaLabel else "Luogo", ui.area, R.drawable.ic_map,
                if (ui.area) R.drawable.ic_close else null, onClick = a.onArea,
                onTrailing = if (ui.area) a.onClearArea else null, trailingDescription = "Togli il filtro luogo",
            )
            FilterChip(
                if (ui.film) ui.filmLabel else "Pellicola", ui.film, R.drawable.ic_film,
                if (ui.film) R.drawable.ic_close else R.drawable.ic_chevron_down, onClick = a.onFilm,
                onTrailing = if (ui.film) a.onClearFilm else null, trailingDescription = "Togli il filtro pellicola",
            )
            Box(Modifier.width(1.dp).height(24.dp).background(c.outlineVariant))
            ui.chips.forEachIndexed { i, chip ->
                FilterChip(chip.label, chip.selected, if (chip.selected) R.drawable.ic_check else null, null, onClick = { a.onFilter(i) })
            }
        }

        // background work on the camera (list, positions): a quiet line with a thin bar
        if (ui.working) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ui.workLabel, Modifier.weight(1f), color = c.onSurfaceVariant, style = type(12, 16), maxLines = 1)
                    Text(ui.workCount, color = c.onSurfaceVariant, style = type(12, 16, medium = true))
                }
                WavyBar(ui.workProgress, 1000, c.primary, c.secondaryContainer, Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp))
            }
        }

        if (ui.subtitle.isNotEmpty()) {
            Text(
                ui.subtitle, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                color = c.onSurfaceVariant, style = type(12, 16),
            )
        }
    }
}

/**
 * Filter chip (8 dp corners): outlined, or tonal when on. [leading] is the icon before the text, [trailing] the one
 * after it; with [onTrailing] the trailing icon is its own button (the x of a set filter), the rest of the chip
 * still opens the filter.
 */
@Composable
internal fun FilterChip(
    label: String, selected: Boolean, leading: Int?, trailing: Int?, onClick: () -> Unit,
    onTrailing: (() -> Unit)? = null, trailingDescription: String? = null,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val content = if (selected) c.onSecondaryContainer else c.onSurfaceVariant
    Row(
        Modifier.height(32.dp).animateContentSize().clip(shape)
            .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outlineVariant, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (leading != null && selected && trailing == null) 8.dp else 16.dp, end = if (onTrailing != null) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(painterResource(leading), null, Modifier.size(18.dp), tint = content)
            Box(Modifier.width(6.dp))
        }
        Text(label, color = content, style = type(14, 20, medium = true), maxLines = 1)
        if (trailing != null) {
            if (onTrailing != null) {
                Box(
                    Modifier.fillMaxHeight().width(40.dp).clickable(role = Role.Button, onClick = onTrailing),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(trailing), trailingDescription, Modifier.size(18.dp), tint = content)
                }
            } else {
                Box(Modifier.width(6.dp))
                Icon(painterResource(trailing), null, Modifier.size(18.dp), tint = content)
            }
        }
    }
}

// ------------------------------------------------------------------ bottom

data class GalleryBottomUi(
    /** Downloads run while the gallery is shown: a strip brings back the download screen. */
    val strip: Boolean = false,
    val stripPercent: Int = 0,
    val stripTitle: String = "",
    val stripSub: String = "",
    val selTitle: String = "Nessuna selezionata",
    val selInfo: String = "",
    val canDownload: Boolean = false,
)

@Composable
fun GalleryBottom(ui: GalleryBottomUi, onStrip: () -> Unit, onDownload: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (ui.strip) {
            Row(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth().height(64.dp)
                    .clip(RoundedCornerShape(28.dp)).background(c.primaryContainer)
                    .clickable(role = Role.Button, onClick = onStrip).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    WavyBar(ui.stripPercent, 100, c.primary, c.secondaryContainer, Modifier.size(36.dp))
                    Text(ui.stripPercent.toString(), color = c.onPrimaryContainer, style = type(10, 12, medium = true))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(ui.stripTitle, color = c.onPrimaryContainer, style = type(14, 20, medium = true), maxLines = 1)
                    Text(ui.stripSub, color = c.onPrimaryContainer, style = type(12, 16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Apri", Modifier.padding(start = 8.dp), color = c.onPrimaryContainer, style = type(14, 20, medium = true))
            }
        }

        // floating selection bar (Expressive): a pill with the count, the download button beside it
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val pill = RoundedCornerShape(32.dp)
            Column(
                Modifier.weight(1f).height(64.dp).shadow(3.dp, pill).background(c.primaryContainer, pill)
                    .padding(start = 24.dp, end = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(ui.selTitle, color = c.onSurface, style = type(16, 24, medium = true), maxLines = 1)
                Text(ui.selInfo, color = c.onSurfaceVariant, style = type(14, 20), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DownloadFab(ui.canDownload, onDownload, Modifier.padding(start = 8.dp))
        }
    }
}

/** The download button beside the selection bar: 64 dp, 20 dp corners that tighten while pressed. */
@Composable
private fun DownloadFab(enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val radius by animateDpAsState(if (pressed && enabled) 12.dp else 20.dp, tween(if (pressed) 120 else 220), label = "fab")
    val shape = RoundedCornerShape(radius)
    val content = if (enabled) c.onPrimary else c.onSurfaceVariant
    Box(
        modifier.height(64.dp).shadow(3.dp, shape).background(if (enabled) c.primary else c.surfaceContainerHighest, shape).clip(shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 20.dp, end = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_download), null, Modifier.size(20.dp), tint = content)
            Box(Modifier.width(8.dp))
            Text("Scarica", color = content, style = type(14, 20, medium = true), maxLines = 1)
        }
    }
}
