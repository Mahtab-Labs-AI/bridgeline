package com.wifiproxy.app.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifiproxy.app.session.SessionPhase
import com.wifiproxy.app.session.SessionRepository
import com.wifiproxy.app.session.SlotUi
import com.wifiproxy.app.ui.ScreenTitle
import com.wifiproxy.app.ui.color
import com.wifiproxy.app.ui.formatBytes
import com.wifiproxy.app.ui.icon
import com.wifiproxy.app.ui.label

@Composable
fun HomeScreen(repository: SessionRepository, modifier: Modifier = Modifier, onBeforeStart: () -> Unit = {}) {
    val session by repository.session.collectAsStateWithLifecycle()
    var slotPendingRevoke by remember { mutableStateOf<SlotUi?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(text = "Wi-Fi Proxy")

        StatusCard(
            phase = session.phase,
            degradedReason = session.degradedReason,
            ssid = session.ssid,
            proxyAddress = "${session.proxyHost}:${session.port}",
            lastError = session.lastError,
        )

        StartStopButton(
            phase = session.phase,
            onStart = {
                onBeforeStart()
                repository.start()
            },
            onStop = repository::stop,
        )

        MobileDataChip(cellularUp = session.cellularUp)

        Text(
            text = "Devices ${session.slots.size}/${session.maxClients}",
            style = MaterialTheme.typography.titleMedium,
        )

        session.slots.forEach { slot ->
            SlotRow(slot = slot, onRevokeClick = { slotPendingRevoke = slot })
        }

        HorizontalDivider()

        Text(
            text = "Web and most apps work. Calls, games and some apps won't.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    slotPendingRevoke?.let { slot ->
        AlertDialog(
            onDismissRequest = { slotPendingRevoke = null },
            title = { Text("Revoke ${slot.name}?") },
            text = { Text("This device loses access immediately and must be re-invited to rejoin.") },
            confirmButton = {
                TextButton(onClick = {
                    repository.revoke(slot.id)
                    slotPendingRevoke = null
                }) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { slotPendingRevoke = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusCard(
    phase: SessionPhase,
    degradedReason: String?,
    ssid: String,
    proxyAddress: String,
    lastError: String?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = phase.color().copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(color = phase.color(), shape = CircleShape),
                )
                Text(text = phase.label(), style = MaterialTheme.typography.headlineSmall)
            }
            Text(text = ssid, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = proxyAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (phase == SessionPhase.DEGRADED && degradedReason != null) {
                Text(
                    text = degradedReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = phase.color(),
                )
            }
            // (C1) Surfaces a start() failure (e.g. port 8081 already bound) once the session has
            // fallen back to IDLE, so "Start did nothing" never looks like silent failure.
            if (phase == SessionPhase.IDLE && lastError != null) {
                Text(
                    text = "Couldn't start: $lastError",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StartStopButton(phase: SessionPhase, onStart: () -> Unit, onStop: () -> Unit) {
    val running = phase == SessionPhase.RUNNING || phase == SessionPhase.DEGRADED
    val busy = phase == SessionPhase.STARTING || phase == SessionPhase.STOPPING

    Button(
        onClick = { if (running) onStop() else onStart() },
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = if (running) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            text = when {
                busy -> phase.label()
                running -> "Stop sharing"
                else -> "Start sharing"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun MobileDataChip(cellularUp: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(if (cellularUp) "Mobile data up" else "No mobile data") },
        leadingIcon = {
            Icon(
                imageVector = if (cellularUp) Icons.Filled.SignalCellularAlt else Icons.Filled.SignalCellularOff,
                contentDescription = null,
            )
        },
    )
}

@Composable
private fun SlotRow(slot: SlotUi, onRevokeClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = slot.platform.icon(),
                contentDescription = slot.platform.name,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = slot.name, style = MaterialTheme.typography.titleSmall)
                slot.ip?.let { ip ->
                    Text(
                        text = ip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "↑ ${formatBytes(slot.bytesUp)}   ↓ ${formatBytes(slot.bytesDown)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRevokeClick) {
                Icon(Icons.Filled.Close, contentDescription = "Revoke ${slot.name}")
            }
        }
    }
}
