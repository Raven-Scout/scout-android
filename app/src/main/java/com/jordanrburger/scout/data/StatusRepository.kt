package com.jordanrburger.scout.data

import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.BridgeApi
import com.jordanrburger.scout.data.api.StatusResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface StatusState {
    data object Loading : StatusState
    data class Loaded(val status: StatusResponse, val stale: Boolean) : StatusState
    data class Error(val message: String) : StatusState
    data object Unauthorized : StatusState
}

/** Mirrors ActionItemsRepository, minus the disk snapshot — status is ephemeral,
 *  so a stale in-memory copy is enough to ride out a blip. */
class StatusRepository(private val api: BridgeApi) {

    private val _state = MutableStateFlow<StatusState>(StatusState.Loading)
    val state: StateFlow<StatusState> = _state

    private var lastGood: StatusResponse? = null

    suspend fun refresh() {
        when (val result = api.status()) {
            is ApiResult.Ok -> {
                lastGood = result.value
                _state.value = StatusState.Loaded(result.value, stale = false)
            }
            ApiResult.Unauthorized -> _state.value = StatusState.Unauthorized
            is ApiResult.Unreachable, is ApiResult.Failed -> {
                val snapshot = lastGood
                _state.value = if (snapshot != null) StatusState.Loaded(snapshot, stale = true)
                else StatusState.Error(describe(result))
            }
        }
    }

    suspend fun runNow(type: String, bypassBudget: Boolean = false): ApiResult<Unit> =
        api.runNow(type, bypassBudget)

    suspend fun retry(runId: String, bypassBudget: Boolean = false): ApiResult<Unit> =
        api.retryRun(runId, bypassBudget)

    suspend fun runLog(runId: String, tail: Int = 200) = api.runLog(runId, tail)

    private fun describe(result: ApiResult<*>) = when (result) {
        is ApiResult.Unreachable -> "Bridge unreachable${result.cause?.let { ": $it" }.orEmpty()}"
        is ApiResult.Failed -> result.error
        else -> "unknown error"
    }
}
