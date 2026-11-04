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
)

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
            Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Fuji Transfer", Modifier.weight(1f), color = c.onSurface, style = type(22, 28, medium = true))
            IconButton(onClick = a.onImmich) {
                Icon(painterResource(R.drawable.ic_cloud_upload), contentDescription = "Invia a Immich", tint = c.onSurfaceVariant)
            }
            IconButton(onClick = a.onShareLog) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = "Condividi il registro", tint = c.onSurfaceVariant)
            }
            IconButton(onClick = a.onLog) {
                Icon(painterResource(R.drawable.ic_log), contentDescription = "Registro tecnico", tint = c.onSurfaceVariant)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp),
        ) {
            Segmented(ui.useWifi, ui.canConnect, a.onWifi, a.onUsb)
            if (ui.useWifi) WifiBody(ui, ssid, onSsid, pass, onPass, a) else UsbBody(ui)
        }

        // footer
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            FujiButton(
                if (ui.useWifi) "Connetti" else "Connetti via USB", a.onConnect, Modifier.fillMaxWidth(),
                kind = ButtonKind.Filled, enabled = ui.canConnect,
            )
            if (ui.useWifi) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    FujiButton("Abbina nuova camera", a.onRepair, kind = ButtonKind.Text, enabled = ui.canConnect)
                    FujiButton(if (ui.manualOpen) "Chiudi manuale" else "Connessione manuale", a.onManualToggle, kind = ButtonKind.Text)
                }
            }
            if (ui.paired && ui.useWifi) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    FujiButton(
                        "Dimentica camera", a.onForget, kind = ButtonKind.Text, enabled = ui.canConnect,
                        height = 40.dp, contentColor = c.onSurfaceVariant,
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

@Composable
private fun HeroCircle(icon: Int) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(104.dp).clip(CircleShape).background(c.primaryContainer).padding(26.dp)) {
        Icon(painterResource(icon), null, Modifier.fillMaxSize(), tint = c.onPrimaryContainer)
    }
}

@Composable
private fun WifiBody(ui: ConnectUi, ssid: String, onSsid: (String) -> Unit, pass: String, onPass: (String) -> Unit, a: ConnectActions) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
            .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroCircle(R.drawable.ic_camera)
        Text(
            "Camera", Modifier.padding(top = 16.dp), color = c.onSurfaceVariant,
            style = type(12, 16, medium = true).copy(letterSpacing = 0.04.em),
        )
        Text(ui.cameraName, color = c.onSurface, textAlign = TextAlign.Center, style = TextStyle(fontSize = 32.sp, lineHeight = 40.sp))
        Row(
            Modifier.padding(top = 16.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(c.secondaryContainer)
                .padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_bluetooth), null, Modifier.size(18.dp), tint = c.onSecondaryContainer)
            Text(ui.pairTag, Modifier.padding(start = 6.dp), color = c.onSecondaryContainer, style = type(14, 20, medium = true))
        }
        Text(ui.hint, Modifier.padding(top = 12.dp), color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(14, 20))
    }

    Text(
        "Come funziona", Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp),
        color = c.primary, style = type(14, 20, medium = true),
    )
    HowStep(R.drawable.ic_bluetooth, "Trova la camera via Bluetooth", "Anche se è già abbinata a XApp")
    HowStep(R.drawable.ic_wifi, "Le fa accendere il Wi-Fi", "Nome e password arrivano da soli")
    HowStep(R.drawable.ic_photos, "Scegli le foto e scaricale", "Se il segnale cade, riprende da dove era")

    // manual connection (Wi-Fi started from the camera menu)
    if (ui.manualOpen) {
        Column(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp),
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
private fun HowStep(icon: Int, title: String, sub: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.secondaryContainer).padding(10.dp)) {
            Icon(painterResource(icon), null, Modifier.fillMaxSize(), tint = c.onSecondaryContainer)
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = c.onSurface, style = type(16, 24))
            Text(sub, color = c.onSurfaceVariant, style = type(14, 20))
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
    /** 0 looking for the camera, 1 pairing recognised, 2 camera Wi-Fi, 3 session and list. */
    val step: Int = 0,
    val title: String = "",
    val detail: String = "",
    val icon: Int = R.drawable.ic_bluetooth,
)

private val connectSteps = listOf(
    "Cerca la camera via Bluetooth" to "Anche se è abbinata a XApp",
    "Abbinamento riconosciuto" to "Lo stesso di XApp, senza rifarlo",
    "Wi-Fi della camera" to "Se Android chiede di collegarsi alla camera, accetta",
    "Elenco delle foto" to "",
)

