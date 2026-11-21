package com.wifiproxy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wifiproxy.app.session.FakeSessionRepository
import com.wifiproxy.app.session.RepositoryProvider
import com.wifiproxy.app.session.ServiceSessionRepository
import com.wifiproxy.app.settings.AppPreferences
import com.wifiproxy.app.ui.theme.WifiProxyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WifiProxyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val prefs = remember { AppPreferences(applicationContext) }
                    val provider = remember {
                        RepositoryProvider(
                            createReal = { ServiceSessionRepository(applicationContext, lifecycleScope) },
                            createFake = { FakeSessionRepository(scope = lifecycleScope) },
                            initialDemoMode = prefs.demoMode,
                        )
                    }
                    val repository by provider.repository.collectAsState()
                    var demoMode by remember { mutableStateOf(prefs.demoMode) }

                    val permissionsLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) {
                        // No-op either way: a denied notification permission just means the FGS
                        // notification won't show, and a denied Wi-Fi Direct permission surfaces
                        // on its own as the link's "Allow the Nearby devices permission…"
                        // degraded reason (P2pGroupLink) once sharing is started.
                    }

                    AppRoot(
                        repository = repository,
                        prefs = prefs,
                        isDemoMode = demoMode,
                        onDemoModeChange = { newValue ->
                            demoMode = newValue
                            prefs.demoMode = newValue
                            provider.setDemoMode(newValue)
                        },
                        onBeforeStart = {
                            // Wi-Fi Direct's own permission is only requested when a password is
                            // set (plan decision 2: no password means USB-only, no P2P group).
                            val wifiDirectNeeded = repository.settings.value.password.isNotEmpty()
                            val wanted = buildList {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                if (wifiDirectNeeded) {
                                    when {
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                            add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                        // Android 12 (API 31-32) requires both location permissions.
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                        }
                                        else -> add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                }
                            }
                            val missing = wanted.filter {
                                ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                                    PackageManager.PERMISSION_GRANTED
                            }
                            // Start proceeds either way; a still-missing permission shows up as a
                            // degraded reason rather than blocking Start.
                            if (missing.isNotEmpty()) {
                                permissionsLauncher.launch(missing.toTypedArray())
                            }
                        },
                    )
                }
            }
        }
    }
}
