package com.fujixfer.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the offline preview shows: the file's name and date, its thumbnail (if any) and its details. */
class PreviewUi(val name: String, val whenText: String, val bitmap: Bitmap?, val details: DetailsUi)

/**
 * Full-screen preview when there is nothing to fetch from the camera (design "Anteprima"): the thumbnail on black,
 * the details in a panel at the bottom. A layer of MainActivity (the ComposeView previewArea), not a dialog.
 */
@Composable
fun PreviewScreen(ui: PreviewUi, onBack: () -> Unit, onSelect: () -> Boolean, onDownload: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bmp = remember(ui.bitmap) { ui.bitmap?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    Image(bmp, contentDescription = "Anteprima", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Text("Anteprima non disponibile", color = Color(0xB3FFFFFF), style = type(14, 20))
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surfaceContainerLow)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetHandle() }
                DetailsContent(ui.details, onSelect, onDownload)
            }
        }
        // top bar over the picture
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(Color(0x73000000)).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Indietro", tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(ui.name, color = Color.White, style = type(16, 24, medium = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.whenText, color = Color(0xC7FFFFFF), style = type(12, 16))
            }
        }
    }
}
