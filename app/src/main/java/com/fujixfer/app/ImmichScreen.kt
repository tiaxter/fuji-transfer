package com.fujixfer.app

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The status card at the top: never checked, checking, connected (version · photos), or what went wrong. */
private enum class ServerState { Unknown, Checking, Ok, Error }

/**
 * Immich settings, as in the "Impostazioni Immich" design: status card, Email e password / Chiave API,
 * server address and access, "Invia dopo il download" and "Solo con il Wi-Fi di casa", Verifica + Salva.
 * The password is used once to log in and is never stored: the app keeps the session token.
 */
@Composable
fun ImmichScreen(onBack: () -> Unit, onSaved: (ready: Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { ImmichSync.settings(ctx) }
    var useLogin by remember { mutableStateOf(saved.useLogin) }
    var url by remember { mutableStateOf(saved.url) }
    var email by remember { mutableStateOf(saved.email) }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(saved.key) }
    var token by remember { mutableStateOf(saved.token) }
    var on by remember { mutableStateOf(saved.enabled || saved.url.isEmpty()) }
    var wifi by remember { mutableStateOf(saved.wifiOnly) }
    var show by remember { mutableStateOf(false) }
    val info0 = remember { ImmichSync.serverInfo(ctx) }
    var state by remember { mutableStateOf(if (info0.isNotEmpty() && saved.credential.isNotEmpty()) ServerState.Ok else ServerState.Unknown) }
    var detail by remember { mutableStateOf(info0) }
    val last = remember { ImmichSync.lastSummary(ctx) }

    fun current() = ImmichSync.Settings(on, url, key, wifi, useLogin, email, token)
    fun edited() {
        if (state != ServerState.Checking) state = ServerState.Unknown
    }

    /** Logs in if a password is typed, checks the server; on success [then] runs with the checked settings. */
    fun check(then: ((ImmichSync.Settings) -> Unit)? = null) {
        if (url.isBlank()) {
            state = ServerState.Error
            detail = "Inserisci l'indirizzo del server"
            return
        }
        state = ServerState.Checking
        detail = "Contatto il server…"
        val s = current()
        val pw = password
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ImmichSync.verify(s, pw) } }
            r.onSuccess { (checked, text) ->
                token = checked.token
                password = ""
                state = ServerState.Ok
                detail = text
                ImmichSync.saveServerInfo(ctx, text)
                then?.invoke(checked)
            }.onFailure { e ->
                state = ServerState.Error
                detail = e.message ?: "Server non raggiungibile"
            }
        }
    }

    fun save(s: ImmichSync.Settings) {
        ImmichSync.save(ctx, s)
        ImmichSync.schedule(ctx)
        onSaved(s.ready)
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
            StatusCard(state, detail)

            // Email e password | Chiave API
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                GroupButton(
                    "Email e password", first = true,
                    container = if (useLogin) c.primary else c.surfaceContainerHigh,
                    content = if (useLogin) c.onPrimary else c.onSurface,
                    onClick = { useLogin = true; edited() }, modifier = Modifier.weight(if (useLogin) 1.25f else 1f),
                    icon = if (useLogin) R.drawable.ic_check else null,
                )
                GroupButton(
                    "Chiave API", first = false,
                    container = if (!useLogin) c.primary else c.surfaceContainerHigh,
                    content = if (!useLogin) c.onPrimary else c.onSurface,
                    onClick = { useLogin = false; edited() }, modifier = Modifier.weight(if (!useLogin) 1.25f else 1f),
                    icon = if (!useLogin) R.drawable.ic_check else null,
                )
            }

            Field(
                url, { url = it; edited() }, "Indirizzo del server", R.drawable.ic_globe,
                support = "Con la porta, come nell'app web", keyboard = KeyboardType.Uri,
            )
            if (useLogin) {
                Field(email, { email = it; edited() }, "Email", R.drawable.ic_person, keyboard = KeyboardType.Email)
                Field(
                    password, { password = it; edited() }, "Password", R.drawable.ic_lock,
                    secret = !show, onToggle = { show = !show }, keyboard = KeyboardType.Password,
                    support = if (token.isNotEmpty() && password.isEmpty()) "Accesso già fatto: scrivila solo per rifarlo" else null,
                )
            } else {
                Field(
                    key, { key = it; edited() }, "Chiave API", R.drawable.ic_key,
                    secret = !show, onToggle = { show = !show }, keyboard = KeyboardType.Password,
                    support = "Immich → Impostazioni account → Chiavi API",
                )
            }

            Column(
                Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(c.surfaceContainer)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                ToggleRow("Invia dopo il download", "Le nuove foto vanno su Immich da sole", on) { on = it }
                HorizontalDivider(color = c.outlineVariant)
                ToggleRow("Solo con il Wi-Fi di casa", "Niente dati mobili", wifi) { wifi = it }
            }

            val progress = ImmichSync.progress
            val note = progress.ifEmpty { last }
            if (note.isNotEmpty()) {
                Text(note, Modifier.padding(horizontal = 16.dp), color = c.onSurfaceVariant, style = type(12, 16))
            }
            Text(
                "Prima di inviare, ogni foto è confrontata con Immich: se c'è già, non viene caricata.",
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), color = c.onSurfaceVariant, style = type(12, 16),
            )
        }

        // Verifica | Salva
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GroupButton(
                "Verifica", first = true, container = c.secondaryContainer, content = c.onSecondaryContainer,
                onClick = { check() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_retry,
                enabled = state != ServerState.Checking,
            )
            GroupButton(
                "Salva", first = false, container = c.primary, content = c.onPrimary,
                onClick = {
                    // a typed password needs the login to get the token; otherwise save without the network
                    if (useLogin && password.isNotEmpty()) check { save(it) } else save(current())
                },
                modifier = Modifier.weight(1f), enabled = state != ServerState.Checking,
            )
        }
    }
}

@Composable
private fun StatusCard(state: ServerState, detail: String) {
    val c = MaterialTheme.colorScheme
    val title = when (state) {
        ServerState.Unknown -> "Da verificare"
        ServerState.Checking -> "Verifica…"
        ServerState.Ok -> "Connesso"
        ServerState.Error -> "Non connesso"
    }
    val sub = when (state) {
        ServerState.Unknown -> "Controlla indirizzo e accesso"
        else -> detail
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(c.surfaceContainer).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(if (state == ServerState.Error) c.errorContainer else c.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_immich), null, Modifier.size(36.dp),
                    tint = if (state == ServerState.Error) c.onErrorContainer else c.onPrimaryContainer,
                )
            }
            if (state == ServerState.Ok) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(24.dp).clip(CircleShape)
                        .background(c.primary).border(2.dp, c.surfaceContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(14.dp), tint = c.onPrimary)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text("Server", color = c.onSurfaceVariant, style = type(12, 16, medium = true))
            Text(title, color = c.onSurface, style = type(28, 36))
            Text(sub, color = if (state == ServerState.Error) c.error else c.onSurfaceVariant, style = type(14, 20), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
