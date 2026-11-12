package com.fujixfer.app

import androidx.compose.ui.res.stringResource
import android.app.Dialog
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// The parts of the full-size viewer that are not the picture: the top bar and the "original on its way" panel.
// The viewer is still a Dialog with the pager, the zoomable picture, the video and the film strip as Views.

/**
 * A Compose view inside a Dialog needs the lifecycle and the saved-state owners on the dialog's window, which only an
 * Activity's window has by itself. Call it after setContentView, before show.
 */
fun Dialog.composeOwners(owner: ComponentActivity) {
    val decor = window?.decorView ?: return
    decor.setViewTreeLifecycleOwner(owner)
    decor.setViewTreeSavedStateRegistryOwner(owner)
}

data class ViewerTopUi(
    val name: String = "",
    /** "3 di 20 · 4 nov 2026", or what the picture on screen is. */
    val note: String = "",
    /** Shown over a quick preview: the full photo from the camera. */
    val hd: Boolean = false,
    /** The file on screen is chosen for the download. */
    val selected: Boolean = false,
)

/**
 * The viewer's top bar (design "Rev · Visore"): back, the file name with its date, "Originale" over a quick preview
 * and the round tonal "Dettagli" button. Selecting the file is in the panel at the bottom.
 */
@Composable
fun ViewerBar(ui: ViewerTopUi, onBack: () -> Unit, onHd: () -> Unit, onInfo: () -> Unit) {
    val originalCd = stringResource(R.string.cd_original)
    val detailsCd = stringResource(R.string.details)
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Color(0x73000000)).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(ui.name, color = Color.White, style = type(18, 24, medium = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ui.note, color = Color(0xFFC9CDC9), style = type(14, 20), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ui.hd) {
            Box(
                Modifier.padding(end = 8.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x33FFFFFF))
                    .clickable(role = Role.Button, onClick = onHd).padding(horizontal = 14.dp)
                    .semantics { contentDescription = originalCd },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.original), color = Color.White, style = type(14, 20, medium = true), maxLines = 1)
            }
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF2B2E2C))
                .clickable(role = Role.Button, onClick = onInfo).semantics { contentDescription = detailsCd },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_info), null, Modifier.size(24.dp), tint = Color.White)
        }
    }
}

/**
 * The fixed panel under the viewer's strip (design "Rev · Visore"): a sheet-like surface with the handle, the name and
 * "Dettagli ›" (the full sheet), the format and film chips, size · dimensions · place, whether it is on the phone,
 * and the joined Seleziona | Scarica ora pair. A drag up on the picture pulls out the full sheet (ViewerScreen).
 */
@Composable
fun ViewerPanel(ui: DetailsUi, onDetails: () -> Unit, onSelect: () -> Unit, onDownload: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surfaceContainerLow)
            .navigationBarsPadding().padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp)).background(c.outline),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.title, Modifier.weight(1f), color = c.onSurface, style = type(24, 32, medium = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onDetails)
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.details), color = c.primary, style = type(14, 20, medium = true))
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(start = 2.dp).size(18.dp), tint = c.primary)
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.kind.isNotEmpty()) {
                Box(
                    Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(c.primaryContainer).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ui.kind, color = c.onPrimaryContainer, style = type(14, 20, medium = true), maxLines = 1)
                }
            }
            if (ui.film.isNotEmpty()) {
                val tag = RoundedCornerShape(8.dp)
                Row(
                    Modifier.height(32.dp).clip(tag).border(1.dp, c.outline, tag).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ui.filmIcon?.let { Icon(painterResource(it), null, Modifier.padding(end = 6.dp).size(18.dp), tint = c.onSurface) }
                    Text(ui.film, color = c.onSurface, style = type(14, 20, medium = true), maxLines = 1)
                }
            }
        }
        val facts = listOf(ui.size, ui.dims, ui.place).filter { it.isNotEmpty() && it != "—" }.joinToString(" · ")
        if (facts.isNotEmpty()) {
            Text(facts, Modifier.padding(top = 10.dp), color = c.onSurfaceVariant, style = type(14, 20), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            stringResource(if (ui.onPhone) R.string.viewer_on_phone else R.string.viewer_not_on_phone),
            Modifier.padding(top = if (facts.isEmpty()) 10.dp else 0.dp), color = c.onSurfaceVariant, style = type(14, 20),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            GroupButton(
                stringResource(if (ui.selected) R.string.selected_one else R.string.select), first = true,
                container = c.secondaryContainer, content = c.onSecondaryContainer, onClick = onSelect, modifier = Modifier.weight(1f),
                icon = if (ui.selected) R.drawable.ic_check else R.drawable.ic_select_all,
                description = stringResource(if (ui.selected) R.string.cd_selected_tap else R.string.cd_select_download),
            )
            Box(Modifier.size(2.dp))
            GroupButton(
                stringResource(R.string.download_now), first = false, container = c.primary, content = c.onPrimary,
                onClick = onDownload, modifier = Modifier.weight(1f), enabled = ui.canDownload && !ui.onPhone, icon = R.drawable.ic_download,
            )
        }
    }
}

data class ViewerLoadUi(
    val status: String = "",
    val progressVisible: Boolean = true,
    /** 0..1000 */
    val progress: Int = 0,
    val bytes: String = "",
    /** A video the phone could not play: what it is ("È un video H.265, 3840 × 2160") and why; "" = no error. */
    val videoWhat: String = "",
    val videoWhy: String = "",
    /** The video is not on the phone yet: the error panel offers "Scarica il video". */
    val videoCanDownload: Boolean = false,
)

/** What is on its way over the picture (preview or original): a status line, a thin wavy bar, the bytes so far. */
@Composable
fun ViewerLoading(ui: ViewerLoadUi) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xB3000000))
            .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ui.status, color = Color.White, textAlign = TextAlign.Center, style = type(16, 24))
        if (ui.progressVisible) {
            WavyBar(ui.progress, 1000, c.primary, c.secondaryContainer, Modifier.padding(top = 10.dp).fillMaxWidth().height(12.dp))
        }
        Text(ui.bytes, Modifier.padding(top = 8.dp), color = Color(0xB3FFFFFF), style = type(13, 18))
    }
}
