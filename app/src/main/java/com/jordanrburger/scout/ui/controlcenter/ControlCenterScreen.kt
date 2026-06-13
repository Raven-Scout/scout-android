package com.jordanrburger.scout.ui.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jordanrburger.scout.ScoutApp
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.RunSummaryDto
import com.jordanrburger.scout.data.StatusRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val RUN_TYPES = listOf("consolidation", "briefing", "research", "dreaming")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlCenterScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { StatusRepository(ScoutApp.container(context).api) }
    val vm: ControlCenterViewModel = viewModel(initializer = { ControlCenterViewModel(repo) })
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var sheetOpen by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf<String?>(null) }
    var budgetPrompt by remember { mutableStateOf<ControlCenterEvent.BudgetPrompt?>(null) }
    var openRun by remember { mutableStateOf<RunSummaryDto?>(null) }
    var fastPollUntil by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is ControlCenterEvent.Notice -> snackbar.showSnackbar(event.message)
                is ControlCenterEvent.BudgetPrompt -> budgetPrompt = event
            }
        }
    }
    // Poll while foregrounded: ~5s for 30s after a launch, else ~15s.
    LaunchedEffect(ui.running.active) {
        while (true) {
            vm.refresh()
            val fast = System.currentTimeMillis() < fastPollUntil
            delay(if (fast) 5_000 else 15_000)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Control Center") }) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.unauthorized -> Banner("Pairing broken — open Settings.",
                    MaterialTheme.colorScheme.errorContainer, onOpenSettings)
                ui.stale -> Banner("Offline — last known status.",
                    MaterialTheme.colorScheme.tertiaryContainer, null)
            }
            if (ui.error != null && !ui.stale) {
                Column(Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!)
                    TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                    TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                }
            } else {
                ControlCenterBody(ui, onRunNow = { sheetOpen = true },
                    onTapRun = { openRun = it })
            }
        }
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheetState) {
            Text("Run a session now", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp))
            RUN_TYPES.forEach { type ->
                ListItem(
                    headlineContent = { Text(type.replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        pendingType = type
                    },
                )
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    pendingType?.let { type ->
        AlertDialog(
            onDismissRequest = { pendingType = null },
            title = { Text("Run ${type}?") },
            text = { Text("This starts a real Scout run on your Mac and spends tokens. Continue?") },
            confirmButton = {
                Button(onClick = {
                    pendingType = null
                    fastPollUntil = System.currentTimeMillis() + 30_000
                    vm.runNow(type)
                }) { Text("Run now") }
            },
            dismissButton = { OutlinedButton(onClick = { pendingType = null }) { Text("Cancel") } },
        )
    }

    budgetPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { budgetPrompt = null },
            title = { Text("Over budget") },
            text = { Text("Scout's token budget for now is used up. Run anyway?") },
            confirmButton = {
                Button(onClick = {
                    budgetPrompt = null
                    fastPollUntil = System.currentTimeMillis() + 30_000
                    if (prompt.runId != null) vm.retry(prompt.runId, bypassBudget = true)
                    else if (prompt.type != null) vm.runNow(prompt.type, bypassBudget = true)
                }) { Text("Run anyway") }
            },
            dismissButton = { OutlinedButton(onClick = { budgetPrompt = null }) { Text("Cancel") } },
        )
    }

    openRun?.let { run ->
        RunDetailDialog(run, repo, onRetry = {
            openRun = null
            fastPollUntil = System.currentTimeMillis() + 30_000
            vm.retry(run.id)
        }, onDismiss = { openRun = null })
    }
}

@Composable
private fun ControlCenterBody(
    ui: ControlCenterUiState,
    onRunNow: () -> Unit,
    onTapRun: (RunSummaryDto) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.running.active) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Running ${ui.running.type ?: "session"}" +
                            (ui.running.startedAt?.let { " · since ${time(it)}" } ?: ""),
                            style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("● Idle", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        item {
            Button(onClick = onRunNow, modifier = Modifier.fillMaxWidth()) { Text("Run a session now") }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.nextRun?.let {
                        InfoRow("Next run", "${it.type} · ${it.firesAtLocal} (${it.slot})")
                    }
                    InfoRow("Today's spend", "$" + String.format(Locale.US, "%.2f", ui.todaySpendUsd))
                }
            }
        }
        if (ui.connectors.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Connectors", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.size(8.dp))
                        ui.connectors.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp)) {
                                Box(Modifier.size(10.dp).background(connectorColor(c.status), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(c.name, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Text(c.status, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        item { Text("Recent runs", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
        items(ui.recentRuns, key = { it.id }) { run ->
            Card(modifier = Modifier.clickable { onTapRun(run) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(run.type, style = MaterialTheme.typography.bodyLarge)
                        Text(time(run.startedAt), style = MaterialTheme.typography.labelSmall)
                    }
                    OutcomeChip(run.outcome)
                }
            }
        }
    }
}

@Composable
private fun RunDetailDialog(
    run: RunSummaryDto,
    repo: StatusRepository,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var lines by remember(run.id) { mutableStateOf(listOf("Loading…")) }
    LaunchedEffect(run.id) {
        lines = when (val r = repo.runLog(run.id, 200)) {
            is ApiResult.Ok -> r.value.lines.ifEmpty { listOf("(empty log)") }
            else -> listOf("(couldn't load log)")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${run.type} · ${run.outcome}") },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(lines) { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            if (run.outcome == "failure") Button(onClick = onRetry) { Text("Retry") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (run.outcome == "failure") OutlinedButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OutcomeChip(outcome: String) {
    val color = when (outcome) {
        "success" -> Color(0xFF4C9A52)
        "failure" -> Color(0xFFD64545)
        "running" -> Color(0xFFE8B931)
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(color = color.copy(alpha = 0.18f), shape = CircleShape) {
        Text(outcome, color = color, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun Banner(text: String, color: Color, onClick: (() -> Unit)?) {
    Surface(color = color, modifier = Modifier.fillMaxWidth()
        .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun connectorColor(status: String) = when (status) {
    "ok" -> Color(0xFF4C9A52)
    "dark" -> Color(0xFFD64545)
    else -> Color(0xFF999999)
}

/** Renders an ISO instant/local-datetime in the device's tz + locale. */
private fun time(iso: String): String = try {
    val instant = if (iso.endsWith("Z") || iso.contains("+")) Instant.parse(iso)
    else java.time.LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant()
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(instant)
} catch (e: Exception) {
    iso
}

