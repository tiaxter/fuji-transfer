package com.fujixfer.app

import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ connect

/** What the connect screen shows (MainActivity.updateConnect fills it). */
data class ConnectUi(
    val useWifi: Boolean = true,
    /** Not connected and not busy: the buttons and fields work. */
    val canConnect: Boolean = true,
    val cameraName: String = "Fujifilm",
    val pairTag: String = "",
    val hint: String = "",
    val paired: Boolean = false,
    /** What the app knows of the camera's Wi-Fi link right now ("Wi-Fi non collegato", "Wi-Fi in avvio", ...). */
    val wifiTag: String = "",
    /** The app's own link to the camera's Wi-Fi is up (the dot beside [wifiTag] is green). */
    val wifiOn: Boolean = false,
    /** The "Backup" card: Immich set up or not, and what it does ("Carichi tu dalla galleria · 3 in coda"). */
    val backupLinked: Boolean = false,
    val backupSub: String = "",
    /** "Ultima sessione": when ("Ieri, 18:42") and what came of it; empty = no session yet. */
    val lastWhen: String = "",
    val lastText: String = "",
    /** Files of the listing saved at the last connection: "Apri la galleria salvata" (0 = none saved). */
    val cachedCount: Int = 0,
    val manualOpen: Boolean = false,
    val usbStatus: String = "Nessuna camera collegata",
)

class ConnectActions(
    val onWifi: () -> Unit,
    val onUsb: () -> Unit,
    val onConnect: () -> Unit,
    val onRepair: () -> Unit,
    val onForget: () -> Unit,
    val onManualToggle: () -> Unit,
    val onManualConnect: () -> Unit,
    val onLog: () -> Unit,
    val onShareLog: () -> Unit,
    val onImmich: () -> Unit,
    val onSavedGallery: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

/** A 48 dp tonal round icon button of the top bar. */
@Composable
private fun TopIcon(icon: Int, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = c.onSurface)
    }
}

@Composable
fun ConnectScreen(
    ui: ConnectUi,
    ssid: String,
    onSsid: (String) -> Unit,
    pass: String,
    onPass: (String) -> Unit,
    a: ConnectActions,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, top = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Fuji Transfer", Modifier.weight(1f), color = c.onSurface, style = type(24, 32, medium = true))
            TopIcon(R.drawable.ic_log, "Registro tecnico", a.onLog)
            Spacer(Modifier.width(8.dp))
            TopIcon(R.drawable.ic_settings, "Impostazioni", a.onSettings)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp),
        ) {
            Segmented(ui.useWifi, ui.canConnect, a.onWifi, a.onUsb)
            if (ui.useWifi) WifiBody(ui, ssid, onSsid, pass, onPass, a) else UsbBody(ui)
        }

        // footer
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)) {
            FujiButton(
                if (ui.useWifi) "Connetti" else "Connetti via USB", a.onConnect, Modifier.fillMaxWidth(),
                kind = ButtonKind.Filled, enabled = ui.canConnect, height = 64.dp,
                icon = if (ui.useWifi) R.drawable.ic_wifi else R.drawable.ic_usb,
            )
            if (ui.useWifi) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    FujiButton("Abbina nuova camera", a.onRepair, Modifier.weight(1f), kind = ButtonKind.Text, enabled = ui.canConnect)
                    FujiButton(
                        if (ui.manualOpen) "Chiudi manuale" else "Connessione manuale", a.onManualToggle, Modifier.weight(1f),
                        kind = ButtonKind.Text,
                    )
                }
            }
        }
    }
}

