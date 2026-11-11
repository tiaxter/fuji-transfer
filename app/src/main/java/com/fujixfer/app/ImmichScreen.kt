package com.fujixfer.app

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Immich" (design "Impostazioni · Immich"). Two phases:
 * - not signed in: address, Email e password | Chiave API, "Accedi". The password is used once to log in and is
 *   never stored: the app keeps the session token.
 * - signed in: the server ("Verifica"), address and account ("Cambia" = sign in again), "Quando inviare" (only
 *   when asked / ask at the end of a download / always), "Cosa inviare" (for the automatic queue), "Invia solo con
 *   Wi-Fi", the way to the uploads list and "Scollega Immich".
 * Every choice is saved as soon as it is made: there is no "Salva".
 */
@Composable
fun ImmichScreen(onBack: () -> Unit, onUploads: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(ImmichSync.settings(ctx)) }
    var linked by remember { mutableStateOf(saved.configured && ImmichSync.serverInfo(ctx).isNotEmpty()) }
    var serverInfo by remember { mutableStateOf(ImmichSync.serverInfo(ctx)) }

    // sign-in fields
    var useLogin by remember { mutableStateOf(saved.useLogin) }
    var url by remember { mutableStateOf(saved.url) }
    var email by remember { mutableStateOf(saved.email) }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(saved.key) }
    var show by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    // queue size and access problems, kept fresh while the screen is open
    var queued by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            queued = ImmichSync.queued(ctx).size + ImmichSync.failed(ctx).size
            blocked = ImmichSync.blocked(ctx)
            delay(1000)
        }
    }

    var verifying by remember { mutableStateOf(false) }
    var checkedOk by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf("") }

    fun signOut() {
        ImmichSync.signOut(ctx)
        saved = ImmichSync.settings(ctx)
        url = saved.url
        email = saved.email
        key = ""
        serverInfo = ""
        checkedOk = false
        linked = false
    }

    fun update(s: ImmichSync.Settings) {
        saved = s
        ImmichSync.save(ctx, s)
        ImmichSync.schedule(ctx)
    }

    fun signIn() {
        if (url.isBlank()) {
            error = "Inserisci l'indirizzo del server"
            return
        }
        checking = true
        error = ""
        val s = saved.copy(url = url, key = if (useLogin) "" else key, useLogin = useLogin, email = email, token = if (useLogin) "" else saved.token)
        val pw = password
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ImmichSync.verify(s, pw) } }
            checking = false
            r.onSuccess { (checked, text) ->
                password = ""
                ImmichSync.saveServerInfo(ctx, text)
                serverInfo = text
                update(checked)
                linked = true
                DebugLog.add("Immich: accesso riuscito ($text)")
            }.onFailure { e ->
                error = e.message ?: "Server non raggiungibile"
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Indietro", tint = c.onSurface)
            }
            Text("Immich", Modifier.padding(start = 4.dp), color = c.onSurface, style = type(22, 28))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!linked) {
                Text(
                    "Collega il tuo server Immich. Le foto restano sul telefono: su Immich va solo una copia, e solo di quelle che scegli tu.",
                    Modifier.padding(horizontal = 4.dp), color = c.onSurfaceVariant, style = type(14, 20),
                )
                Field(url, { url = it; error = "" }, "Indirizzo del server", R.drawable.ic_globe,
                    support = "Con la porta, come nell'app web", keyboard = KeyboardType.Uri)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupButton(
                        "Email e password", first = true,
                        container = if (useLogin) c.primary else c.surfaceContainerHigh,
                        content = if (useLogin) c.onPrimary else c.onSurface,
                        onClick = { useLogin = true; error = "" }, modifier = Modifier.weight(if (useLogin) 1.25f else 1f),
                        icon = if (useLogin) R.drawable.ic_check else null, height = 48.dp, textSize = 14,
                    )
                    GroupButton(
                        "Chiave API", first = false,
                        container = if (!useLogin) c.primary else c.surfaceContainerHigh,
                        content = if (!useLogin) c.onPrimary else c.onSurface,
                        onClick = { useLogin = false; error = "" }, modifier = Modifier.weight(if (!useLogin) 1.25f else 1f),
                        icon = if (!useLogin) R.drawable.ic_check else null, height = 48.dp, textSize = 14,
                    )
                }
                if (useLogin) {
                    Field(email, { email = it; error = "" }, "Email", R.drawable.ic_person, keyboard = KeyboardType.Email)
                    Field(
                        password, { password = it; error = "" }, "Password", R.drawable.ic_lock,
                        secret = !show, onToggle = { show = !show }, keyboard = KeyboardType.Password,
                        support = "Non viene salvata: serve solo per ottenere l'accesso.",
                    )
                } else {
                    Field(
                        key, { key = it; error = "" }, "Chiave API", R.drawable.ic_key,
                        secret = !show, onToggle = { show = !show }, keyboard = KeyboardType.Password,
                        support = "Immich → Impostazioni account → Chiavi API",
                    )
                }
                if (error.isNotEmpty()) {
                    Text(error, Modifier.padding(horizontal = 4.dp), color = c.error, style = type(14, 20))
                }
            } else {
                val host = hostOf(saved.url)
                val version = serverInfo.substringBefore(" · ")
                StatusCard(
                    problem = blocked,
                    line = when {
                        checkedOk -> "$host · risponde"
                        saved.useLogin -> saved.email
                        else -> "Chiave API"
                    },
                    sub = if (checkedOk) "Verificato ora" + (if (version.isNotEmpty()) " · $version" else "")
                    else host + (if (version.isNotEmpty()) " · $version" else ""),
                    checking = verifying,
                    onVerify = {
                        verifying = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { ImmichSync.verify(saved, "") } }
                            verifying = false
                            r.onSuccess { (_, text) ->
                                ImmichSync.saveServerInfo(ctx, text)
                                serverInfo = text
                                checkedOk = true
                                verifyError = ""
                            }.onFailure { e ->
                                checkedOk = false
                                verifyError = e.message ?: "Server non raggiungibile"
                            }
                        }
                    },
                )
                if (verifyError.isNotEmpty()) {
                    Text(verifyError, Modifier.padding(horizontal = 4.dp), color = c.error, style = type(14, 20))
                }

                SectionTitle("Server e account")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InfoRow("Indirizzo del server", saved.url, groupShape(0, 2), null, {})
                    InfoRow(
                        if (saved.useLogin) saved.email else "Chiave API",
                        if (saved.useLogin) "Accesso con email e password" else "Accesso con chiave API",
                        groupShape(1, 2), "Cambia",
                    ) { signOut() }
                }
                Text(
                    "Per cambiare indirizzo o account tocca Cambia e accedi di nuovo.",
                    Modifier.padding(horizontal = 4.dp), color = c.onSurfaceVariant, style = type(12, 16),
                )

                SectionTitle("Quando inviare")
                val modes = listOf(
                    Triple(ImmichSync.MODE_ASK, "Solo quando lo chiedo", "In galleria scegli le foto e tocchi Immich"),
                    Triple(ImmichSync.MODE_OFFER, "Chiedi a fine download", "Una domanda dopo ogni lotto: sì o no"),
                    Triple(ImmichSync.MODE_AUTO, "Sempre, dopo ogni download", "Le foto appena scaricate vanno in coda"),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    modes.forEachIndexed { i, (id, title, sub) ->
                        ModeRow(title, sub, saved.mode == id, first = i == 0, last = i == modes.lastIndex) {
                            update(saved.copy(mode = id))
                        }
                    }
                }

                SectionTitle("Cosa inviare")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ImmichSync.KIND_PHOTO to "Foto JPG", ImmichSync.KIND_RAW to "RAW (RAF)", ImmichSync.KIND_VIDEO to "Video",
                    ).forEach { (id, label) ->
                        KindChip(label, id in saved.kinds) {
                            val k = if (id in saved.kinds) saved.kinds - id else saved.kinds + id
                            update(saved.copy(kinds = k))
                        }
                    }
                }
                Text(
                    "Vale per «Sempre, dopo ogni download». Le foto che scegli tu partono comunque.",
                    Modifier.padding(horizontal = 4.dp), color = c.onSurfaceVariant, style = type(12, 16),
                )

                SectionTitle("Rete")
                Column(
                    Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(c.surfaceContainer).padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    ToggleRow(
                        "Invia solo con Wi‑Fi", "Mai con i dati mobili. Se il Wi‑Fi non raggiunge il server, aspetta.", saved.wifiOnly,
                    ) { update(saved.copy(wifiOnly = it)) }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!linked) {
                FujiButton(
                    if (checking) "Accesso…" else "Accedi", { signIn() }, Modifier.fillMaxWidth(),
                    kind = ButtonKind.Filled, enabled = !checking,
                )
                Text(
                    "Dopo l'accesso scegli quando inviare. Nulla parte da solo.",
                    Modifier.padding(top = 8.dp), color = c.onSurfaceVariant, style = type(12, 16),
                )
            } else {
                FujiButton(
                    if (queued > 0) "Vedi gli invii · $queued in coda" else "Vedi gli invii", onUploads,
                    Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, height = 56.dp, icon = R.drawable.ic_cloud_queue,
                )
                FujiButton(
                    "Scollega Immich", { signOut() }, Modifier.padding(top = 4.dp).fillMaxWidth(),
                    kind = ButtonKind.Text, contentColor = c.error,
                )
                Text(
                    "Le foto già su Immich restano lì. Qui si ferma solo l'invio.",
                    color = c.onSurfaceVariant, textAlign = TextAlign.Center, style = type(13, 18),
                )
            }
        }
    }
}

