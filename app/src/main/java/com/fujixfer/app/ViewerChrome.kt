package com.fujixfer.app

import android.app.Dialog
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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

@Composable
fun ViewerBar(ui: ViewerTopUi, onBack: () -> Unit, onHd: () -> Unit, onSelect: () -> Unit, onInfo: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Color(0x73000000)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Indietro", tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(ui.name, color = Color.White, style = type(16, 24, medium = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ui.note, color = Color(0xC7FFFFFF), style = type(12, 16), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ui.hd) {
            Box(
                Modifier.padding(end = 4.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x33FFFFFF))
                    .clickable(role = Role.Button, onClick = onHd).padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Scarica la foto originale" },
                contentAlignment = Alignment.Center,
            ) {
                Text("Originale", color = Color.White, style = type(14, 20, medium = true), maxLines = 1)
            }
        }
        // select this file for the download, as in the gallery: ring = not chosen, green check = chosen
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onSelect)
                .semantics { contentDescription = if (ui.selected) "Selezionata, tocca per togliere" else "Seleziona per il download" },
            contentAlignment = Alignment.Center,
        ) {
            if (ui.selected) {
                Icon(
                    painterResource(R.drawable.ic_check), contentDescription = null,
                    modifier = Modifier.size(24.dp).background(c.primary, CircleShape).border(2.dp, c.background, CircleShape).padding(3.dp),
                    tint = c.onPrimary,
                )
            } else {
                Box(Modifier.size(24.dp).background(Color(0x2E000000), CircleShape).border(2.dp, Color.White, CircleShape))
            }
        }
        IconButton(onClick = onInfo) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = "Dettagli", tint = Color.White)
        }
    }
}

data class ViewerLoadUi(
    val status: String = "Scarico l'originale…",
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