/** Wi-Fi / USB connected pair: the chosen button widens a little with a springy overshoot and goes fully round. */
@Composable
private fun Segmented(useWifi: Boolean, enabled: Boolean, onWifi: () -> Unit, onUsb: () -> Unit) {
    val overshoot = remember { OvershootInterpolator(1.6f) }
    val animate = ValueAnimator.areAnimatorsEnabled()
    val w by animateFloatAsState(
        targetValue = if (useWifi) 1.15f else 1f,
        animationSpec = if (animate) tween(450, easing = Easing { overshoot.getInterpolation(it) }) else snap(),
        label = "tabs",
    )
    Row(Modifier.fillMaxWidth().height(48.dp)) {
        SegButton("Wi-Fi", useWifi, enabled, first = true, onClick = onWifi, modifier = Modifier.weight(w.coerceAtLeast(0.1f)))
        Spacer(Modifier.width(2.dp))
        SegButton("Cavo USB", !useWifi, enabled, first = false, onClick = onUsb, modifier = Modifier.weight((2.15f - w).coerceAtLeast(0.1f)))
    }
}

@Composable
private fun SegButton(text: String, selected: Boolean, enabled: Boolean, first: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val outer by animateDpAsState(if (pressed) 12.dp else 24.dp, tween(if (pressed) 120 else 220), label = "outer")
    val inner by animateDpAsState(if (selected && !pressed) 24.dp else 8.dp, tween(220), label = "inner")
    val shape = if (first) RoundedCornerShape(topStart = outer, topEnd = inner, bottomEnd = inner, bottomStart = outer)
    else RoundedCornerShape(topStart = inner, topEnd = outer, bottomEnd = outer, bottomStart = inner)
    val content = if (selected) c.onPrimary else c.onSurface
    Box(
        modifier.fillMaxSize().clip(shape).background(if (selected) c.primary else c.surfaceContainerHighest)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = content)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = content, style = type(14, 20, medium = true), maxLines = 1)
        }
    }
}

/** The camera's circle (88 dp), with a small round badge on its lower right edge, ringed with the card colour. */
@Composable
private fun HeroCircle(icon: Int, badge: Int? = null) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(96.dp)) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(c.primaryContainer).padding(24.dp)) {
            Icon(painterResource(icon), null, Modifier.fillMaxSize(), tint = c.onPrimaryContainer)
        }
        if (badge != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(36.dp).clip(CircleShape).background(c.surfaceContainer).padding(3.dp)
                    .clip(CircleShape).background(c.tertiary).padding(7.dp),
            ) {
                Icon(painterResource(badge), null, Modifier.fillMaxSize(), tint = c.onTertiary)
            }
        }
    }
}

