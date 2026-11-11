package com.fujixfer.app

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The parts of the gallery around the grid of photos: the top (bar, error banner, filter chips, work bar) and the
// bottom (download strip, selection bar). The grid itself is still a ListView in activity_main.xml.

data class ChipUi(val label: String, val selected: Boolean)

data class GalleryTopUi(
    val title: String = "",
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
    /** The files come from the listing saved at the last connection, not from the camera (hollow dot). */
    val cached: Boolean = false,
    /** The card over the saved gallery: the camera being connected, or not connected; null = none. */
    val link: GalleryLink? = null,
)

/** "Collego la camera… 14 s" (counted from [startedAt], 0 = no counter) with its action ("Annulla", "Connetti"). */
data class GalleryLink(val title: String, val sub: String, val startedAt: Long, val action: String)

class GalleryTopActions(
    val onBack: () -> Unit,
    val onSelectAll: () -> Unit,
    val onBanner: () -> Unit,
    val onDate: () -> Unit,
    val onClearDate: () -> Unit,
    val onArea: () -> Unit,
    val onClearArea: () -> Unit,
    val onFilter: (Int) -> Unit,
    val onFilm: () -> Unit = {},
    val onClearFilm: () -> Unit = {},
    val onLink: () -> Unit = {},
)

@Composable
fun GalleryTop(ui: GalleryTopUi, a: GalleryTopActions) {
    val c = MaterialTheme.colorScheme
    val selectAllText = stringResource(if (ui.allSelected) R.string.deselect_all else R.string.select_all)
    Column(Modifier.fillMaxWidth()) {
        // top bar: leave the camera, name and connection, select all (no overflow menu: the log is in
        // Settings, Immich in the selection bar, leaving the camera is the arrow)
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = a.onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.disconnect), tint = c.onSurface)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(ui.title, color = c.onSurface, style = type(22, 28), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // green = the camera's own list; hollow = the saved one
                    Box(
                        Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape)
                            .then(if (ui.cached) Modifier.border(2.dp, c.outline, CircleShape) else Modifier.background(c.primary)),
                    )
                    Text(ui.info, color = c.onSurfaceVariant, style = type(13, 16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (ui.allSelected) c.secondaryContainer else c.surfaceContainerHighest)
                    .clickable(role = Role.Button, onClick = a.onSelectAll)
                    .semantics { contentDescription = selectAllText },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_select_all), null, Modifier.size(24.dp),
                    tint = if (ui.allSelected) c.onSecondaryContainer else c.onSurface,
                )
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
                if (ui.dated) ui.dateLabel else stringResource(R.string.filter_date), ui.dated, R.drawable.ic_calendar,
                if (ui.dated) R.drawable.ic_close else R.drawable.ic_chevron_down, onClick = a.onDate,
                onTrailing = if (ui.dated) a.onClearDate else null, trailingDescription = stringResource(R.string.filter_date_clear),
            )
            FilterChip(
                if (ui.area) ui.areaLabel else stringResource(R.string.filter_place), ui.area, R.drawable.ic_map,
                if (ui.area) R.drawable.ic_close else null, onClick = a.onArea,
                onTrailing = if (ui.area) a.onClearArea else null, trailingDescription = stringResource(R.string.filter_place_clear),
            )
            FilterChip(
                if (ui.film) ui.filmLabel else stringResource(R.string.filter_film), ui.film, R.drawable.ic_film,
                if (ui.film) R.drawable.ic_close else R.drawable.ic_chevron_down, onClick = a.onFilm,
                onTrailing = if (ui.film) a.onClearFilm else null, trailingDescription = stringResource(R.string.filter_film_clear),
            )
            Box(Modifier.width(1.dp).height(24.dp).background(c.outlineVariant))
            ui.chips.forEachIndexed { i, chip ->
                FilterChip(chip.label, chip.selected, if (chip.selected) R.drawable.ic_check else null, null, onClick = { a.onFilter(i) })
            }
        }

        // over the saved gallery: the connection being made (or the way to make one)
        ui.link?.let { LinkCard(it, a.onLink) }

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