@Composable
fun ConnectingScreen(ui: ConnectingUi, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
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
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 24.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
                WavyBar(0, 100, c.primary, c.secondaryContainer, Modifier.fillMaxSize(), indeterminate = true)
                Box(Modifier.size(128.dp).clip(CircleShape).background(c.primaryContainer).padding(36.dp)) {
                    Icon(painterResource(ui.icon), null, Modifier.fillMaxSize(), tint = c.onPrimaryContainer)
                }
            }
            Text(ui.title, Modifier.padding(top = 24.dp), color = c.onSurface, textAlign = TextAlign.Center, style = type(24, 30))
            Text(
                ui.detail, Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(14, 20),
            )
            if (!ui.usb) {
                Column(
                    Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
                        .animateContentSize().padding(vertical = 8.dp),
                ) {
                    val step = ui.step.coerceIn(0, 3)
                    connectSteps.forEachIndexed { k, (title, sub) -> StepRow(k, step, title, sub) }
                }
            }
        }

        FujiButton("Annulla", onCancel, Modifier.padding(16.dp).fillMaxWidth(), kind = ButtonKind.Outlined)
    }
}

@Composable
private fun StepRow(k: Int, step: Int, title: String, sub: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            when {
                k < step -> Box(Modifier.size(32.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = c.onPrimary)
                }
                else -> Box(Modifier.size(32.dp).border(2.dp, c.outline, CircleShape), contentAlignment = Alignment.Center) {
                    if (k == step) Box(Modifier.size(10.dp).clip(CircleShape).background(c.primary))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                title, color = if (k > step) c.onSurfaceVariant else c.onSurface,
                style = type(16, 24, medium = k == step),
            )
            if (sub.isNotEmpty() && k <= step) Text(sub, color = c.onSurfaceVariant, style = type(14, 20))
            if (k == step) {
                WavyBar(0, 100, c.primary, c.secondaryContainer, Modifier.padding(top = 4.dp).fillMaxWidth().height(12.dp), indeterminate = true)
            }
        }
    }
}

// ------------------------------------------------------------------ error

data class ErrorUi(val message: String = "", val log: String = "")

@Composable
fun ErrorScreen(ui: ErrorUi, onBack: () -> Unit, onShare: () -> Unit, onRetry: () -> Unit, onRepair: () -> Unit) {
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
                .padding(start = 16.dp, top = 16.dp, end = 16.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(88.dp).clip(CircleShape).background(c.errorContainer).padding(22.dp),
            ) {
                Icon(painterResource(R.drawable.ic_wifi_off), null, Modifier.fillMaxSize(), tint = c.onErrorContainer)
            }
            Text(
                "Connessione non riuscita", Modifier.padding(top = 16.dp).fillMaxWidth(),
                color = c.onSurface, textAlign = TextAlign.Center, style = type(24, 30),
            )
            Text(
                ui.message, Modifier.padding(top = 8.dp).fillMaxWidth(),
                color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(16, 24),
            )

            Column(
                Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer)
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    "Cosa puoi provare", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    color = c.primary, style = type(14, 20, medium = true),
                )
                Tip(R.drawable.ic_bluetooth, "Tieni il telefono vicino alla camera, accesa")
                Tip(R.drawable.ic_usb, "Scollega il cavo USB dalla camera")
                Tip(R.drawable.ic_tip_close_apps, "Chiudi le altre app che usano la camera, come XApp")
            }

            Column(
                Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHighest)
                    .padding(start = 16.dp, top = 6.dp, end = 8.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Registro tecnico", Modifier.weight(1f), color = c.onSurface, style = type(14, 20, medium = true))
                    FujiButton("Condividi", onShare, kind = ButtonKind.Text, height = 40.dp, icon = R.drawable.ic_share)
                }
                SelectionContainer {
                    Text(
                        ui.log, Modifier.padding(end = 8.dp), color = c.onSurfaceVariant,
                        style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            FujiButton("Riprova", onRetry, Modifier.fillMaxWidth(), kind = ButtonKind.Filled)
            FujiButton("Abbina nuova camera", onRepair, Modifier.padding(top = 8.dp).fillMaxWidth(), kind = ButtonKind.Text)
        }
    }
}

@Composable
private fun Tip(icon: Int, text: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = c.onSurfaceVariant)
        Text(text, Modifier.padding(start = 16.dp), color = c.onSurface, style = type(14, 20))
    }
}
