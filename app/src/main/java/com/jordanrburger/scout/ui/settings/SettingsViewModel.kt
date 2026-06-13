package com.jordanrburger.scout.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jordanrburger.scout.data.api.ApiResult
import com.jordanrburger.scout.data.api.BridgeApi
import com.jordanrburger.scout.data.settings.SettingsRepository
import com.jordanrburger.scout.domain.PairingUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface TestConnectionState {
    data object Idle : TestConnectionState
    data object Testing : TestConnectionState
    data class Success(val message: String) : TestConnectionState
    data class Failure(val message: String) : TestConnectionState
}

class SettingsViewModel(
    val settings: SettingsRepository,
    private val api: BridgeApi,
) : ViewModel() {

    private val _testState = MutableStateFlow<TestConnectionState>(TestConnectionState.Idle)
    val testState: StateFlow<TestConnectionState> = _testState

    fun save(bridgeUrl: String, secret: String) {
        settings.setBridgeUrl(bridgeUrl.trim().trimEnd('/'))
        settings.setSecret(secret.trim())
    }

    /** Pair from a scanned QR / deep-link payload. Returns false on a payload
     *  that isn't a Scout pairing URI (leaves current settings untouched). */
    fun pairFrom(raw: String): Boolean {
        val config = PairingUri.parse(raw) ?: return false
        save(config.baseUrl, config.secret)
        testConnection()
        return true
    }

    fun testConnection() {
        _testState.value = TestConnectionState.Testing
        viewModelScope.launch {
            _testState.value = when (val result = api.health()) {
                is ApiResult.Ok ->
                    TestConnectionState.Success("Connected — vault date ${result.value.vaultDate ?: "?"}")
                ApiResult.Unauthorized -> TestConnectionState.Failure("Secret rejected (401)")
                is ApiResult.Unreachable ->
                    TestConnectionState.Failure("Unreachable: ${result.cause ?: "no route"}")
                is ApiResult.Failed -> TestConnectionState.Failure(result.error)
            }
        }
    }
}
