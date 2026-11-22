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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifiproxy.app.BuildConfig
import com.wifiproxy.app.logs.formatClockTime
import com.wifiproxy.app.service.SessionHolder
import com.wifiproxy.app.session.HostSettings
import com.wifiproxy.app.session.PasswordStrength
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.session.SessionRepository
import com.wifiproxy.app.session.SettingsStatus
import com.wifiproxy.app.session.SettingsUpdate
import com.wifiproxy.app.session.passwordStrength
import com.wifiproxy.app.ui.ScreenTitle
import com.wifiproxy.app.wifi.CREDENTIALS_UNREADABLE_MESSAGE
import com.wifiproxy.app.wifi.WifiCredentialRules
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
    val session by repository.session.collectAsStateWithLifecycle()
    val settingsStatus by repository.settingsStatus.collectAsStateWithLifecycle()
    // The fields are initialised exactly once, from the saved settings, as soon as those are READY
    // (at once if they already are; else when the repository's off-Main load lands) - and never
    // reset afterwards, so nothing overwrites what the user is typing. They stay disabled until
    // then: before the load, `settings` holds blanks that mean nothing. Every "changed?" test
    // below compares against the live savedSettings, never a value captured once.
    val readyAtStart = remember(repository) { repository.settingsStatus.value == SettingsStatus.READY }
    val startValues = remember(repository) { repository.settings.value }
    var fieldsInitialised by remember(repository) { mutableStateOf(readyAtStart) }
    var suffix by remember(repository) { mutableStateOf(if (readyAtStart) startValues.ssidSuffix else "") }
    var password by remember(repository) { mutableStateOf(if (readyAtStart) startValues.password else "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var maxClients by remember(repository) { mutableStateOf(startValues.maxClients) }
    var portText by remember(repository) { mutableStateOf(startValues.port.toString()) }
    var prefer5Ghz by remember(repository) { mutableStateOf(startValues.prefer5Ghz) }
    var usbSharingEnabled by remember { mutableStateOf(prefs.usbSharingEnabled) }
    LaunchedEffect(repository, settingsStatus) {
        if (!fieldsInitialised && settingsStatus == SettingsStatus.READY) {
            val loaded = repository.settings.value
            suffix = loaded.ssidSuffix
            password = loaded.password
            maxClients = loaded.maxClients
            portText = loaded.port.toString()
            prefer5Ghz = loaded.prefer5Ghz
            fieldsInitialised = true
        }
    }
    val editable = fieldsInitialised && settingsStatus == SettingsStatus.READY

    var rejectionMessage by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf(false) }
    var pendingPasswordConfirm by remember { mutableStateOf(false) }
    var pendingRenameConfirm by remember { mutableStateOf(false) }

    // (I7) The real session lives independently of whichever repository is currently active (it's
    // the fake while in demo mode), so this reads SessionHolder directly rather than
    // `repository.session`: switching away from demo mode must stay blocked for as long as a real
    // session is up, even though the fake's own session never reports anything but IDLE.
    val liveController by SessionHolder.controller.collectAsStateWithLifecycle()
    val liveControllerPhase by remember(liveController) {
        liveController?.state ?: MutableStateFlow(SessionPhase.IDLE)
    }.collectAsStateWithLifecycle()
    val demoSwitchLocked = liveControllerPhase != SessionPhase.IDLE

    val suffixError = if (suffix.isNotEmpty()) WifiCredentialRules.suffixError(suffix) else null
    val passwordError = if (password.isNotEmpty()) WifiCredentialRules.passwordError(password) else null

    fun applySave() {
        // (E3.4) The port is checked here, so its error shows on the Port field itself.
        val port = portText.toIntOrNull()
        val portProblem = if (port == null) "Enter a port" else HostConfigSource.portError(port)
        if (port == null || portProblem != null) {
            savedMessage = false
            portError = portProblem
            return
        }
        val result = repository.updateSettings(
            HostSettings(
                ssidSuffix = suffix,
                password = password,
                maxClients = maxClients,
                port = port,
                prefer5Ghz = prefer5Ghz,
            ),
        )
        if (result == SettingsUpdate.SAVED) {
            rejectionMessage = null
            savedMessage = true
        } else if (result == SettingsUpdate.CREDENTIALS_UNREADABLE) {
            // Nothing was changed; the user's edits stay in the fields for another try.
            savedMessage = false
            rejectionMessage = CREDENTIALS_UNREADABLE_MESSAGE
        } else {
            savedMessage = false
            val activeSlots = repository.session.value.slots
            rejectionMessage = if (maxClients < activeSlots.size) {
                val toRevoke = activeSlots.drop(maxClients).joinToString { it.name }
                "Revoke $toRevoke first, then lower the limit to $maxClients."
            } else {
                "Couldn't save. Check the Wi-Fi name and password, and that the device limit leaves " +
                    "room for your saved devices (plus the USB laptop if USB sharing is on)."
            }
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
            // (D7) Turning the switch on is refused when the laptop's seat wouldn't fit the saved
            // Wi-Fi devices; turning it off is always allowed while idle. A cached flow, so
            // recomposing never decodes the slot store.
            // Null until first computed: the switch stays disabled until the answer is known.
            val usbCanTurnOn by repository.usbSwitchAllowed.collectAsStateWithLifecycle()
            val usbSwitchEnabled = !demoSwitchLocked && usbCanTurnOn != null && (usbSharingEnabled || usbCanTurnOn == true)
            val usbNeedsFreeSlot = !demoSwitchLocked && !usbSharingEnabled && usbCanTurnOn == false
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "USB sharing (laptop)", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = usbSharingEnabled,
                    enabled = usbSwitchEnabled,
                    onCheckedChange = {
                        usbSharingEnabled = it
                        prefs.usbSharingEnabled = it
                    },
                )
            }
            Text(
                text = when {
                    demoSwitchLocked -> "Applies the next time sharing starts. Stop sharing to change it."
                    usbNeedsFreeSlot ->
                        "Remove a Wi-Fi device or raise the device limit first: the laptop needs a free slot."
                    else -> "Start sharing, then connect a laptop over USB."
                },
                style = MaterialTheme.typography.bodySmall,
                // Only the D7 refusal is an error; the "applies next start" lock is information.
                color = if (usbNeedsFreeSlot) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // (D8) Read once per start, like the USB switch: locked while a real session runs.
            val requireCodes by repository.requireDeviceCodes.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Require a code for each device",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = requireCodes,
                    enabled = !demoSwitchLocked,
                    onCheckedChange = { repository.setRequireDeviceCodes(it) },
                )
            }
            Text(
                text = buildString {
                    append(
                        if (requireCodes) {
                            "Each device needs its own invite."
                        } else {
                            "Anyone who knows the Wi-Fi password can use it, up to the device limit."
                        },
                    )
                    if (demoSwitchLocked) append(" Applies the next time sharing starts. Stop sharing to change it.")
                },
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
            // Off by default and only ever turned on here; it turns itself off after 30 minutes.
            // Unlike the switches above it applies at once, also while sharing.
            val verboseUntil by repository.verboseUntilMs.collectAsStateWithLifecycle()
            val verboseOn = verboseUntil != 0L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Verbose logging (troubleshooting)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = verboseOn,
                    onCheckedChange = { repository.setVerboseLogging(it) },
                )
            }
            Text(
                text = "Adds per-connection detail to Logs (device IP, port, errors; never website names). " +
                    "Turns itself off after 30 minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (verboseOn) {
                Text(
                    text = "On until ${formatClockTime(verboseUntil)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Network name", style = MaterialTheme.typography.titleMedium)
            // (E3.5) The real saved code (it is kept when the name changes), or a placeholder
            // until the first save picks one.
            val ssidPrefix = if (savedSettings.password.isNotEmpty()) {
                WifiCredentialRules.prefixOf(session.ssid)
            } else {
                WifiCredentialRules.PLACEHOLDER_PREFIX
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ssidPrefix,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = suffix,
                    onValueChange = { suffix = it },
                    singleLine = true,
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                )
            }
            suffixError?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (suffix.isNotEmpty()) {
                Text(
                    text = "$ssidPrefix$suffix",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Password", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                enabled = editable,
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
            // (E3.3) Empty means USB-only, which is valid: no meter, no error. A non-empty invalid
            // password shows its one error; only a valid one gets a strength rating.
            if (password.isNotEmpty() && passwordError == null) PasswordStrengthMeter(password)
            passwordError?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            AnimatedVisibility(visible = password != savedSettings.password) {
                Text(
                    text = "Changing the password disconnects every connected device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // A real session reads maxClients/port once on start (SessionController), so a change made
        // while it's running only takes effect the next time sharing starts.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Max clients: $maxClients", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = maxClients.toFloat(),
                onValueChange = { maxClients = it.toInt() },
                enabled = editable,
                valueRange = 1f..3f,
                steps = 1,
            )
            if (demoSwitchLocked) {
                Text(
                    text = "Applies the next time sharing starts.",
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
                onValueChange = {
                    if (it.length <= 5 && it.all(Char::isDigit)) {
                        portText = it
                        portError = null // (E3.4) a stale error never outlives an edit
                    }
                },
                singleLine = true,
                enabled = editable,
                isError = portError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                supportingText = when {
                    portError != null -> {
                        { Text(portError.orEmpty()) }
                    }
                    demoSwitchLocked -> {
                        { Text("Applies the next time sharing starts.") }
                    }
                    else -> null
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Prefer 5 GHz", style = MaterialTheme.typography.titleMedium)
            Switch(checked = prefer5Ghz, enabled = editable, onCheckedChange = { prefer5Ghz = it })
        }
        if (demoSwitchLocked) {
            Text(
                text = "Applies the next time sharing starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (settingsStatus) {
            SettingsStatus.LOADING -> Text(
                text = "Loading the saved settings…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The stored credentials exist but can't be read: saving now would be judged against
            // blanks, so Save stays disabled until a retry reads them.
            SettingsStatus.UNREADABLE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = CREDENTIALS_UNREADABLE_MESSAGE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { repository.reloadSettings() }) { Text("Try again") }
            }
            SettingsStatus.READY -> Unit
        }

        Button(
            onClick = {
                val sharing = session.phase != SessionPhase.IDLE && session.phase != SessionPhase.STOPPING
                when {
                    // Only a password that really differs from the saved one deletes the devices.
                    password != savedSettings.password -> pendingPasswordConfirm = true
                    // A rename of saved credentials restarts a running session under the new SSID.
                    sharing && savedSettings.ssidSuffix.isNotEmpty() && suffix != savedSettings.ssidSuffix ->
                        pendingRenameConfirm = true
                    else -> applySave()
                }
            },
            enabled = editable && suffixError == null && passwordError == null,
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

        Text(
            text = "Bridgeline ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
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

    if (pendingRenameConfirm) {
        AlertDialog(
            onDismissRequest = { pendingRenameConfirm = false },
            title = { Text("Rename the network?") },
            text = { Text("Renaming restarts sharing; devices must rejoin the new network (their invites stay valid).") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRenameConfirm = false
                    applySave()
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameConfirm = false }) { Text("Cancel") }
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
