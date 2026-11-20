package com.wifiproxy.app.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wifiproxy.app.session.CheckStatus
import com.wifiproxy.app.session.SessionRepository
import com.wifiproxy.app.session.SetupCheck
import com.wifiproxy.app.ui.ScreenTitle
import kotlinx.coroutines.launch

@Composable
fun SetupCheckScreen(repository: SessionRepository, modifier: Modifier = Modifier) {
    val checks by repository.checks.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Re-checked on every app open/resume (design spec §8), including returning from a Fix-it
    // intent (e.g. the battery-optimisation system dialog), which resumes this same composable
    // rather than recreating it.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentRepository by rememberUpdatedState(repository)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentRepository.refreshChecks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle(text = "Setup check")

            checks.forEach { check ->
                SetupCheckCard(
                    check = check,
                    onFixClick = {
                        val intent = check.fixIntent
                        if (intent == null) {
                            scope.launch { snackbarHostState.showSnackbar("Would open system settings") }
                        } else {
                            // (I8) Some OEMs don't ship the developer-settings or battery-exemption
                            // activity a fixIntent targets; fall back to the general settings
                            // screen, and only if even that is missing, tell the user instead of
                            // crashing.
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                } catch (e2: ActivityNotFoundException) {
                                    scope.launch { snackbarHostState.showSnackbar("Couldn't open Settings on this device") }
                                }
                            }
                        }
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { Snackbar(it) }
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
