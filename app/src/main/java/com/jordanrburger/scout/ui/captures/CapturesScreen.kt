package com.jordanrburger.scout.ui.captures

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jordanrburger.scout.ScoutApp
import com.jordanrburger.scout.data.db.CapturedNotification

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturesScreen() {
    val context = LocalContext.current
    val vm: CapturesViewModel = viewModel(initializer = {
        CapturesViewModel(ScoutApp.container(context).notificationRepository)
    })
    val items by vm.ui.collectAsState()
    var hideOngoing by remember { mutableStateOf(true) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Captures") }) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                FilterChip(
                    selected = hideOngoing,
                    onClick = {
                        hideOngoing = !hideOngoing
                        vm.setHideOngoing(hideOngoing)
                    },
                    label = { Text("Hide ongoing") },
                )
            }
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No captured notifications yet.\n\nCapture is opt-in: Scout only " +
                            "monitors apps you select in Settings → \"Choose apps to monitor\", " +
                            "and needs Notification Access granted below.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Grant Notification Access") }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                ) {
                    items(items, key = { it.id }) { capture -> CaptureRow(capture) }
                }
            }
        }
    }
}

@Composable
private fun CaptureRow(capture: CapturedNotification) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    capture.pkg,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(capture.postTimeMs).toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (capture.forwardedAt != null) "✓" else "◌",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            capture.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            capture.text?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
