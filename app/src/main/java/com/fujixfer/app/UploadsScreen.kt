package com.fujixfer.app

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the "Caricamenti" screen shows, read from [ImmichSync] about once a second. */
private class UploadsSnapshot(
    val rows: List<ImmichSync.Row> = emptyList(),
    val running: Boolean = false,
    val paused: Boolean = false,
    val configured: Boolean = true,
    val wifiOnly: Boolean = true,
    val anyOnce: Boolean = false,
    val blocked: String = "",
    /** The phone is on a network the queue may use now (internet, and unmetered if "solo Wi-Fi"). */
    val networkOk: Boolean = false,
    val unmetered: Boolean = false,
    val runIndex: Int = 0,
    val runFiles: Int = 0,
    val runBytesDone: Long = 0,
    val runBytesTotal: Long = 0,
    val runStartMs: Long = 0,
    val currentDone: Long = 0,
    val currentTotal: Long = 0,
)

private fun snapshot(c: Context): UploadsSnapshot {
    val s = ImmichSync.settings(c)
    val cm = c.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    val anyOnce = ImmichSync.anyNetworkOnce(c)
    return UploadsSnapshot(
        rows = ImmichSync.rows(c),
        running = ImmichSync.running,
        paused = ImmichSync.isPaused(c),
        configured = s.configured,
        wifiOnly = s.wifiOnly,
        anyOnce = anyOnce,
        blocked = ImmichSync.blocked(c),
        networkOk = internet && (unmetered || !s.wifiOnly || anyOnce),
        unmetered = unmetered,
        runIndex = ImmichSync.runIndex,
        runFiles = ImmichSync.runFiles,
        runBytesDone = ImmichSync.runBytesDone,
        runBytesTotal = ImmichSync.runBytesTotal,
        runStartMs = ImmichSync.runStartMs,
        currentDone = ImmichSync.currentDone,
        currentTotal = ImmichSync.currentTotal,
    )
}

private enum class UploadTab { All, Todo, Done }

/**
 * "Caricamenti" (design "Caricamenti · file per file" and "in attesa del Wi‑Fi"): the run going on (files, bytes,
 * speed, time left) or why the queue is waiting, then every file: going up, refused (with "Riprova"), queued,
 * not on the phone yet, uploaded, or "C'era già" (the server had the same bytes). "Annulla tutti" empties the
 * queue; "Pausa" stops after the file going up.
 * [thumb]: the camera thumbnail of a file ("name|date"), when the app has it.
 */
