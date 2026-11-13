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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
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

/**
 * What is on its way over the picture (preview or original): a compact pill as wide as its text, with a thin wavy
 * bar and the bytes so far only when there are some; the bar gives it a steady width so it does not jump as it fills.
 */
@Composable
fun ViewerLoading(ui: ViewerLoadUi) {
    val c = MaterialTheme.colorScheme
    val bar = ui.progressVisible
    val shape = if (bar || ui.bytes.isNotEmpty()) RoundedCornerShape(20.dp) else RoundedCornerShape(50)
    Column(
        Modifier.widthIn(min = if (bar) 220.dp else 0.dp, max = 360.dp).clip(shape).background(Color(0xB3000000))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ui.status, color = Color.White, textAlign = TextAlign.Center, style = type(14, 20, medium = true))
        if (bar) {
            WavyBar(ui.progress, 1000, c.primary, c.secondaryContainer, Modifier.padding(top = 8.dp).width(184.dp).height(10.dp))
        }
        if (ui.bytes.isNotEmpty()) {
            Text(ui.bytes, Modifier.padding(top = 6.dp), color = Color(0xB3FFFFFF), style = type(12, 16))
        }
    }
}
