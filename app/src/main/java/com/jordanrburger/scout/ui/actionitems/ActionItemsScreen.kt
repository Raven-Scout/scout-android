package com.jordanrburger.scout.ui.actionitems

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jordanrburger.scout.ScoutApp
import com.jordanrburger.scout.domain.Priority
import com.jordanrburger.scout.domain.SectionKind
import com.jordanrburger.scout.domain.Segment
import com.jordanrburger.scout.domain.Style
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionItemsScreen(
    highlightPrefix: String?,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ActionItemsViewModel = viewModel(initializer = {
        ActionItemsViewModel(ScoutApp.container(context).actionItemsRepository)
    })
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notice: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    LaunchedEffect(Unit) { vm.refresh() }

    // Header date rendered in the DEVICE's timezone + locale — never hardcoded ET
    // (Jordan's "traveled to a different timezone and it sucks" musing).
    val today = remember {
        LocalDate.now().format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault())
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Action Items", style = MaterialTheme.typography.titleLarge)
                        Text(
                            today + (ui.lastSyncedMs?.let { "  ·  synced ${formatTime(it)}" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                actions = {
                    if (ui.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    } else {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (ui.unauthorized) {
                Banner(
                    text = "Pairing broken — secret rejected by the bridge. Open Settings.",
                    color = MaterialTheme.colorScheme.errorContainer,
                    onClick = onOpenSettings,
                )
            } else if (ui.stale) {
                Banner(
                    text = "Offline — showing the last synced list.",
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                )
            }

            when {
                ui.error != null && ui.sections.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(ui.error!!, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                        TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                    }
                }
                else -> SectionList(ui, highlightPrefix, vm, notice)
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(
        color = color,
        modifier = Modifier.fillMaxWidth().let { m -> onClick?.let { m.clickable(onClick = it) } ?: m },
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionList(
    ui: ActionItemsUiState,
    highlightPrefix: String?,
    vm: ActionItemsViewModel,
    notice: (String) -> Unit,
) {
    val expanded = remember { mutableStateOf(SectionKind.entries.filterNot { it.collapsedByDefault }.toSet()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        ui.sections.forEach { section ->
            item(key = "header-${section.kind}") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded.value = if (section.kind in expanded.value)
                                expanded.value - section.kind else expanded.value + section.kind
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        "${section.kind.emoji} ${section.kind.label} (${section.items.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (section.kind in expanded.value) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                }
            }
            if (section.kind in expanded.value) {
                items(section.items.size, key = { i -> section.items[i].prefix ?: "${section.kind}-$i" }) { i ->
                    ItemCard(section.items[i], highlightPrefix, vm, notice)
                }
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: UiItem,
    highlightPrefix: String?,
    vm: ActionItemsViewModel,
    notice: (String) -> Unit,
) {
    var expanded by remember(item.prefix) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val highlighted = highlightPrefix != null && item.prefix == highlightPrefix

    Card(
        colors = if (highlighted)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = item.done,
                onCheckedChange = { checked ->
                    if (checked && !item.done && item.prefix != null) {
                        vm.markDone(item.prefix, notice)
                    }
                },
                enabled = item.prefix != null && !item.done,
            )
            Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityDot(item.priority)
                    item.prefix?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "#$it",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Text(
                    annotate(item.segments, struck = item.done),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.prefix != null && !item.done) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        snoozePresets().forEach { (label, date) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    menuOpen = false
                                    vm.snooze(item.prefix, date.toString(), notice)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorityDot(priority: Priority) {
    val color = when (priority) {
        Priority.URGENT -> Color(0xFFD64545)
        Priority.TODO -> Color(0xFFE8B931)
        Priority.WATCHING -> Color(0xFF4C9A52)
        Priority.NONE -> MaterialTheme.colorScheme.outline
    }
    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
}

private fun snoozePresets(): List<Pair<String, LocalDate>> {
    val today = LocalDate.now()
    return listOf(
        "Snooze to tomorrow" to today.plusDays(1),
        "Snooze +2 days" to today.plusDays(2),
        "Snooze to next Monday" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
    )
}

private fun formatTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

private fun annotate(segments: List<Segment>, struck: Boolean): AnnotatedString =
    buildAnnotatedString {
        segments.forEach { segment ->
            val base = when (segment.style) {
                Style.PLAIN -> SpanStyle()
                Style.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
            }
            val style = if (struck) base.copy(textDecoration = TextDecoration.LineThrough) else base
            pushStyle(style)
            append(segment.text)
            pop()
        }
    }