@Composable
fun UploadsScreen(onBack: () -> Unit, onSettings: () -> Unit, thumb: (String) -> Bitmap?) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(UploadsSnapshot()) }
    var tick by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(UploadTab.All) }
    LaunchedEffect(tick) {
        while (true) {
            snap = withContext(Dispatchers.IO) { snapshot(ctx) }
            delay(800)
        }
    }
    fun refresh() { tick++ }

    val todoStates = setOf(ImmichSync.RowState.Running, ImmichSync.RowState.Failed, ImmichSync.RowState.Queued, ImmichSync.RowState.NotOnPhone)
    val todo = snap.rows.count { it.state in todoStates }
    val doneN = snap.rows.size - todo
    val errors = snap.rows.any { it.state == ImmichSync.RowState.Failed }
    val shown = when (tab) {
        UploadTab.All -> snap.rows
        UploadTab.Todo -> snap.rows.filter { it.state in todoStates }
        UploadTab.Done -> snap.rows.filter { it.state !in todoStates }
    }
    val waitingFiles = snap.rows.filter { it.state == ImmichSync.RowState.Queued }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Indietro", tint = c.onSurface)
            }
            Text("Caricamenti", Modifier.weight(1f).padding(start = 4.dp), color = c.onSurface, style = type(22, 28))
            FujiButton("Impostazioni", onSettings, kind = ButtonKind.Text, height = 40.dp)
        }

        when {
            !snap.configured -> NoteCard(
                "Immich non è collegato", "Collega il server per caricare le foto.", R.drawable.ic_immich, error = false,
                action = "Collega", onAction = onSettings,
            )
            snap.blocked.isNotEmpty() -> NoteCard(
                "Il server ha rifiutato l'accesso", snap.blocked, R.drawable.ic_lock, error = true,
                action = "Impostazioni", onAction = onSettings,
            )
            snap.running -> RunCard(snap)
            snap.paused && todo > 0 -> NoteCard(
                "In pausa", (if (todo == 1) "1 file aspetta" else "$todo file aspettano") + ": tocca Riprendi per continuare.",
                R.drawable.ic_cloud_queue, error = false,
            )
            waitingFiles.isNotEmpty() && !snap.networkOk -> {
                val bytes = waitingFiles.sumOf { it.size.coerceAtLeast(0) }
                val what = (if (waitingFiles.size == 1) "1 file" else "${waitingFiles.size} file") + ", " + formatBytes(bytes)
                NoteCard(
                    if (snap.wifiOnly && !snap.anyOnce) "In attesa del Wi‑Fi" else "In attesa della rete",
                    "$what. Partono da soli appena il telefono è su una rete " +
                        (if (snap.wifiOnly && !snap.anyOnce) "Wi‑Fi" else "con internet") + ", anche ad app chiusa.",
                    R.drawable.ic_cloud_queue, error = false,
                    action = if (snap.wifiOnly && !snap.anyOnce) "Carica ora con i dati mobili" else null,
                    onAction = { ImmichSync.uploadNowAnyNetwork(ctx); refresh() },
                )
            }
            waitingFiles.isNotEmpty() -> NoteCard(
                "Partono a breve", "Il telefono è in rete: Android avvia i caricamenti appena può.",
                R.drawable.ic_cloud_queue, error = false,
            )
        }

        // Tutti / Da fare / Fatti
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabChip("Tutti ${snap.rows.size}", tab == UploadTab.All, dot = false) { tab = UploadTab.All }
            TabChip("Da fare $todo", tab == UploadTab.Todo, dot = errors) { tab = UploadTab.Todo }
            TabChip("Fatti $doneN", tab == UploadTab.Done, dot = false) { tab = UploadTab.Done }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Text(
                    when (tab) {
                        UploadTab.All -> "Niente da caricare. In galleria scegli le foto e tocca Carica."
                        UploadTab.Todo -> "Niente in coda."
                        UploadTab.Done -> "Nessun file caricato finora."
                    },
                    Modifier.align(Alignment.Center).padding(32.dp), color = c.onSurfaceVariant, style = type(14, 20),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(shown, key = { it.state.name + "|" + it.key }) { row ->
                    UploadRow(row, snap, thumb(row.key)) { ImmichSync.retry(ctx, row.key); refresh() }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GroupButton(
                "Annulla tutti", first = true, container = c.surfaceContainerHighest, content = c.onSurface,
                onClick = { ImmichSync.dequeueAll(ctx); refresh() }, modifier = Modifier.weight(1f),
                enabled = todo > 0 && !(snap.running && todo == 1), height = 56.dp, textSize = 14,
            )
            GroupButton(
                if (snap.paused) "Riprendi" else "Pausa", first = false,
                container = c.primary, content = c.onPrimary,
                onClick = { ImmichSync.setPaused(ctx, !snap.paused); refresh() }, modifier = Modifier.weight(1f),
                enabled = todo > 0 || snap.paused, height = 56.dp, textSize = 14,
                icon = if (snap.paused) R.drawable.ic_play else null,
            )
        }
    }
}

/** "3 di 7", "74 di 320 MB", wavy bar, network, speed, time left. */
@Composable
private fun RunCard(s: UploadsSnapshot) {
    val c = MaterialTheme.colorScheme
    val elapsed = (System.currentTimeMillis() - s.runStartMs).coerceAtLeast(1)
    val speed = s.runBytesDone * 1000.0 / elapsed // bytes per second
    val left = (s.runBytesTotal - s.runBytesDone).coerceAtLeast(0)
    val eta = if (speed > 1000 && s.runBytesDone > 0) etaText(left / speed) else "…"
    val progress = if (s.runBytesTotal > 0) (s.runBytesDone * 1000 / s.runBytesTotal).toInt().coerceIn(0, 1000) else 0
    Column(
        Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(c.tertiaryContainer).padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("${s.runIndex}", Modifier.alignByBaseline(), color = c.onTertiaryContainer, style = type(40, 48))
            Text(" di ${s.runFiles}", Modifier.weight(1f).alignByBaseline(), color = c.onTertiaryContainer, style = type(22, 28))
            Text(
                "${formatBytes(s.runBytesDone)} di ${formatBytes(s.runBytesTotal)}", Modifier.alignByBaseline(),
                color = c.onTertiaryContainer, style = type(14, 20, medium = true),
            )
        }
        WavyBar(
            progress = progress, max = 1000, color = c.tertiary, track = c.surfaceContainerLowest,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(14.dp),
        )
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_wifi), null, Modifier.size(18.dp), tint = c.onTertiaryContainer)
            Text(if (s.unmetered) "Wi‑Fi" else "Dati mobili", Modifier.padding(start = 6.dp), color = c.onTertiaryContainer, style = type(14, 20))
            Spacer(Modifier.width(16.dp))
            Icon(painterResource(R.drawable.ic_speed), null, Modifier.size(18.dp), tint = c.onTertiaryContainer)
            Text(
                if (speed > 1000) String.format(java.util.Locale.ITALY, "%.1f MB/s", speed / 1e6) else "…",
                Modifier.padding(start = 6.dp), color = c.onTertiaryContainer, style = type(14, 20),
            )
            Spacer(Modifier.width(16.dp))
            Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(18.dp), tint = c.onTertiaryContainer)
            Text(eta, Modifier.padding(start = 6.dp), color = c.onTertiaryContainer, style = type(14, 20))
        }
    }
}