@Composable
private fun WifiBody(ui: ConnectUi, ssid: String, onSsid: (String) -> Unit, pass: String, onPass: (String) -> Unit, a: ConnectActions) {
    val c = MaterialTheme.colorScheme
    var howOpen by rememberSaveable { mutableStateOf(false) }
    // the camera: circle, name, pairing chip and what the app knows of its Wi-Fi
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroCircle(R.drawable.ic_camera, badge = if (ui.paired) R.drawable.ic_bluetooth else null)
        Text(
            "Camera", Modifier.padding(top = 10.dp), color = c.onSurfaceVariant,
            style = type(12, 16).copy(letterSpacing = 0.06.em),
        )
        Text(ui.cameraName, color = c.onSurface, textAlign = TextAlign.Center, style = type(32, 40, medium = true))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(c.secondaryContainer).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_bluetooth), null, Modifier.size(16.dp), tint = c.onSecondaryContainer)
                Text(ui.pairTag, Modifier.padding(start = 6.dp), color = c.onSecondaryContainer, style = type(14, 20, medium = true), maxLines = 1)
            }
            if (ui.wifiTag.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ui.wifiOn) c.primary else c.outline))
                    Text(ui.wifiTag, Modifier.padding(start = 6.dp), color = c.onSurfaceVariant, style = type(14, 20), maxLines = 1)
                }
            }
        }
        if (ui.hint.isNotEmpty()) {
            Text(ui.hint, Modifier.padding(top = 12.dp), color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(14, 20))
        }
    }

    // what the last connection left: files downloaded, still for Immich, and the saved list to browse offline
    if (ui.lastWhen.isNotEmpty() || ui.cachedCount > 0) {
        Column(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
                .padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ultima sessione", Modifier.weight(1f), color = c.primary, style = type(14, 20, medium = true))
                if (ui.lastWhen.isNotEmpty()) Text(ui.lastWhen, color = c.onSurfaceVariant, style = type(13, 18))
            }
            if (ui.lastText.isNotEmpty()) {
                Text(ui.lastText, Modifier.padding(top = 4.dp), color = c.onSurface, style = type(16, 24))
            }
            if (ui.cachedCount > 0) {
                FujiButton(
                    "Apri la galleria salvata · ${java.text.NumberFormat.getIntegerInstance().format(ui.cachedCount)} file", a.onSavedGallery,
                    Modifier.padding(top = 12.dp).fillMaxWidth(), kind = ButtonKind.Tonal, icon = R.drawable.ic_photos,
                )
            }
        }
    }

    Box(Modifier.padding(top = 12.dp)) { BackupCard(ui.backupLinked, ui.backupSub, a.onImmich) }

    // "Come funziona": folded away, the steps behind a chevron
    val turn by animateFloatAsState(if (howOpen) 180f else 0f, tween(200), label = "chevron")
    Row(
        Modifier.padding(top = 4.dp).height(48.dp).clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = { howOpen = !howOpen }).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Come funziona", color = c.primary, style = type(14, 20, medium = true))
        Icon(
            painterResource(R.drawable.ic_chevron_down), null,
            Modifier.padding(start = 4.dp).size(20.dp).graphicsLayer { rotationZ = turn }, tint = c.primary,
        )
    }
    if (howOpen) {
        Column(Modifier.animateContentSize()) {
            HowStep(1, "Trova la camera via Bluetooth", "Anche se è già abbinata a XApp")
            HowStep(2, "Le fa accendere il Wi‑Fi", "Nome e password arrivano da soli")
            HowStep(3, "Scegli le foto e scaricale", "Se il segnale cade, riprende da dove era")
            if (ui.paired) {
                FujiButton(
                    "Dimentica la camera abbinata", a.onForget, kind = ButtonKind.Text, enabled = ui.canConnect,
                    height = 40.dp, contentColor = c.onSurfaceVariant,
                )
            }
        }
    }

    // manual connection (Wi-Fi started from the camera menu)
    if (ui.manualOpen) {
        Column(
            Modifier.padding(top = 4.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer).padding(16.dp),
        ) {
            Text("Connessione manuale", Modifier.padding(bottom = 4.dp), color = c.primary, style = type(14, 20, medium = true))
            Text(
                "Per quando il Wi-Fi è stato acceso dal menu della camera.", Modifier.padding(bottom = 12.dp),
                color = c.onSurfaceVariant, style = type(14, 20),
            )
            FujiField(ssid, onSsid, "Nome rete (vuoto = Wi-Fi già collegato)", ui.canConnect, Modifier.fillMaxWidth())
            FujiField(pass, onPass, "Password della rete (se richiesta)", ui.canConnect, Modifier.padding(top = 8.dp).fillMaxWidth(), password = true)
            FujiButton(
                "Connetti senza Bluetooth", a.onManualConnect, Modifier.padding(top = 12.dp).fillMaxWidth(),
                kind = ButtonKind.Tonal, enabled = ui.canConnect,
            )
        }
    }
}

@Composable
private fun HowStep(n: Int, title: String, sub: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.secondaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", color = c.onSecondaryContainer, style = type(16, 24, medium = true))
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, color = c.onSurface, style = type(16, 24))
            Text(sub, color = c.onSurfaceVariant, style = type(14, 20))
        }
    }
}