@Composable
private fun LinkCard(link: GalleryLink, onAction: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val now = tickingNow()
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().height(64.dp)
            .clip(RoundedCornerShape(20.dp)).background(c.secondaryContainer).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (link.startedAt > 0) {
            WavyBar(0, 100, c.primary, c.surfaceContainerHighest, Modifier.size(32.dp), indeterminate = true)
        } else {
            Icon(painterResource(R.drawable.ic_wifi_off), null, Modifier.size(24.dp), tint = c.onSecondaryContainer)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val secs = if (link.startedAt > 0) " " + seconds((now - link.startedAt).coerceAtLeast(0)) else ""
            Text(link.title + secs, color = c.onSecondaryContainer, style = type(14, 20, medium = true), maxLines = 1)
            Text(link.sub, color = c.onSecondaryContainer, style = type(12, 16), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FujiButton(link.action, onAction, kind = ButtonKind.Text, height = 48.dp)
    }
}

/**
 * Filter chip: outlined with 10 dp corners, or a tonal pill when on. [leading] is the icon before the text, [trailing] the one
 * after it; with [onTrailing] the trailing icon is its own button (the x of a set filter), the rest of the chip
 * still opens the filter.
 */
@Composable
internal fun FilterChip(
    label: String, selected: Boolean, leading: Int?, trailing: Int?, onClick: () -> Unit,
    onTrailing: (() -> Unit)? = null, trailingDescription: String? = null,
) {
    val c = MaterialTheme.colorScheme
    // guideline: chips not on have 10 dp corners, chips on are pills; the corners move with a light bounce
    val radius by animateDpAsState(
        if (selected) 16.dp else 10.dp,
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "chip",
    )
    val shape = RoundedCornerShape(radius)
    val content = if (selected) c.onSecondaryContainer else c.onSurfaceVariant
    Row(
        Modifier.height(32.dp).animateContentSize().clip(shape)
            .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier.border(1.5.dp, c.outlineVariant, shape))
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
    val selTitle: String = "",
    val selInfo: String = "",
    val canDownload: Boolean = false,
    /** "Immich": the selection goes to Immich (downloaded first where needed). */
    val canUpload: Boolean = false,
    /** After "Immich", with nothing selected: "5 foto in coda per Immich" with Annulla ("" = none). */
    val snack: String = "",
    /** Second line of [snack]: when the queued files go up. */
    val snackSub: String = "",
    /** A tip above the selection bar ("Tieni premuto e trascina…"), empty = none. */
    val hint: String = "",
    /** The camera is not there yet: "Scarica" queues the files for when it is (clock instead of the arrow). */
    val later: Boolean = false,
)

@Composable
fun GalleryBottom(
    ui: GalleryBottomUi, onStrip: () -> Unit, onDownload: () -> Unit, onUpload: () -> Unit, onUndo: () -> Unit,
    onClear: () -> Unit = {},
) {
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
                Text(stringResource(R.string.open), Modifier.padding(start = 8.dp), color = c.onPrimaryContainer, style = type(14, 20, medium = true))
            }
        }

        if (ui.snack.isNotEmpty()) {
            // after "Immich": what happens next, and the way back
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp).heightIn(min = 64.dp)
                    .shadow(3.dp, RoundedCornerShape(20.dp)).background(c.inverseSurface, RoundedCornerShape(20.dp))
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_cloud_queue), null, Modifier.size(24.dp), tint = c.inverseOnSurface)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(ui.snack, color = c.inverseOnSurface, style = type(14, 20, medium = true), maxLines = 1)
                    Text(ui.snackSub, color = c.inverseOnSurface.copy(alpha = 0.85f), style = type(12, 16), maxLines = 2)
                }
                FujiButton(stringResource(R.string.cancel), onUndo, kind = ButtonKind.Text, height = 40.dp, contentColor = c.inversePrimary)
            }
        } else {
            if (ui.hint.isNotEmpty()) {
                Row(
                    Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp).fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(16.dp)).background(c.inverseSurface, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_select_all), null, Modifier.size(20.dp), tint = c.inverseOnSurface)
                    Text(ui.hint, Modifier.padding(start = 10.dp), color = c.inverseOnSurface, style = type(14, 20))
                }
            }
            // floating selection bar (Expressive): a pill with the count, then the joined Carica | Scarica pair
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val pill = RoundedCornerShape(32.dp)
                val any = ui.canDownload || ui.canUpload
                Row(
                    Modifier.weight(1f).height(64.dp).shadow(3.dp, pill).background(c.primaryContainer, pill)
                        .padding(start = if (any) 8.dp else 24.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (any) {
                        IconButton(onClick = onClear) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear_selection), tint = c.onPrimaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = if (any) 4.dp else 0.dp)) {
                        Text(ui.selTitle, color = c.onPrimaryContainer, style = type(16, 22, medium = true), maxLines = 1)
                        Text(
                            ui.selInfo, color = c.onPrimaryContainer.copy(alpha = 0.85f), style = type(13, 18),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(Modifier.padding(start = 8.dp)) {
                    ActionTile(
                        stringResource(R.string.tile_immich), R.drawable.ic_cloud_upload, first = true, enabled = ui.canUpload,
                        container = c.secondaryContainer, content = c.onSecondaryContainer,
                        description = stringResource(R.string.cd_send_selected), onClick = onUpload,
                    )
                    Box(Modifier.width(2.dp))
                    ActionTile(
                        if (ui.later) stringResource(R.string.tile_queue) else stringResource(R.string.tile_phone), if (ui.later) R.drawable.ic_clock else R.drawable.ic_download,
                        first = false, enabled = ui.canDownload, container = c.primary, content = c.onPrimary,
                        description = if (ui.later) stringResource(R.string.cd_download_later) else stringResource(R.string.cd_save_selected),
                        onClick = onDownload,
                    )
                }
            }
        }
    }
}

/**
 * One of the two joined buttons beside the selection bar: 60 × 64 dp, icon over a short word, 20 dp outer and
 * 8 dp inner corners; the outer ones tighten while pressed.
 */
@Composable
private fun ActionTile(
    text: String, icon: Int, first: Boolean, enabled: Boolean, container: Color, content: Color,
    description: String, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val outer by animateDpAsState(if (pressed && enabled) 12.dp else 20.dp, tween(if (pressed) 120 else 220), label = "tile")
    val shape = if (first) RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = 8.dp, bottomEnd = 8.dp)
    else RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = outer, bottomEnd = outer)
    val fg = if (enabled) content else c.onSurfaceVariant
    Column(
        Modifier.size(width = 60.dp, height = 64.dp).shadow(3.dp, shape)
            .background(if (enabled) container else c.surfaceContainerHighest, shape).clip(shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = fg)
        Text(text, Modifier.padding(top = 2.dp), color = fg, style = type(11, 14, medium = true), maxLines = 1)
    }
}