@Composable
private fun NoteCard(
    title: String, text: String, icon: Int, error: Boolean, action: String? = null, onAction: () -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    val bg = if (error) c.errorContainer else c.tertiaryContainer
    val fg = if (error) c.onErrorContainer else c.onTertiaryContainer
    Column(
        Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(bg).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (error) c.error else c.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), null, Modifier.size(22.dp), tint = if (error) c.onError else c.onTertiary)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = fg, style = type(18, 24, medium = true))
                Text(text, Modifier.padding(top = 2.dp), color = fg, style = type(14, 20))
            }
        }
        if (action != null) {
            FujiButton(
                action, onAction, Modifier.padding(top = 14.dp).fillMaxWidth(), kind = ButtonKind.Filled, height = 48.dp,
            )
        }
    }
}

@Composable
private fun TabChip(label: String, on: Boolean, dot: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (on) 16.dp else 8.dp)
    Row(
        Modifier.height(32.dp).clip(shape)
            .then(if (on) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outlineVariant, shape))
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(c.error))
        Text(label, color = if (on) c.onSecondaryContainer else c.onSurface, style = type(14, 20, medium = true))
    }
}

@Composable
private fun UploadRow(row: ImmichSync.Row, s: UploadsSnapshot, bmp: Bitmap?, onRetry: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val size = if (row.size >= 0) formatBytes(row.size) else ""
    val sub = when (row.state) {
        ImmichSync.RowState.Running ->
            if (s.currentTotal > 0) "${formatBytes(s.currentDone)} di ${formatBytes(s.currentTotal)}" else "Confronto con Immich…"
        ImmichSync.RowState.Failed -> row.note.replaceFirstChar { it.uppercase() }
        ImmichSync.RowState.Queued -> size
        ImmichSync.RowState.NotOnPhone -> "Non ancora sul telefono: parte dopo il download"
        ImmichSync.RowState.Done -> if (size.isEmpty()) "Caricato" else "$size · caricato"
        ImmichSync.RowState.Skipped -> if (size.isEmpty()) "Stessi byte già su Immich" else "$size · stessi byte già su Immich"
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).alpha(if (row.state == ImmichSync.RowState.Skipped) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            val img = remember(bmp) { bmp?.asImageBitmap() }
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(painterResource(R.drawable.ic_photos), null, Modifier.size(22.dp), tint = c.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(row.name, color = c.onSurface, style = type(16, 22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sub, color = if (row.state == ImmichSync.RowState.Failed) c.error else c.onSurfaceVariant,
                style = type(14, 20), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        when (row.state) {
            ImmichSync.RowState.Running -> {
                val pct = if (s.currentTotal > 0) (s.currentDone * 100 / s.currentTotal).toInt().coerceIn(0, 100) else 0
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    WavyBar(pct, 100, c.tertiary, c.surfaceContainerHigh, Modifier.size(40.dp))
                    Text("$pct", color = c.onSurface, style = type(11, 14, medium = true))
                }
            }
            ImmichSync.RowState.Failed ->
                FujiButton("Riprova", onRetry, kind = ButtonKind.Tonal, height = 40.dp, icon = R.drawable.ic_retry)
            ImmichSync.RowState.Queued, ImmichSync.RowState.NotOnPhone ->
                Text(if (s.paused) "In pausa" else if (s.running || s.networkOk) "In coda" else "In attesa",
                    color = c.onSurfaceVariant, style = type(14, 20))
            ImmichSync.RowState.Done -> Box(
                Modifier.size(32.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = "Caricato", Modifier.size(18.dp), tint = c.onPrimary)
            }
            ImmichSync.RowState.Skipped -> Text("C'era già", color = c.onSurfaceVariant, style = type(14, 20, medium = true))
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> String.format(java.util.Locale.ITALY, "%.1f GB", b / 1e9)
    b >= 100_000_000 -> "${b / 1_000_000} MB"
    else -> String.format(java.util.Locale.ITALY, "%.1f MB", b / 1e6)
}

private fun etaText(seconds: Double): String = when {
    seconds < 60 -> "circa ${((seconds / 10).toInt() + 1) * 10} s"
    seconds < 3600 -> "circa ${(seconds / 60).toInt() + 1} min"
    else -> "più di un'ora"
}