/** "immich.casa.it" from "https://immich.casa.it:2283/api". */
private fun hostOf(url: String): String =
    url.trim().substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { url }

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, Modifier.padding(start = 4.dp, top = 12.dp), color = MaterialTheme.colorScheme.primary,
        style = type(14, 20, medium = true),
    )
}

/** The server: connected (or the sign-in to redo), with "Verifica" to ask it now. */
@Composable
private fun StatusCard(problem: String, line: String, sub: String, checking: Boolean, onVerify: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bad = problem.isNotEmpty()
    val bg = if (bad) c.errorContainer else c.tertiaryContainer
    val fg = if (bad) c.onErrorContainer else c.onTertiaryContainer
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (bad) c.error else c.tertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (bad) R.drawable.ic_lock else R.drawable.ic_cloud_done), null, Modifier.size(26.dp),
                tint = if (bad) c.onError else c.onTertiary,
            )
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(if (bad) "Accesso da rifare" else "Collegato", color = fg, style = type(18, 24, medium = true))
            Text(if (bad) problem else line, color = fg, style = type(14, 20), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!bad && sub.isNotEmpty()) Text(sub, color = fg.copy(alpha = 0.85f), style = type(12, 16), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (bad) c.error else c.tertiary)
                .clickable(enabled = !checking, role = Role.Button, onClick = onVerify).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (checking) "Verifico…" else "Verifica", color = if (bad) c.onError else c.onTertiary, style = type(14, 20, medium = true))
        }
    }
}

