package com.jordanrburger.scout.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Single Json config shared by app + tests: tolerant reads, explicit writes. */
val ScoutJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Passthrough of one `scoutctl action-items list --json` element (bridge spec §4). */
@Serializable
data class ActionItemDto(
    val title: String,
    val priority: String = "",
    val status: String = "open",
    val section: String = "",
    @SerialName("short_prefix") val shortPrefix: String? = null,
)

@Serializable
data class ActionItemsResponse(
    val date: String,
    @SerialName("generated_at") val generatedAt: String,
    val items: List<ActionItemDto>,
)

/** Phone→engine notification event, schema versioned via [v] (bridge spec §4). */
@Serializable
data class NotificationEventDto(
    val key: String,
    @SerialName("package") val pkg: String,
    @SerialName("post_time_ms") val postTimeMs: Long,
    val title: String?,
    val text: String?,
    @SerialName("big_text") val bigText: String?,
    val category: String?,
    @SerialName("is_ongoing") val isOngoing: Boolean,
    val removed: Boolean,
    @SerialName("captured_at") val capturedAt: String,
    val v: Int = 1,
)

@Serializable
data class EventsRequest(val events: List<NotificationEventDto>)

@Serializable
data class EventsResponse(val ok: Boolean, val accepted: Int = 0)

@Serializable
data class OkResponse(val ok: Boolean, val error: String? = null)

@Serializable
data class HealthResponse(
    val ok: Boolean,
    @SerialName("vault_date") val vaultDate: String? = null,
    val scoutctl: Boolean = false,
)

@Serializable
data class SnoozeRequest(val until: String)

// --- Control Center (Pass 1) -------------------------------------------------

@Serializable
data class StatusResponse(
    val running: RunningDto,
    @SerialName("last_run") val lastRun: RunSummaryDto? = null,
    @SerialName("next_run") val nextRun: NextRunDto? = null,
    @SerialName("today_spend_usd") val todaySpendUsd: Double = 0.0,
    val connectors: List<ConnectorDto> = emptyList(),
    @SerialName("recent_runs") val recentRuns: List<RunSummaryDto> = emptyList(),
)

@Serializable
data class RunningDto(
    val active: Boolean,
    val type: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    val pid: Int? = null,
)

@Serializable
data class RunSummaryDto(
    val id: String,
    val type: String,
    @SerialName("started_at") val startedAt: String,
    val outcome: String,
    @SerialName("spend_usd") val spendUsd: Double = 0.0,
)

@Serializable
data class NextRunDto(
    val slot: String,
    val type: String,
    @SerialName("fires_at_local") val firesAtLocal: String,
    @SerialName("fires_at") val firesAt: String? = null,
)

@Serializable
data class ConnectorDto(val name: String, val status: String)

@Serializable
data class RunRequest(
    val type: String,
    @SerialName("bypass_budget") val bypassBudget: Boolean = false,
)

@Serializable
data class RetryRequest(
    @SerialName("run_id") val runId: String,
    @SerialName("bypass_budget") val bypassBudget: Boolean = false,
)

@Serializable
data class RunStartedResponse(val started: Boolean, val reason: String? = null)

@Serializable
data class RunLogResponse(val id: String, val lines: List<String> = emptyList())
