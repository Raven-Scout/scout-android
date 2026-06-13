package com.jordanrburger.scout.testutil

import com.jordanrburger.scout.data.SnapshotStore
import com.jordanrburger.scout.data.api.ActionItemDto
import com.jordanrburger.scout.data.api.ActionItemsResponse
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.BridgeApi
import com.jordanrburger.scout.data.api.BridgeConfig
import com.jordanrburger.scout.data.api.HealthResponse
import com.jordanrburger.scout.data.api.NotificationEventDto
import com.jordanrburger.scout.data.api.RunLogResponse
import com.jordanrburger.scout.data.api.RunningDto
import com.jordanrburger.scout.data.api.StatusResponse
import com.jordanrburger.scout.data.db.CapturedNotification
import com.jordanrburger.scout.data.db.CapturedNotificationDao
import com.jordanrburger.scout.data.db.PendingAction
import com.jordanrburger.scout.data.db.PendingActionDao
import com.jordanrburger.scout.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

fun response(vararg prefixes: String) = ActionItemsResponse(
    date = "2026-06-12",
    generatedAt = "2026-06-12T20:00:00Z",
    items = prefixes.map {
        ActionItemDto(title = "item $it", priority = "🔴", status = "open",
            section = "🔴 Urgent", shortPrefix = it)
    },
)

fun captured(key: String, t: Long, ongoing: Boolean = false) = CapturedNotification(
    key = key, pkg = "p", postTimeMs = t, title = "t", text = "x", bigText = null,
    category = null, isOngoing = ongoing, removed = false, capturedAt = "2026-06-12T15:00:00Z",
)

class FakeApi : BridgeApi {
    var healthResult: ApiResult<HealthResponse> = ApiResult.Ok(HealthResponse(true, "2026-06-12", true))
    var todayResult: ApiResult<ActionItemsResponse> = ApiResult.Ok(response("A1"))
    var markDoneResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var snoozeResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var postEventsResult: ApiResult<Int> = ApiResult.Ok(0)
    val markedDone = mutableListOf<String>()
    val snoozed = mutableListOf<Pair<String, String>>()
    val postedBatches = mutableListOf<List<NotificationEventDto>>()
    override suspend fun health() = healthResult
    override suspend fun today() = todayResult
    override suspend fun markDone(prefix: String): ApiResult<Unit> {
        markedDone.add(prefix); return markDoneResult
    }
    override suspend fun snooze(prefix: String, untilIso: String): ApiResult<Unit> {
        snoozed.add(prefix to untilIso); return snoozeResult
    }
    override suspend fun postEvents(events: List<NotificationEventDto>): ApiResult<Int> {
        postedBatches.add(events)
        return if (postEventsResult is ApiResult.Ok) ApiResult.Ok(events.size) else postEventsResult
    }

    var statusResult: ApiResult<StatusResponse> =
        ApiResult.Ok(StatusResponse(running = RunningDto(active = false)))
    var runNowResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var retryResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var runLogResult: ApiResult<RunLogResponse> = ApiResult.Ok(RunLogResponse("x", listOf("log")))
    val runNowCalls = mutableListOf<Pair<String, Boolean>>()
    val retryCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun status(): ApiResult<StatusResponse> = statusResult
    override suspend fun runNow(type: String, bypassBudget: Boolean): ApiResult<Unit> {
        runNowCalls.add(type to bypassBudget); return runNowResult
    }
    override suspend fun retryRun(runId: String, bypassBudget: Boolean): ApiResult<Unit> {
        retryCalls.add(runId to bypassBudget); return retryResult
    }
    override suspend fun runLog(runId: String, tail: Int): ApiResult<RunLogResponse> = runLogResult
}

class FakeSnapshots : SnapshotStore {
    var stored: String? = null
    override fun save(json: String) { stored = json }
    override fun load(): String? = stored
}

class FakePendingDao : PendingActionDao {
    val rows = mutableListOf<PendingAction>()
    private var nextId = 1L
    override suspend fun insert(action: PendingAction): Long {
        val row = action.copy(id = nextId++); rows.add(row); return row.id
    }
    override suspend fun all() = rows.toList()
    override suspend fun delete(id: Long) { rows.removeAll { it.id == id } }
    override suspend fun bumpAttempts(id: Long) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(attempts = rows[i].attempts + 1)
    }
}

class FakeCapturedDao : CapturedNotificationDao {
    val rows = mutableListOf<CapturedNotification>()
    private var nextId = 1L
    private val flow = MutableStateFlow<List<CapturedNotification>>(emptyList())
    override suspend fun insert(notification: CapturedNotification): Long {
        val row = notification.copy(id = nextId++); rows.add(row); flow.value = rows.toList(); return row.id
    }
    override suspend fun unforwarded(limit: Int) =
        rows.filter { it.forwardedAt == null }.sortedBy { it.postTimeMs }.take(limit)
    override suspend fun markForwarded(ids: List<Long>, timestamp: Long) {
        rows.replaceAll { if (it.id in ids) it.copy(forwardedAt = timestamp) else it }
        flow.value = rows.toList()
    }
    override fun recent(): Flow<List<CapturedNotification>> =
        flow.map { it.sortedByDescending { n -> n.postTimeMs } }
    override suspend fun pruneForwarded(olderThanMs: Long) {
        rows.removeAll { it.forwardedAt != null && it.postTimeMs < olderThanMs }
    }
    override suspend fun deleteAllUnforwarded() {
        rows.removeAll { it.forwardedAt == null }
    }
    override suspend fun deleteUnforwardedNotIn(allowed: Set<String>) {
        rows.removeAll { it.forwardedAt == null && it.pkg !in allowed }
    }
}

class InMemorySettings : SettingsRepository {
    override val bridgeUrl = MutableStateFlow("")
    override val secret = MutableStateFlow("")
    override val captureEnabled = MutableStateFlow(true)
    override val allowedPackages = MutableStateFlow(emptySet<String>())
    override fun setBridgeUrl(url: String) { bridgeUrl.value = url }
    override fun setSecret(secret: String) { this.secret.value = secret }
    override fun setCaptureEnabled(enabled: Boolean) { captureEnabled.value = enabled }
    override fun setAllowedPackages(packages: Set<String>) { allowedPackages.value = packages }
    override fun bridgeConfig() = BridgeConfig(bridgeUrl.value, secret.value)
}
