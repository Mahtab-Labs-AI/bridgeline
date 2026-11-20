package com.wifiproxy.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wifiproxy.app.session.CheckStatus
import com.wifiproxy.app.session.SessionRepository
import com.wifiproxy.app.session.SetupCheck
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupCheckScreen(repository: SessionRepository, modifier: Modifier = Modifier) {
    val checks by repository.checks.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            checks.forEach { check ->
                SetupCheckCard(
                    check = check,
                    onFixClick = {
                        scope.launch { snackbarHostState.showSnackbar("Would open system settings") }
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupCheckCard(check: SetupCheck, onFixClick: () -> Unit) {
    val (icon, tint) = check.status.iconAndColor()
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(imageVector = icon, contentDescription = check.status.name, tint = tint)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = check.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (check.status == CheckStatus.ACTION_NEEDED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onFixClick) { Text("Fix it") }
                }
            }
        }
    }
}

@Composable
private fun CheckStatus.iconAndColor(): Pair<ImageVector, Color> = when (this) {
    CheckStatus.OK -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
    CheckStatus.ACTION_NEEDED -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
    CheckStatus.NOT_APPLICABLE -> Icons.Filled.NotInterested to MaterialTheme.colorScheme.onSurfaceVariant
}
