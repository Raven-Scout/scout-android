package com.jordanrburger.scout.data

import com.jordanrburger.scout.data.api.ActionItemsResponse
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.BridgeApi
import com.jordanrburger.scout.data.api.ScoutJson
import com.jordanrburger.scout.data.db.PendingAction
import com.jordanrburger.scout.data.db.PendingActionDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString

sealed interface ActionItemsState {
    data object Loading : ActionItemsState
    data class Loaded(
        val response: ActionItemsResponse,
        val stale: Boolean,
        val lastSyncedMs: Long?,
    ) : ActionItemsState
    data class Error(val message: String) : ActionItemsState
    data object Unauthorized : ActionItemsState
}

/** Persists the last good fetch so the list survives process death offline (spec §5). */
interface SnapshotStore {
    fun save(json: String)
    fun load(): String?
}

class ActionItemsRepository(
    private val api: BridgeApi,
    private val snapshots: SnapshotStore,
    private val pendingDao: PendingActionDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ActionItemsState>(ActionItemsState.Loading)
    val state: StateFlow<ActionItemsState> = _state

    suspend fun refresh() {
        when (val result = api.today()) {
            is ApiResult.Ok -> {
                snapshots.save(ScoutJson.encodeToString(result.value))
                _state.value = ActionItemsState.Loaded(result.value, stale = false, lastSyncedMs = now())
            }
            ApiResult.Unauthorized -> _state.value = ActionItemsState.Unauthorized
            is ApiResult.Unreachable, is ApiResult.Failed -> {
                val snapshot = snapshots.load()
                _state.value = if (snapshot != null) {
                    ActionItemsState.Loaded(
                        ScoutJson.decodeFromString<ActionItemsResponse>(snapshot),
                        stale = true,
                        lastSyncedMs = (_state.value as? ActionItemsState.Loaded)?.lastSyncedMs,
                    )
                } else {
                    ActionItemsState.Error(describe(result))
                }
            }
        }
    }

    /** Optimistic: flip locally first; queue when unreachable; roll back on rejection. */
    suspend fun markDone(prefix: String, onNotice: (String) -> Unit = {}) {
        val before = _state.value
        setStatus(prefix, "done")
        when (val result = api.markDone(prefix)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Unreachable ->
                pendingDao.insert(PendingAction(prefix = prefix, action = "done", createdAt = now()))
            is ApiResult.Failed -> {
                _state.value = before
                onNotice("Couldn't mark #$prefix done: ${result.error}")
            }
            ApiResult.Unauthorized -> {
                _state.value = before
                onNotice("Pairing broken — check Settings")
            }
        }
    }

    suspend fun snooze(prefix: String, untilIso: String, onNotice: (String) -> Unit = {}) {
        val before = _state.value
        removeItem(prefix)
        when (val result = api.snooze(prefix, untilIso)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Unreachable ->
                pendingDao.insert(
                    PendingAction(prefix = prefix, action = "snooze", payload = untilIso, createdAt = now())
                )
            is ApiResult.Failed -> {
                _state.value = before
                onNotice("Couldn't snooze #$prefix: ${result.error}")
            }
            ApiResult.Unauthorized -> {
                _state.value = before
                onNotice("Pairing broken — check Settings")
            }
        }
    }

    /** Queue drain (run by ForwardWorker before fetches). Success → delete; permanent
     *  rejection → delete + user notice (never a silent drop); unreachable → keep + bump. */
    suspend fun drainPending(
        onDropped: (String) -> Unit = {},
        execute: suspend (PendingAction) -> ApiResult<Unit> = { defaultExecute(it) },
    ) {
        for (row in pendingDao.all()) {
            when (execute(row)) {
                is ApiResult.Ok -> pendingDao.delete(row.id)
                is ApiResult.Failed, ApiResult.Unauthorized -> {
                    pendingDao.delete(row.id)
                    onDropped("Queued ${row.action} for #${row.prefix} was rejected and dropped")
                }
                is ApiResult.Unreachable -> pendingDao.bumpAttempts(row.id)
            }
        }
    }

    private suspend fun defaultExecute(row: PendingAction): ApiResult<Unit> = when (row.action) {
        "snooze" -> api.snooze(row.prefix, row.payload.orEmpty())
        else -> api.markDone(row.prefix)
    }

    private fun setStatus(prefix: String, status: String) = mutateLoaded { response ->
        response.copy(
            items = response.items.map {
                if (it.shortPrefix == prefix) it.copy(status = status) else it
            }
        )
    }

    private fun removeItem(prefix: String) = mutateLoaded { response ->
        response.copy(items = response.items.filterNot { it.shortPrefix == prefix })
    }

    private fun mutateLoaded(transform: (ActionItemsResponse) -> ActionItemsResponse) {
        val current = _state.value as? ActionItemsState.Loaded ?: return
        _state.value = current.copy(response = transform(current.response))
    }

    private fun describe(result: ApiResult<*>) = when (result) {
        is ApiResult.Unreachable -> "Bridge unreachable${result.cause?.let { ": $it" }.orEmpty()}"
        is ApiResult.Failed -> result.error
        else -> "unknown error"
    }
}
