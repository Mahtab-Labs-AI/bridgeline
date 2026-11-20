package com.wifiproxy.app.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wifiproxy.app.session.SessionRepository

@Composable
fun SetupCheckScreen(repository: SessionRepository, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Setup check", style = MaterialTheme.typography.titleMedium)
    }
}
