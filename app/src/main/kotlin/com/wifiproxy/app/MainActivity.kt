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

                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { /* No-op either way: the FGS notification just won't show if denied. */ }

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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }
        }
    }
}
