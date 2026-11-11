package com.fujixfer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Screens the design added under "Schermate che mancavano": settings, first-run permissions, battery, the empty
// gallery, the low-storage dialog before a download, and the panel of a video the phone cannot play.

/** The top bar of a full screen: back arrow and title. */
@Composable
internal fun ScreenBar(title: String, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Indietro", tint = c.onSurface)
        }
        if (title.isNotEmpty()) Text(title, Modifier.padding(start = 4.dp), color = c.onSurface, style = type(22, 28))
    }
}

/** Corners of row [i] of [n] in a joined group: wide outside, [inner] between rows. */
internal fun groupShape(i: Int, n: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): RoundedCornerShape = when {
    n == 1 -> RoundedCornerShape(outer)
    i == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
    i == n - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
    else -> RoundedCornerShape(inner)
}

/** The 88 dp circle at the top of the setup screens. */
@Composable
private fun BigCircle(icon: Int, container: Color, content: Color, size: Dp = 88.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.45f), tint = content)
    }
}

// ------------------------------------------------------------------ settings

/** One row of the settings: what it does on the right ([nav] arrow, a switch, or a small button). */
class SettingRow(
    val icon: Int,
    val title: String,
    val sub: String = "",
    val onClick: (() -> Unit)? = null,
    /** Non-null: a switch in this state. */
    val switch: Boolean? = null,
    val onSwitch: (Boolean) -> Unit = {},
    /** A small tonal button ("Svuota", "Dimentica") instead of the arrow. */
    val button: String = "",
    val onButton: () -> Unit = {},
    val enabled: Boolean = true,
)

class SettingGroup(val title: String, val rows: List<SettingRow>)