/** "Backup su Immich" (not set up: Collega) or "Immich · collegato" (Caricamenti), one tappable card. */
@Composable
private fun BackupCard(linked: Boolean, sub: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (linked) c.tertiaryContainer else c.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (linked) c.tertiary else c.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_immich), null, Modifier.size(24.dp), tint = if (linked) c.onTertiary else c.onSurfaceVariant)
        }
        val fg = if (linked) c.onTertiaryContainer else c.onSurface
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(if (linked) "Immich · collegato" else "Backup su Immich", color = fg, style = type(16, 22, medium = true))
            Text(
                if (linked) sub else "Facoltativo: copia le foto sul tuo server",
                color = if (linked) c.onTertiaryContainer else c.onSurfaceVariant, style = type(14, 20),
            )
        }
        Box(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (linked) c.tertiary else c.primary)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (linked) "Invii" else "Collega", color = if (linked) c.onTertiary else c.onPrimary,
                style = type(14, 20, medium = true),
            )
        }
    }
}

@Composable
private fun UsbBody(ui: ConnectUi) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
            .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroCircle(R.drawable.ic_usb)
        Text("Collega il cavo", Modifier.padding(top = 16.dp), color = c.onSurface, style = type(24, 30))
        Text(
            "Sulla camera scegli una modalità PTP o tethering, non «lettore di schede». " +
                "Android chiederà il permesso di accedere alla camera.",
            Modifier.padding(top = 12.dp), color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(14, 20),
        )
        val tag = RoundedCornerShape(8.dp)
        Box(
            Modifier.padding(top = 16.dp).height(32.dp).clip(tag).border(1.dp, c.outlineVariant, tag).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(ui.usbStatus, color = c.onSurfaceVariant, style = type(14, 20, medium = true))
        }
    }
}

// ------------------------------------------------------------------ connecting

data class ConnectingUi(
    val usb: Boolean = false,
    /** 0 Bluetooth and pairing, 1 the camera's Wi-Fi, 2 the phone joins it, 3 list of the photos. */
    val step: Int = 0,
    val title: String = "",
    val icon: Int = R.drawable.ic_bluetooth,
    /** When this attempt started (wall clock, ms): the screen counts the seconds from here. 0 = unknown. */
    val startedAt: Long = 0,
    /** When the step in progress started. */
    val stepStartedAt: Long = 0,
    /** How long each step took in this attempt (ms), null = not done or skipped. */
    val took: List<Long?> = listOf(null, null, null, null),
    /** How long each step usually takes on this phone (ms, from the last connections), null = not known yet. */
    val typical: List<Long?> = listOf(null, null, null, null),
    /** "di solito 25–30 s in tutto" from the last connections, "" = not known yet. */
    val typicalTotal: String = "",
    /** Longest total of the last connections (ms): the bar fills towards it. 0 = not known: the bar only sweeps. */
    val typicalMax: Long = 0,
    /** The elenco delle foto checks against a saved one. */
    val hasCache: Boolean = false,
    /** Files of the saved listing: "Sfoglia intanto la galleria salvata" (0 = none). */
    val cachedCount: Int = 0,
)

private val connectSteps = listOf(
    "Bluetooth e abbinamento",
    "Wi-Fi della camera",
    "Collegamento alla rete",
    "Elenco delle foto",
)

private fun stepSub(k: Int, step: Int, hasCache: Boolean): String = when (k) {
    0 -> if (k < step) "Camera trovata, abbinamento riconosciuto" else "Cerco la camera, anche se è abbinata a XApp"
    1 -> if (k < step) "La camera ha acceso il suo Wi-Fi" else "Chiedo alla camera di accendere il suo Wi-Fi"
    2 -> if (k < step) "Telefono collegato alla camera" else "Android si collega alla camera"
    else -> if (hasCache) "Solo le novità rispetto all'elenco salvato" else "Leggo cosa c'è sulla scheda"
}

/** "5,6 s", "12 s", "1 min 4 s". */
internal fun seconds(ms: Long, decimals: Boolean = false): String {
    val s = ms / 1000.0
    return when {
        s >= 60 -> "${(s / 60).toInt()} min ${(s % 60).toInt()} s"
        decimals && s < 10 -> String.format(java.util.Locale.ITALY, "%.1f s", s)
        else -> "${s.toInt()} s"
    }
}

