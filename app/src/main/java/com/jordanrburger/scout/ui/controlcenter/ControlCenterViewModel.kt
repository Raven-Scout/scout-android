package com.jordanrburger.scout.ui.controlcenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jordanrburger.scout.data.StatusRepository
import com.jordanrburger.scout.data.StatusState
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.ConnectorDto
import com.jordanrburger.scout.data.api.NextRunDto
import com.jordanrburger.scout.data.api.RunSummaryDto
import com.jordanrburger.scout.data.api.RunningDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ControlCenterUiState(
    val loading: Boolean = true,
    val running: RunningDto = RunningDto(active = false),
    val nextRun: NextRunDto? = null,
    val todaySpendUsd: Double = 0.0,
    val connectors: List<ConnectorDto> = emptyList(),
    val recentRuns: List<RunSummaryDto> = emptyList(),
    val stale: Boolean = false,
    val unauthorized: Boolean = false,
    val error: String? = null,
)

/** One-shot UI events the screen reacts to (dialogs / snackbars). */
sealed interface ControlCenterEvent {
    data class BudgetPrompt(val type: String?, val runId: String?) : ControlCenterEvent
    data class Notice(val message: String) : ControlCenterEvent
}

class ControlCenterViewModel(
    private val repository: StatusRepository,
) : ViewModel() {

    val ui = repository.state.map { state ->
        when (state) {
            StatusState.Loading -> ControlCenterUiState(loading = true)
            is StatusState.Loaded -> ControlCenterUiState(
                loading = false,
                running = state.status.running,
                nextRun = state.status.nextRun,
                todaySpendUsd = state.status.todaySpendUsd,
                connectors = state.status.connectors,
                recentRuns = state.status.recentRuns,
                stale = state.stale,
            )
            is StatusState.Error -> ControlCenterUiState(loading = false, error = state.message)
            StatusState.Unauthorized -> ControlCenterUiState(loading = false, unauthorized = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ControlCenterUiState())

    private val _events = MutableSharedFlow<ControlCenterEvent>(extraBufferCapacity = 4)
    val events = _events

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun runNow(type: String, bypassBudget: Boolean = false) {
        viewModelScope.launch {
            handleLaunch(repository.runNow(type, bypassBudget), type = type, runId = null)
        }
    }

    fun retry(runId: String, bypassBudget: Boolean = false) {
        viewModelScope.launch {
            handleLaunch(repository.retry(runId, bypassBudget), type = null, runId = runId)
        }
    }

    private suspend fun handleLaunch(result: ApiResult<Unit>, type: String?, runId: String?) {
        when (result) {
            is ApiResult.Ok -> {
                _events.emit(ControlCenterEvent.Notice("Started — watching for it to run…"))
                repository.refresh()
            }
            is ApiResult.Failed -> when (result.error) {
                "over_budget" -> _events.emit(ControlCenterEvent.BudgetPrompt(type, runId))
                "already_running" ->
                    _events.emit(ControlCenterEvent.Notice("A run is already in progress"))
                else -> _events.emit(ControlCenterEvent.Notice("Couldn't start: ${result.error}"))
            }
            ApiResult.Unauthorized ->
                _events.emit(ControlCenterEvent.Notice("Pairing broken — check Settings"))
            is ApiResult.Unreachable ->
                _events.emit(ControlCenterEvent.Notice("Bridge unreachable"))
        }
    }
}
