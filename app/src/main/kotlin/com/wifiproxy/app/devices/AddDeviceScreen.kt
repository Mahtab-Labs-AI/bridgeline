package com.wifiproxy.app.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wifiproxy.app.session.ClientPlatform
import com.wifiproxy.app.session.DeviceInvite
import com.wifiproxy.app.session.SessionRepository
import com.wifiproxy.app.session.SlotUi
import com.wifiproxy.app.ui.QrCode
import com.wifiproxy.app.ui.ScreenTitle

private data class ActiveInvite(val invite: DeviceInvite, val platform: ClientPlatform, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(repository: SessionRepository, modifier: Modifier = Modifier) {
    val session by repository.session.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var activeInvite by remember { mutableStateOf<ActiveInvite?>(null) }

    val full = session.slots.size >= session.maxClients

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle(text = "Devices", trailing = "${session.slots.size}/${session.maxClients}")

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            session.slots.forEach { slot ->
                DeviceRow(
                    slot = slot,
                    onClick = {
                        repository.inviteFor(slot.id)?.let { invite ->
                            activeInvite = ActiveInvite(invite, slot.platform, slot.name)
                        }
                    },
                )
            }
        }

        if (full) {
            Text(
                text = "All ${session.maxClients} slots are in use. Revoke a device first, or raise the " +
                    "limit in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Button(
            onClick = { showAddDialog = true },
            enabled = !full,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add device")
        }
    }

    if (showAddDialog) {
        AddDeviceDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, platform ->
                showAddDialog = false
                repository.addDevice(name, platform)?.let { invite ->
                    activeInvite = ActiveInvite(invite, platform, name)
                }
            },
        )
    }

    activeInvite?.let { active ->
        ModalBottomSheet(
            onDismissRequest = { activeInvite = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            InviteSheetContent(active = active)
        }
    }
}

@Composable
private fun DeviceRow(slot: SlotUi, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (slot.platform == ClientPlatform.IPHONE) Icons.Filled.PhoneIphone else Icons.Filled.PhoneAndroid,
                contentDescription = slot.platform.name,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = slot.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Tap to show the invite QR codes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddDeviceDialog(onDismiss: () -> Unit, onConfirm: (String, ClientPlatform) -> Unit) {
    var name by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf(ClientPlatform.ANDROID) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = platform == ClientPlatform.ANDROID,
                        onClick = { platform = ClientPlatform.ANDROID },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Android") }
                    SegmentedButton(
                        selected = platform == ClientPlatform.IPHONE,
                        onClick = { platform = ClientPlatform.IPHONE },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("iPhone") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), platform) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun InviteSheetContent(active: ActiveInvite) {
    var wifiRevealed by remember(active) { mutableStateOf(false) }
    val context = LocalContext.current
    val configUrl = if (active.platform == ClientPlatform.IPHONE) {
        active.invite.mobileConfigUrl
    } else {
        active.invite.pacUrl
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Invite: ${active.name}", style = MaterialTheme.typography.titleLarge)

        Text(text = "1. Wi-Fi network", style = MaterialTheme.typography.titleSmall)
        Box(contentAlignment = Alignment.Center) {
            QrCode(content = active.invite.wifiQr)
            if (!wifiRevealed) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.97f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { wifiRevealed = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Visibility, contentDescription = null)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tap to reveal", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        HorizontalDivider()

        val qr2Label = if (active.platform == ClientPlatform.IPHONE) "2. Install profile" else "2. Auto-config"
        Text(text = qr2Label, style = MaterialTheme.typography.titleSmall)
        QrCode(content = configUrl)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = configUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Proxy auto-config URL", configUrl))
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy link")
            }
        }

        HorizontalDivider()

        Text(text = "Steps", style = MaterialTheme.typography.titleSmall)
        val steps = if (active.platform == ClientPlatform.IPHONE) {
            listOf(
                "Scan the Wi-Fi QR above and join the network.",
                "Open the second QR's link in Safari (it must be Safari).",
                "Install the profile when Settings prompts you.",
                "Turn off mobile data on this phone.",
            )
        } else {
            listOf(
                "Scan the Wi-Fi QR above and join the network.",
                "Wi-Fi → network → Edit → Advanced → Proxy → Auto-config, then paste the URL.",
                "Set Private DNS to Off or Automatic.",
                "Turn off mobile data on this phone.",
            )
        }
        steps.forEachIndexed { index, step ->
            Text(text = "${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