/** The wall clock, ticking every half second while shown (for the "12 s trascorsi" counters). */
@Composable
internal fun tickingNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    return now
}

@Composable
fun ConnectingScreen(ui: ConnectingUi, onCancel: () -> Unit, onBrowse: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val now = tickingNow()
    val step = ui.step.coerceIn(0, 3)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Annulla", tint = c.onSurface)
            }
            Text("Connessione", Modifier.padding(start = 4.dp), color = c.onSurface, style = type(22, 28))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // the wavy ring around the circle of what is being done
            Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                WavyBar(0, 100, c.primary, c.secondaryContainer, Modifier.fillMaxSize(), indeterminate = true)
                Box(Modifier.size(88.dp).clip(CircleShape).background(c.primaryContainer).padding(24.dp)) {
                    Icon(painterResource(ui.icon), null, Modifier.fillMaxSize(), tint = c.onPrimaryContainer)
                }
            }
            Text(ui.title, Modifier.padding(top = 12.dp), color = c.onSurface, textAlign = TextAlign.Center, style = type(24, 32, medium = true))
            if (!ui.usb) {
                val usual = ui.typical.getOrNull(step)?.let { " · di solito circa ${seconds(it)}" } ?: ""
                Text(
                    "Passo ${step + 1} di 4$usual", Modifier.padding(top = 2.dp),
                    color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(14, 20),
                )
            }

            // seconds so far, against what the last connections took
            if (ui.startedAt > 0) {
                val elapsed = (now - ui.startedAt).coerceAtLeast(0)
                Column(Modifier.padding(top = 14.dp).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("${seconds(elapsed)} trascorsi", Modifier.weight(1f), color = c.onSurface, style = type(13, 18, medium = true))
                        if (ui.typicalTotal.isNotEmpty()) Text(ui.typicalTotal, color = c.onSurfaceVariant, style = type(13, 18))
                    }
                    val known = ui.typicalMax > 0
                    WavyBar(
                        if (known) (elapsed * 1000 / ui.typicalMax).toInt().coerceIn(0, 950) else 0, 1000,
                        c.primary, c.secondaryContainer, Modifier.padding(top = 6.dp).fillMaxWidth().height(16.dp),
                        indeterminate = !known,
                    )
                }
            }

            if (!ui.usb) {
                Column(
                    Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
                        .animateContentSize().padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    connectSteps.forEachIndexed { k, title ->
                        val time = when {
                            k < step -> ui.took.getOrNull(k)?.let { seconds(it, decimals = true) } ?: ""
                            k == step -> ui.typical.getOrNull(k)?.let { "~${seconds(it)}" }
                                ?: if (ui.stepStartedAt > 0) seconds((now - ui.stepStartedAt).coerceAtLeast(0)) else ""
                            else -> ui.typical.getOrNull(k)?.let { "~${seconds(it)}" } ?: ""
                        }
                        StepRow(k, step, title, stepSub(k, step, ui.hasCache), time)
                    }
                }
                // Android's own dialog for the camera's network is the one thing the user has to do
                Row(
                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.tertiaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_info), null, Modifier.padding(top = 1.dp).size(22.dp), tint = c.onTertiaryContainer)
                    Text(
                        buildAnnotatedString {
                            append("Se Android chiede di collegarsi alla rete della camera, tocca ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append("Connetti") }
                            append(".")
                        },
                        Modifier.padding(start = 12.dp), color = c.onTertiaryContainer, style = type(14, 20),
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (ui.cachedCount > 0) {
                FujiButton(
                    "Sfoglia intanto la galleria salvata", onBrowse, Modifier.padding(bottom = 8.dp).fillMaxWidth(),
                    kind = ButtonKind.Tonal, height = 56.dp, icon = R.drawable.ic_photos,
                )
            }
            FujiButton("Annulla", onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.Outlined)
        }
    }
}