/** "Impostazioni": groups of joined rows under a small coloured title. */
@Composable
fun SettingsScreen(groups: List<SettingGroup>, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ScreenBar("Impostazioni", onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            groups.forEach { g ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(g.title, Modifier.padding(start = 4.dp, bottom = 4.dp), color = c.primary, style = type(14, 20, medium = true))
                    g.rows.forEachIndexed { i, r -> SettingsRowView(r, groupShape(i, g.rows.size)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsRowView(r: SettingRow, shape: RoundedCornerShape) {
    val c = MaterialTheme.colorScheme
    val click: (() -> Unit)? = when {
        !r.enabled -> null
        r.switch != null -> { { r.onSwitch(!r.switch) } }
        else -> r.onClick
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(c.surfaceContainer)
            .then(if (click != null) Modifier.clickable(role = if (r.switch != null) Role.Switch else Role.Button, onClick = click) else Modifier)
            .padding(start = 14.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(painterResource(r.icon), null, Modifier.size(20.dp), tint = c.onSecondaryContainer)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(r.title, color = if (r.enabled) c.onSurface else c.onSurfaceVariant, style = type(16, 22))
            if (r.sub.isNotEmpty()) Text(r.sub, color = c.onSurfaceVariant, style = type(13, 18))
        }
        when {
            r.switch != null -> Switch(
                checked = r.switch, onCheckedChange = r.onSwitch, enabled = r.enabled,
                thumbContent = if (r.switch) {
                    { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
            r.button.isNotEmpty() -> FujiButton(r.button, r.onButton, kind = ButtonKind.Tonal, height = 40.dp, enabled = r.enabled)
            r.onClick != null -> Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(22.dp), tint = c.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ permissions (first start)

/**
 * "Prima di collegare la camera": the nearby-devices permission (needed) and notifications (optional), each with
 * its own "Consenti". [near]/[notif] are the permissions' state now.
 */
@Composable
fun PermissionsScreen(near: Boolean, notif: Boolean, onNear: () -> Unit, onNotif: () -> Unit, onContinue: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 72.dp, end = 20.dp),
        ) {
            Box(Modifier.padding(start = 4.dp)) { BigCircle(R.drawable.ic_camera, c.primaryContainer, c.onPrimaryContainer) }
            Text(
                "Prima di collegare la camera", Modifier.padding(start = 4.dp, top = 24.dp, end = 4.dp, bottom = 8.dp),
                color = c.onSurface, style = type(28, 36, medium = true),
            )
            Text(
                "Servono due permessi. Puoi cambiarli quando vuoi dalle impostazioni di Android.",
                Modifier.padding(horizontal = 4.dp), color = c.onSurfaceVariant, style = type(16, 24),
            )
            Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PermissionRow(
                    R.drawable.ic_bluetooth, "Dispositivi nelle vicinanze",
                    "Per trovare la camera via Bluetooth e collegarti al suo Wi‑Fi.",
                    c.secondaryContainer, c.onSecondaryContainer, near, onNear, groupShape(0, 2, 28.dp, 8.dp),
                )
                PermissionRow(
                    R.drawable.ic_bell, "Notifiche", "Per mostrarti i download che vanno avanti a schermo spento.",
                    c.tertiaryContainer, c.onTertiaryContainer, notif, onNotif, groupShape(1, 2, 28.dp, 8.dp),
                )
            }
            Text(
                "Le notifiche sono facoltative: senza, i download in background non si vedono.",
                Modifier.padding(start = 8.dp, top = 14.dp, end = 8.dp), color = c.onSurfaceVariant, style = type(13, 18),
            )
        }
        // "Continua" looks off until the nearby-devices permission is there, but still lets the user go on:
        // the permission is asked again at the first "Connetti"
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp)) {
            FujiButton(
                "Continua", onContinue, Modifier.fillMaxWidth(), kind = if (near) ButtonKind.Filled else ButtonKind.Tonal,
                height = 56.dp, contentColor = if (near) null else c.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: Int, title: String, sub: String, iconBg: Color, iconFg: Color, granted: Boolean, onAsk: () -> Unit, shape: RoundedCornerShape,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surfaceContainer).padding(start = 16.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = iconFg)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, color = c.onSurface, style = type(16, 22, medium = true))
            Text(sub, color = c.onSurfaceVariant, style = type(14, 20))
        }
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (granted) c.primaryContainer else c.primary)
                .clickable(enabled = !granted, role = Role.Button, onClick = onAsk).padding(horizontal = 16.dp)
                .semantics { contentDescription = title + if (granted) ", consentito" else ", tocca per consentire" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (granted) "Consentito" else "Consenti", color = if (granted) c.onPrimaryContainer else c.onPrimary,
                style = type(14, 20, medium = true),
            )
        }
    }
}

// ------------------------------------------------------------------ battery

/** "Lascia lavorare l'app a schermo spento": the battery exemption, explained before Android's own dialog. */
@Composable
fun BatteryScreen(onAllow: () -> Unit, onLater: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ScreenBar("", onLater)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box(Modifier.padding(start = 4.dp)) { BigCircle(R.drawable.ic_battery, c.tertiaryContainer, c.onTertiaryContainer) }
            Text(
                "Lascia lavorare l'app a schermo spento", Modifier.padding(start = 4.dp, top = 20.dp, end = 4.dp, bottom = 8.dp),
                color = c.onSurface, style = type(26, 34, medium = true),
            )
            Text(
                "Su alcuni telefoni, quando lo schermo si spegne, Android può fermare la connessione dell'app con la camera. " +
                    "Togliere il limite di batteria può aiutare i download a finire.",
                Modifier.padding(horizontal = 4.dp), color = c.onSurfaceVariant, style = type(16, 24),
            )
            Column(
                Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                BatteryPoint(R.drawable.ic_check, "Più possibilità di arrivare in fondo", "Il download può continuare anche con lo schermo spento.")
                BatteryPoint(R.drawable.ic_clock, "Solo quando serve", "L'app lavora in background solo per i download e per gli invii a Immich.")
                BatteryPoint(R.drawable.ic_settings, "Lo cambi quando vuoi", "Da Impostazioni › Batteria, oppure dalle impostazioni di Android.")
            }
            Row(
                Modifier.padding(top = 10.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(c.surfaceContainerHighest).padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Icon(painterResource(R.drawable.ic_info), null, Modifier.padding(top = 1.dp).size(22.dp), tint = c.onSurfaceVariant)
                Text(
                    buildAnnotatedString {
                        append("Se la camera cade lo stesso, su alcuni telefoni (per esempio OPPO) c'è un'impostazione in più: ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = c.onSurface)) {
                            append("Impostazioni › Batteria › Consenti attività in background")
                        }
                        append(".")
                    },
                    Modifier.padding(start = 12.dp), color = c.onSurfaceVariant, style = type(14, 20),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FujiButton("Consenti", onAllow, Modifier.fillMaxWidth(), kind = ButtonKind.Filled, height = 56.dp)
            FujiButton("Non ora", onLater, Modifier.padding(top = 4.dp).fillMaxWidth(), kind = ButtonKind.Text)
            Text("Android chiederà una conferma.", color = c.onSurfaceVariant, style = type(13, 18))
        }
    }
}

@Composable
private fun BatteryPoint(icon: Int, title: String, sub: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = c.onSecondaryContainer)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = c.onSurface, style = type(15, 22, medium = true))
            Text(sub, color = c.onSurfaceVariant, style = type(14, 20))
        }
    }
}

// ------------------------------------------------------------------ empty gallery

/**
 * Nothing to show in the gallery: every file is on the phone already ([cardEmpty] false: "Mostra tutte le foto"),
 * or the card holds nothing.
 */
