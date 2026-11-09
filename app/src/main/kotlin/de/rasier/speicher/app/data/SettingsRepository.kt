package de.rasier.speicher.app.data

import android.content.Context
import de.rasier.speicher.cloud.MarstekCloudClient
import de.rasier.speicher.protocol.ct002.Ct002Protocol
import de.rasier.speicher.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App settings as JSON in SharedPreferences, plus the encrypted password hash. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    private val _settings = MutableStateFlow(AppSettings.fromJson(prefs.getString(KEY_SETTINGS, null)))
    val settings: StateFlow<AppSettings> = _settings

    fun save(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, settings.toJson()).apply()
        _settings.value = settings
    }

    /** Hashes the password immediately; the plain password is not kept anywhere. */
    fun setPassword(password: String?) {
        secrets.put(SecretStore.PASSWORD_MD5, password?.takeIf { it.isNotBlank() }?.let { MarstekCloudClient.md5Hex(it) })
    }

    fun passwordMd5(): String? = secrets.get(SecretStore.PASSWORD_MD5)

    val hasPassword: Boolean get() = passwordMd5() != null

    /** Random id with which the app identifies itself to the CT002. Created once, never a battery id. */
    fun ctReaderId(): String = prefs.getString(KEY_READER_ID, null) ?: Ct002Protocol.newReaderId().also {
        prefs.edit().putString(KEY_READER_ID, it).apply()
    }

    var backgroundServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE, value).apply()

    companion object {
        private const val KEY_SETTINGS = "app_settings"
        private const val KEY_READER_ID = "ct_reader_id"
        private const val KEY_SERVICE = "background_service"
    }
}
