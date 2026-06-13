package com.jordanrburger.scout.ui.actionitems

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jordanrburger.scout.data.ActionItemsRepository
import com.jordanrburger.scout.data.ActionItemsState
import com.jordanrburger.scout.domain.MarkdownLite
import com.jordanrburger.scout.domain.Priority
import com.jordanrburger.scout.domain.SectionKind
import com.jordanrburger.scout.domain.Sections
import com.jordanrburger.scout.domain.Segment
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiItem(
    val prefix: String?,
    val segments: List<Segment>,
    val priority: Priority,
    val done: Boolean,
)

data class UiSection(val kind: SectionKind, val items: List<UiItem>)

data class ActionItemsUiState(
    val loading: Boolean = true,
    val sections: List<UiSection> = emptyList(),
    val stale: Boolean = false,
    val unauthorized: Boolean = false,
    val error: String? = null,
    val lastSyncedMs: Long? = null,
)

class ActionItemsViewModel(
    private val repository: ActionItemsRepository,
) : ViewModel() {

    val ui = repository.state.map { state ->
        when (state) {
            ActionItemsState.Loading -> ActionItemsUiState(loading = true)
            is ActionItemsState.Loaded -> ActionItemsUiState(
                loading = false,
                sections = Sections.organize(state.response.items).map { group ->
                    UiSection(
                        kind = group.kind,
                        items = group.items.map {
                            UiItem(it.prefix, MarkdownLite.parse(it.title), it.priority, it.done)
                        },
                    )
                },
                stale = state.stale,
                lastSyncedMs = state.lastSyncedMs,
            )
            is ActionItemsState.Error -> ActionItemsUiState(loading = false, error = state.message)
            ActionItemsState.Unauthorized -> ActionItemsUiState(loading = false, unauthorized = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ActionItemsUiState())

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun markDone(prefix: String, onNotice: (String) -> Unit = {}) {
        viewModelScope.launch { repository.markDone(prefix, onNotice) }
    }

    fun snooze(prefix: String, untilIso: String, onNotice: (String) -> Unit = {}) {
        viewModelScope.launch { repository.snooze(prefix, untilIso, onNotice) }
    }
}
