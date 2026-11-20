package com.wifiproxy.app.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifiproxy.app.service.SessionHolder
import com.wifiproxy.app.session.HostSettings
import com.wifiproxy.app.session.PasswordStrength
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.session.SessionRepository
import com.wifiproxy.app.session.passwordStrength
import com.wifiproxy.app.ui.ScreenTitle
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun SettingsScreen(
    repository: SessionRepository,
    prefs: AppPreferences,
    isDemoMode: Boolean,
    onDemoModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val savedSettings by repository.settings.collectAsStateWithLifecycle()
    val initial = remember { repository.settings.value }
    var suffix by remember { mutableStateOf(initial.ssidSuffix) }
    var password by remember { mutableStateOf(initial.password) }
    var passwordVisible by remember { mutableStateOf(false) }
    var maxClients by remember { mutableStateOf(initial.maxClients) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var prefer5Ghz by remember { mutableStateOf(initial.prefer5Ghz) }

    var rejectionMessage by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf(false) }
    var pendingPasswordConfirm by remember { mutableStateOf(false) }

    // (I7) The real session lives independently of whichever repository is currently active (it's
    // the fake while in demo mode), so this reads SessionHolder directly rather than
    // `repository.session`: switching away from demo mode must stay blocked for as long as a real
    // session is up, even though the fake's own session never reports anything but IDLE.
    val liveController by SessionHolder.controller.collectAsStateWithLifecycle()
    val liveControllerPhase by remember(liveController) {
        liveController?.state ?: MutableStateFlow(SessionPhase.IDLE)
    }.collectAsStateWithLifecycle()
    val demoSwitchLocked = liveControllerPhase != SessionPhase.IDLE

    // USB sharing has no real toggle in 2a; persist the always-on value so a future version that
    // does let it vary reads a well-defined starting point.
    LaunchedEffect(Unit) { prefs.usbSharingEnabled = true }

    fun applySave() {
        val port = portText.toIntOrNull() ?: initial.port
        val ok = repository.updateSettings(
            HostSettings(
                ssidSuffix = suffix,
                password = password,
                maxClients = maxClients,
                port = port,
                prefer5Ghz = prefer5Ghz,
            ),
        )
        if (ok) {
            rejectionMessage = null
            savedMessage = true
        } else {
            val activeSlots = repository.session.value.slots
            val toRevoke = activeSlots.drop(maxClients).joinToString { it.name }
            rejectionMessage = "Revoke $toRevoke first, then lower the limit to $maxClients."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTitle(text = "Settings")

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "USB sharing (laptop)", style = MaterialTheme.typography.titleMedium)
                Switch(checked = true, onCheckedChange = {}, enabled = false)
            }
            Text(
                text = "Always on in this version: start sharing, then connect a laptop over USB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Demo mode", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = isDemoMode,
                    enabled = !demoSwitchLocked,
                    onCheckedChange = {
                        prefs.demoMode = it
                        onDemoModeChange(it)
                    },
                )
            }
            Text(
                text = if (demoSwitchLocked) {
                    "Can't switch while a real session is running. Stop sharing first."
                } else {
                    "Shows fake devices and traffic instead of really sharing cellular data."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (demoSwitchLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Network name", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "DIRECT-wp-",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = suffix,
                    onValueChange = { suffix = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Password", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordStrengthMeter(password)
            AnimatedVisibility(visible = password != savedSettings.password) {
                Text(
                    text = "Changing the password disconnects every connected device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // (I9) In real (2a/USB-sharing) mode the server is hard-wired to one pinned slot on port
        // 8081 - these two fields don't do anything there, so they're disabled with an
        // explanation rather than left looking editable but silently ignored.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Max clients: $maxClients", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = maxClients.toFloat(),
                onValueChange = { maxClients = it.toInt() },
                valueRange = 1f..3f,
                steps = 1,
                enabled = isDemoMode,
            )
            if (!isDemoMode) {
                Text(
                    text = "Fixed for USB sharing (one laptop slot)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rejectionMessage?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Port", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = portText,
                onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) portText = it },
                singleLine = true,
                enabled = isDemoMode,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                supportingText = if (!isDemoMode) {
                    { Text("Fixed for USB sharing (port 8081)") }
                } else {
                    null
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Prefer 5 GHz", style = MaterialTheme.typography.titleMedium)
            Switch(checked = prefer5Ghz, onCheckedChange = { prefer5Ghz = it })
        }

        Button(
            onClick = {
                if (password != initial.password) {
                    pendingPasswordConfirm = true
                } else {
                    applySave()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }

        if (savedMessage) {
            Text(
                text = "Settings saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (pendingPasswordConfirm) {
        AlertDialog(
            onDismissRequest = { pendingPasswordConfirm = false },
            title = { Text("Change password?") },
            text = { Text("Every connected device will be disconnected and must be re-invited.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPasswordConfirm = false
                    applySave()
                }) { Text("Change password") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPasswordConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PasswordStrengthMeter(password: String) {
    val strength = passwordStrength(password)
    val (fraction, label, color) = when (strength) {
        PasswordStrength.INVALID -> Triple(0.1f, "Too short (min 8 characters)", MaterialTheme.colorScheme.error)
        PasswordStrength.WEAK -> Triple(0.33f, "Weak", MaterialTheme.colorScheme.error)
        PasswordStrength.FAIR -> Triple(0.66f, "Fair", MaterialTheme.colorScheme.tertiary)
        PasswordStrength.STRONG -> Triple(1f, "Strong", MaterialTheme.colorScheme.primary)
    }
    Column {
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