@Composable
fun EmptyGallery(cardEmpty: Boolean, total: String, onShowAll: () -> Unit, onDisconnect: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, top = 48.dp, end = 32.dp, bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        BigCircle(if (cardEmpty) R.drawable.ic_sd_card else R.drawable.ic_photos_done, c.primaryContainer, c.onPrimaryContainer, 128.dp)
        Text(
            if (cardEmpty) "La scheda è vuota" else "Nessuna foto nuova", Modifier.padding(top = 8.dp),
            color = c.onSurface, textAlign = TextAlign.Center, style = type(24, 32, medium = true),
        )
        Text(
            if (cardEmpty) "La camera non ha foto né video. Scattane qualcuna e ricollegati."
            else "Tutti i $total file della camera sono già sul telefono. Per rivederli usa i filtri o mostra l'elenco completo.",
            color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(16, 24),
        )
        Column(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!cardEmpty) FujiButton("Mostra tutte le foto", onShowAll, Modifier.fillMaxWidth(), kind = ButtonKind.Filled, height = 56.dp)
            FujiButton("Scollega", onDisconnect, Modifier.fillMaxWidth(), kind = ButtonKind.Text)
        }
    }
}

// ------------------------------------------------------------------ low storage

/** Before a download that does not fit on the phone ([fitCount] files of the batch would, [fitSize] in all). */
class LowStorageInfo(val need: String, val free: String, val fitCount: Int, val fitSize: String, val fitHandles: LongArray)

@Composable
fun LowStorageDialog(info: LowStorageInfo, onFirst: () -> Unit, onFree: () -> Unit, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(Color(0x80000000)).clickable(onClick = onCancel, indication = null,
            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainerHighest)
                .clickable(enabled = false) {}.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 16.dp)
                .semantics { contentDescription = "Spazio insufficiente sul telefono" },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BigCircle(R.drawable.ic_phone, c.errorContainer, c.onErrorContainer, 56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Spazio insufficiente sul telefono", color = c.onSurface, style = type(22, 28, medium = true))
                Text(
                    "Servono ${info.need} e ne restano ${info.free}. Non ho cancellato né toccato niente.",
                    color = c.onSurfaceVariant, style = type(14, 20),
                )
            }
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val n = if (info.fitCount > 0) 3 else 2
                var k = 0
                if (info.fitCount > 0) {
                    DialogChoice(
                        if (info.fitCount == 1) "Scarica solo il primo file · ${info.fitSize}" else "Scarica solo i primi ${info.fitCount} file · ${info.fitSize}",
                        c.primary, c.onPrimary, groupShape(k++, n, 28.dp, 8.dp), onFirst,
                    )
                }
                DialogChoice("Libera spazio", c.secondaryContainer, c.onSecondaryContainer, groupShape(k++, n, 28.dp, 8.dp), onFree)
                DialogChoice("Annulla il download", c.surface, c.onSurface, groupShape(k, n, 28.dp, 8.dp), onCancel)
            }
            Text(
                "«Libera spazio» apre la memoria di Android: poi torni qui e riprovi.", Modifier.fillMaxWidth(),
                color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(13, 18),
            )
        }
    }
}

@Composable
private fun DialogChoice(text: String, bg: Color, fg: Color, shape: RoundedCornerShape, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(bg).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, textAlign = TextAlign.Center, style = type(15, 20, medium = true))
    }
}

// ------------------------------------------------------------------ video the phone cannot play

/** "Il telefono non lo riproduce bene": what the video is, why it does not play, and the two ways on. */
@Composable
fun VideoErrorPanel(what: String, why: String, onRetry: () -> Unit, onDownload: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainer)
            .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_play), null, Modifier.size(22.dp), tint = c.onTertiaryContainer)
            }
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Il telefono non lo riproduce bene", color = c.onSurface, style = type(20, 26, medium = true))
                Text(what, color = c.onSurfaceVariant, style = type(14, 20))
            }
        }
        Text(why, Modifier.padding(horizontal = 4.dp), color = c.onSurfaceVariant, style = type(14, 20))
        Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GroupButton(
                "Prova lo stesso", first = true, container = c.secondaryContainer, content = c.onSecondaryContainer,
                onClick = onRetry, modifier = Modifier.weight(1f), height = 56.dp, textSize = 14,
            )
            if (onDownload != null) {
                GroupButton(
                    "Scarica il video", first = false, container = c.primary, content = c.onPrimary,
                    onClick = onDownload, modifier = Modifier.weight(1f), icon = R.drawable.ic_download, height = 56.dp, textSize = 14,
                )
            }
        }
    }
}