@Composable
private fun StepRow(k: Int, step: Int, title: String, sub: String, time: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            k < step -> Box(Modifier.size(36.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = c.onPrimary)
            }
            k == step -> Box(Modifier.size(36.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(c.onPrimaryContainer))
            }
            else -> Box(Modifier.size(36.dp).border(2.dp, c.outline, CircleShape))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, color = if (k > step) c.onSurfaceVariant else c.onSurface, style = type(16, 22, medium = true))
            Text(sub, color = c.onSurfaceVariant, style = type(13, 18))
        }
        if (time.isNotEmpty()) {
            Text(time, color = if (k == step) c.primary else c.onSurfaceVariant, style = type(13, 18, medium = true), maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ error

/**
 * What the error screen shows. Title and tips come from [errorUiFor], which only reads the message the connection
 * actually failed with: it names what went wrong, never a guessed cause.
 */
data class ErrorUi(
    val title: String = "Connessione non riuscita",
    val message: String = "",
    val icon: Int = R.drawable.ic_wifi_off,
    val tips: List<Pair<Int, String>> = emptyList(),
    val log: String = "",
    /** The steps of this attempt, in order, and the one that failed (filled by MainActivity). */
    val events: List<ConnectEvent> = emptyList(),
)

/** One line of "Cosa è successo": a step done (ok) or the one that failed, with its time of day ("15:42:07"). */
data class ConnectEvent(val ok: Boolean, val text: String, val time: String)

private val TIP_NEAR = R.drawable.ic_bluetooth to "Tieni il telefono vicino alla camera, accesa"
private val TIP_USB = R.drawable.ic_usb to "Se il cavo USB è collegato alla camera, scollegalo"
private val TIP_APPS = R.drawable.ic_tip_close_apps to "Chiudi le altre app che usano la camera, come XApp"

/** Builds the error screen from the failure message ([status], as the service wrote it) and the last log lines. */
fun errorUiFor(status: String, log: String): ErrorUi {
    val msg = status.removePrefix("Connessione non riuscita:").trim()
        .replaceFirstChar { it.uppercase() }
        .let { if (it.isEmpty() || it.endsWith(".") || it.endsWith(")")) it else "$it." }
    val m = msg.lowercase()
    return when {
        "non ha acceso il wi-fi" in m || "avvio del wi-fi" in m || "nome della rete" in m -> ErrorUi(
            "La camera non ha acceso il Wi-Fi", msg, R.drawable.ic_wifi_off, listOf(TIP_USB, TIP_APPS, TIP_NEAR), log,
        )
        "non risulta abbinata" in m || "abbinamento" in m || "non sembra una fotocamera" in m || "identificazione" in m -> ErrorUi(
            "Abbinamento non riconosciuto", msg, R.drawable.ic_bluetooth,
            listOf(
                R.drawable.ic_camera to "Sulla camera: Bluetooth › Registrazione abbinamento, poi «Abbina nuova camera»",
                TIP_NEAR,
            ),
            log,
        )
        "dispositivi nelle vicinanze" in m || "permesso" in m || "geolocalizzazione" in m -> ErrorUi(
            "Manca un permesso", msg, R.drawable.ic_lock,
            listOf(R.drawable.ic_phone to "Impostazioni › App › Fuji Transfer › Autorizzazioni"), log,
        )
        "bluetooth" in m -> ErrorUi(
            "Camera non raggiunta via Bluetooth", msg, R.drawable.ic_bluetooth,
            listOf(TIP_NEAR, R.drawable.ic_phone to "Controlla che il Bluetooth del telefono sia acceso", TIP_APPS), log,
        )
        "usb" in m || "bulk" in m -> ErrorUi(
            "Camera USB non raggiungibile", msg, R.drawable.ic_usb,
            listOf(
                R.drawable.ic_usb to "Scollega e ricollega il cavo, con la camera accesa",
                R.drawable.ic_phone to "Se Android chiede l'accesso alla camera USB, consentilo",
            ),
            log,
        )
        "rete" in m -> ErrorUi(
            "Rete della camera non raggiunta", msg, R.drawable.ic_wifi_off,
            listOf(
                R.drawable.ic_wifi to "Se Android chiede di collegarsi alla rete della camera, tocca Connetti",
                TIP_NEAR, TIP_APPS,
            ),
            log,
        )
        "sessione" in m || "closed the connection" in m || "cannot connect" in m || "accesso alle foto" in m ||
            "init reply" in m -> ErrorUi(
            "La camera non ha aperto la sessione", msg, R.drawable.ic_camera, listOf(TIP_APPS, TIP_NEAR), log,
        )
        else -> ErrorUi("Connessione non riuscita", msg, R.drawable.ic_wifi_off, listOf(TIP_NEAR, TIP_USB, TIP_APPS), log)
    }
}

@Composable
fun ErrorScreen(ui: ErrorUi, onBack: () -> Unit, onCopy: () -> Unit, onRetry: () -> Unit, onRepair: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Indietro", tint = c.onSurface)
            }
            Text("Connessione", Modifier.padding(start = 4.dp), color = c.onSurface, style = type(22, 28))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp),
        ) {
            // icon and title side by side, the message below
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(c.errorContainer).padding(17.dp)) {
                    Icon(painterResource(ui.icon), null, Modifier.fillMaxSize(), tint = c.onErrorContainer)
                }
                Text(
                    ui.title, Modifier.padding(start = 16.dp).weight(1f),
                    color = c.onSurface, style = type(24, 30, medium = true),
                )
            }
            if (ui.message.isNotEmpty() && ui.message != ui.title + ".") {
                Text(ui.message, Modifier.padding(top = 12.dp), color = c.onSurfaceVariant, style = type(15, 22))
            }

            // what this attempt got through, with the time of each step, and where it stopped
            if (ui.events.isNotEmpty()) {
                Column(
                    Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
                        .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 8.dp),
                ) {
                    Text("Cosa è successo", color = c.primary, style = type(14, 20, medium = true))
                    ui.events.forEach { e -> EventRow(e) }
                }
            }

            if (ui.tips.isNotEmpty()) {
                Column(
                    Modifier.padding(top = 12.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                        .background(c.surfaceContainer).padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 8.dp),
                ) {
                    Text("Cose che a volte aiutano", color = c.primary, style = type(14, 20, medium = true))
                    ui.tips.forEach { (icon, text) -> Tip(icon, text) }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            FujiButton("Riprova", onRetry, Modifier.fillMaxWidth(), kind = ButtonKind.Filled, height = 60.dp, icon = R.drawable.ic_retry)
            Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FujiButton(
                    "Copia i dettagli tecnici", onCopy, Modifier.weight(1f), kind = ButtonKind.Outlined,
                    icon = R.drawable.ic_copy, contentColor = c.onSurface,
                )
                FujiButton("Abbina nuova camera", onRepair, Modifier.weight(1f), kind = ButtonKind.Text)
            }
        }
    }
}

@Composable
private fun EventRow(e: ConnectEvent) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (e.ok) c.primary else c.error),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (e.ok) R.drawable.ic_check else R.drawable.ic_close), null,
                Modifier.size(if (e.ok) 16.dp else 14.dp), tint = if (e.ok) c.onPrimary else c.onError,
            )
        }
        Text(e.text, Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), color = c.onSurface, style = type(14, 20))
        Text(
            e.time, color = c.onSurfaceVariant,
            style = type(12, 16).copy(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun Tip(icon: Int, text: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.secondaryContainer).padding(7.dp)) {
            Icon(painterResource(icon), null, Modifier.fillMaxSize(), tint = c.onSecondaryContainer)
        }
        Text(text, Modifier.padding(start = 12.dp), color = c.onSurface, style = type(14, 20))
    }
}
