package com.wifiproxy.app.logs

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wifiproxy.app.BuildConfig
import com.wifiproxy.app.session.LogEntry
import com.wifiproxy.app.session.SessionRepository
import com.wifiproxy.app.ui.ScreenTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun DiagnosticsScreen(repository: SessionRepository, modifier: Modifier = Modifier) {
    val entries by repository.log.collectAsStateWithLifecycle()
    val verboseUntil by repository.verboseUntilMs.collectAsStateWithLifecycle()
    val newestFirst = entries.asReversed()
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle(text = "Diagnostics")

        if (rememberVerboseActive(verboseUntil)) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Text(
                    text = "Verbose logging on — turns off at ${formatClockTime(verboseUntil)}. " +
                        "Export before then: turning off trims the log to the last 500 lines.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Button(
            onClick = {
                val header = "Bridgeline ${BuildConfig.VERSION_NAME}"
                // Verbose ([v]) lines are ordinary entries, so they are exported too; only a log
                // too long for the share intent loses its oldest lines.
                val shareText = buildExport(header, newestFirst.map { it.format() })
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(sendIntent, "Export log"))
            },
            enabled = newestFirst.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Export")
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(newestFirst) { entry ->
                Text(
                    text = entry.format(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
                HorizontalDivider()
            }
        }
    }
}

private fun LogEntry.format(): String = "${TIME_FORMAT.format(Date(timeMs))}  $message"