/** A row of "Server e account": what, and a small tonal button ([action] null = none). */
@Composable
private fun InfoRow(title: String, sub: String, shape: RoundedCornerShape, action: String?, onAction: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(c.surfaceContainer)
            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = c.onSurface, style = type(16, 22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = c.onSurfaceVariant, style = type(13, 18), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) FujiButton(action, onAction, kind = ButtonKind.Tonal, height = 40.dp)
    }
}

/** One choice of "Quando caricare": a radio row in a joined group (outer corners 20, inner 6; chosen = 20). */
@Composable
private fun ModeRow(title: String, sub: String, on: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val big = 20.dp
    val small = 6.dp
    val shape = if (on) RoundedCornerShape(big) else RoundedCornerShape(
        topStart = if (first) big else small, topEnd = if (first) big else small,
        bottomStart = if (last) big else small, bottomEnd = if (last) big else small,
    )
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (on) c.secondaryContainer else c.surfaceContainer)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) c.primary else c.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(c.primary))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = if (on) c.onSecondaryContainer else c.onSurface, style = type(16, 22, medium = on))
            Text(sub, color = if (on) c.onSecondaryContainer else c.onSurfaceVariant, style = type(14, 20))
        }
    }
}

@Composable
private fun KindChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (on) 18.dp else 10.dp)
    Row(
        Modifier.height(36.dp).clip(shape)
            .then(if (on) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outlineVariant, shape))
            .clickable(role = Role.Checkbox, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) Icon(painterResource(R.drawable.ic_check), null, Modifier.padding(end = 6.dp).size(18.dp), tint = c.onSecondaryContainer)
        Text(label, color = if (on) c.onSecondaryContainer else c.onSurface, style = type(14, 20, medium = true))
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String, icon: Int,
    support: String? = null, keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false, onToggle: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(painterResource(icon), null, tint = c.onSurfaceVariant) },
        trailingIcon = if (onToggle != null) {
            {
                IconButton(onClick = onToggle) {
                    Icon(
                        painterResource(if (secret) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        contentDescription = if (secret) "Mostra" else "Nascondi", tint = c.onSurfaceVariant,
                    )
                }
            }
        } else {
            null
        },
        supportingText = if (support != null) {
            { Text(support) }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        textStyle = type(16, 24),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = c.outline),
    )
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.onSurface, style = type(16, 24))
            Text(sub, color = c.onSurfaceVariant, style = type(14, 20))
        }
        Switch(
            checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 16.dp),
            thumbContent = if (checked) {
                { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(SwitchDefaults.IconSize)) }
            } else {
                null
            },
        )
    }
}
