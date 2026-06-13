package com.jordanrburger.scout.ui.settings

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.FirebaseApp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.jordanrburger.scout.ScoutApp
import com.jordanrburger.scout.service.ScoutNotificationListenerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = ScoutApp.container(context)
    val vm: SettingsViewModel = viewModel(initializer = {
        SettingsViewModel(container.settings, container.api)
    })

    var url by remember { mutableStateOf(vm.settings.bridgeUrl.value) }
    var secret by remember { mutableStateOf(vm.settings.secret.value) }
    var showAppPicker by remember { mutableStateOf(false) }
    val allowedPackages by vm.settings.allowedPackages.collectAsState()
    val captureEnabled by vm.settings.captureEnabled.collectAsState()
    val testState by vm.testState.collectAsState()

    val listenerGranted = remember {
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(
                ComponentName(context, ScoutNotificationListenerService::class.java)
            )
    }
    val firebaseConfigured = remember { FirebaseApp.getApps(context).isNotEmpty() }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Bridge pairing", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { barcode ->
                        if (vm.pairFrom(barcode.rawValue.orEmpty())) {
                            url = vm.settings.bridgeUrl.value
                            secret = vm.settings.secret.value
                        }
                    }
            }) { Text("Scan QR to pair") }
            Text(
                "Run bridge/run-bridge.sh on the Mac and scan the QR it prints — " +
                    "or fill the fields manually below.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Bridge URL (e.g. http://192.168.1.10:8787)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Shared secret") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save(url, secret) }) { Text("Save") }
                OutlinedButton(onClick = {
                    vm.save(url, secret)
                    vm.testConnection()
                }) { Text("Test connection") }
            }
            when (val state = testState) {
                is TestConnectionState.Success ->
                    Text(state.message, color = MaterialTheme.colorScheme.primary)
                is TestConnectionState.Failure ->
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                TestConnectionState.Testing -> Text("Testing…")
                TestConnectionState.Idle -> {}
            }

            HorizontalDivider()
            Text("Notification capture", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Capture enabled", modifier = Modifier.weight(1f))
                Switch(
                    checked = captureEnabled,
                    onCheckedChange = { vm.settings.setCaptureEnabled(it) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (listenerGranted) "Notification access: granted ✓"
                    else "Notification access: NOT granted",
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text(if (listenerGranted) "Manage" else "Grant") }
            }
            Text(
                if (allowedPackages.isEmpty())
                    "No apps selected — Scout captures nothing until you choose which apps to monitor."
                else
                    "Monitoring ${allowedPackages.size} app(s). Only their notifications are captured and forwarded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { showAppPicker = true }) {
                Text("Choose apps to monitor (${allowedPackages.size})")
            }
            if (showAppPicker) {
                AppPickerDialog(
                    current = allowedPackages,
                    onConfirm = {
                        vm.settings.setAllowedPackages(it)
                        showAppPicker = false
                    },
                    onDismiss = { showAppPicker = false },
                )
            }

            HorizontalDivider()
            Text("Push (FCM)", style = MaterialTheme.typography.titleMedium)
            Text(
                if (firebaseConfigured) "Firebase configured — topic scout-default"
                else "Not configured. Add google-services.json to app/ and rebuild (README §Firebase).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private data class InstalledApp(val label: String, val pkg: String)

@Composable
private fun AppPickerDialog(
    current: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
    var selection by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps Scout may monitor") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(apps.size, key = { apps[it].pkg }) { i ->
                    val app = apps[i]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selection = if (app.pkg in selection) selection - app.pkg
                                else selection + app.pkg
                            },
                    ) {
                        Checkbox(
                            checked = app.pkg in selection,
                            onCheckedChange = { checked ->
                                selection = if (checked) selection + app.pkg else selection - app.pkg
                            },
                        )
                        Column {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                app.pkg,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selection) }) { Text("Save (${selection.size})") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
