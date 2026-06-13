package com.jordanrburger.scout.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jordanrburger.scout.data.api.BridgeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val bridgeUrl: StateFlow<String>
    val secret: StateFlow<String>
    val captureEnabled: StateFlow<Boolean>

    /** Opt-in capture allowlist — empty means capture NOTHING (default). */
    val allowedPackages: StateFlow<Set<String>>
    fun setBridgeUrl(url: String)
    fun setSecret(secret: String)
    fun setCaptureEnabled(enabled: Boolean)
    fun setAllowedPackages(packages: Set<String>)
    fun bridgeConfig(): BridgeConfig
}

/** Backed by EncryptedSharedPreferences (`scout_secure.xml` — excluded from
 *  Auto Backup, spec §5). */
class EncryptedSettingsRepository(context: Context) : SettingsRepository {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "scout_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override val bridgeUrl = MutableStateFlow(prefs.getString(KEY_URL, "").orEmpty())
    override val secret = MutableStateFlow(prefs.getString(KEY_SECRET, "").orEmpty())
    override val captureEnabled = MutableStateFlow(prefs.getBoolean(KEY_CAPTURE, true))
    override val allowedPackages =
        MutableStateFlow(prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty().toSet())

    override fun setBridgeUrl(url: String) {
        prefs.edit().putString(KEY_URL, url).apply()
        bridgeUrl.value = url
    }

    override fun setSecret(secret: String) {
        prefs.edit().putString(KEY_SECRET, secret).apply()
        this.secret.value = secret
    }

    override fun setCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CAPTURE, enabled).apply()
        captureEnabled.value = enabled
    }

    override fun setAllowedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED, packages).apply()
        allowedPackages.value = packages
    }

    override fun bridgeConfig() = BridgeConfig(bridgeUrl.value, secret.value)

    private companion object {
        const val KEY_URL = "bridge_url"
        const val KEY_SECRET = "bridge_secret"
        const val KEY_CAPTURE = "capture_enabled"
        const val KEY_ALLOWED = "allowed_packages"
    }
}
