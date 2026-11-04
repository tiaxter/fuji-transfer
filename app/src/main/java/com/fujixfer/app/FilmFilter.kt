package com.fujixfer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A film simulation found in the gallery and how many shots use it. */
data class FilmOption(val name: String, val count: Int)

/**
 * The symbols of the film simulations, as the camera and XApp show them (taken from XApp, for personal use only:
 * they are Fujifilm's). The key is the name that Exif.filmName / Exif.monochrome give. Studio Portrait has no
 * symbol of its own: null, and the caller falls back to the generic film icon.
 */
object Films {
    fun icon(name: String): Int? = when (name) {
        "Provia" -> R.drawable.film_provia
        "Velvia" -> R.drawable.film_velvia
        "Astia" -> R.drawable.film_astia
        "Classic Chrome" -> R.drawable.film_cc
        "Classic Neg." -> R.drawable.film_cneg
        "Nostalgic Neg." -> R.drawable.film_nneg
        "Pro Neg. Std" -> R.drawable.film_pregs
        "Pro Neg. Hi" -> R.drawable.film_pnegh
        "Eterna" -> R.drawable.film_eterna
        "Eterna Bleach Bypass" -> R.drawable.film_bb
        "Reala Ace" -> R.drawable.film_reala
        "Acros" -> R.drawable.film_acros
        "Acros +R" -> R.drawable.film_acrosr
        "Acros +Ye" -> R.drawable.film_acrosy
        "Acros +G" -> R.drawable.film_acrosg
        "Monocromo" -> R.drawable.film_bw
        "Monocromo +R" -> R.drawable.film_bwr
        "Monocromo +Ye" -> R.drawable.film_bwy
        "Monocromo +G" -> R.drawable.film_bwg
        "Seppia" -> R.drawable.film_sepia
        else -> null
    }
}

/**
 * Bottom sheet to choose one or more film simulations. [options] are the ones present in the gallery (most used
 * first), [initial] the ones already on. [onApply] gets the chosen set; empty = no film filter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmSheet(options: List<FilmOption>, initial: Set<String>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit, scanning: Boolean = false) {
    val c = MaterialTheme.colorScheme
    var sel by remember { mutableStateOf(initial) }
    val total = options.filter { it.name in sel }.sumOf { it.count }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Pellicola", color = c.onSurface, style = type(22, 28))
            Text(
                when {
                    scanning -> "Sto ancora leggendo le foto: l'elenco può crescere"
                    options.isEmpty() -> "Nessuna simulazione letta finora"
                    else -> "Scegli una o più simulazioni"
                },
                Modifier.padding(top = 2.dp), color = c.onSurfaceVariant, style = type(14, 20),
            )
            Column(
                Modifier.padding(top = 12.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { o ->
                    FilmRow(o, o.name in sel) { sel = if (o.name in sel) sel - o.name else sel + o.name }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                GroupButton(
                    "Azzera", first = true, container = c.secondaryContainer, content = c.onSecondaryContainer,
                    onClick = { sel = emptySet() }, modifier = Modifier.weight(1f), enabled = sel.isNotEmpty(),
                )
                Box(Modifier.size(2.dp))
                GroupButton(
                    if (sel.isEmpty()) "Mostra tutte" else "Mostra $total foto", first = false,
                    container = c.primary, content = c.onPrimary, onClick = { onApply(sel) }, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One film: round symbol, name, how many shots, a check when chosen. The chosen row is a tonal pill. */
@Composable
private fun FilmRow(o: FilmOption, on: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp))
            .background(if (on) c.secondaryContainer else Color.Transparent)
            .clickable(role = Role.Checkbox, onClick = onClick).padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (on) c.primary else c.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(Films.icon(o.name) ?: R.drawable.ic_film), null, Modifier.size(20.dp),
                tint = if (on) c.onPrimary else c.onSecondaryContainer,
            )
        }
        Text(
            o.name, Modifier.weight(1f).padding(start = 16.dp),
            color = if (on) c.onSecondaryContainer else c.onSurface, style = type(16, 24), maxLines = 1,
        )
        Text("${o.count}", color = c.onSurfaceVariant, style = type(14, 20))
        if (on) {
            Icon(painterResource(R.drawable.ic_check), null, Modifier.padding(start = 12.dp).size(20.dp), tint = c.onSecondaryContainer)
        } else {
            Box(Modifier.padding(start = 12.dp).size(20.dp))
        }
    }
}
