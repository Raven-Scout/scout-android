package com.jordanrburger.scout.ui.captures

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jordanrburger.scout.data.NotificationRepository
import com.jordanrburger.scout.data.db.CapturedNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class CapturesViewModel(
    repository: NotificationRepository,
) : ViewModel() {

    private val hideOngoing = MutableStateFlow(true)

    val ui = combine(repository.recent, hideOngoing) { list, hide ->
        if (hide) list.filterNot { it.isOngoing } else list
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<CapturedNotification>())

    fun setHideOngoing(hide: Boolean) {
        hideOngoing.value = hide
    }
}
