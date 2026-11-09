package de.rasier.speicher.settings

import de.rasier.speicher.control.AutomationSettings
import de.rasier.speicher.energy.SystemConfig
import de.rasier.speicher.energy.Tariff
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where live data comes from. */
@Serializable
enum class DataMode {
    /** Simulated devices, for trying the app without hardware. */
    DEMO,

    /** Real devices: Bluetooth to the batteries, Wi-Fi to the CT002, Marstek cloud as fallback. */
    LIVE,
}

/** A battery known to the app. */
@Serializable
data class BatteryConfig(
    /** Bluetooth MAC address of the B2500 (e.g. from the Bluetooth scan in the app). */
    val bleAddress: String,
    val name: String,
    /** Cloud device id, if the battery was found in the Marstek account. */
    val cloudDevid: String? = null,
    /** Last known IP address (informational only). */
    val ip: String? = null,
)

@Serializable
data class AppSettings(
    val mode: DataMode = DataMode.DEMO,
    val email: String = "",
    /** Cloud region base URL. */
    val cloudBaseUrl: String = "https://eu.hamedata.com",
    val batteries: List<BatteryConfig> = listOf(
        BatteryConfig("", "Speicher 1", ip = "192.168.178.23"),
        BatteryConfig("", "Speicher 2", ip = "192.168.178.24"),
    ),
    val ct002Ip: String = "192.168.178.25",
    /** Seconds between two readings while the app is in the foreground or the service runs. */
    val pollIntervalSeconds: Int = 30,
    /** Seconds between two cloud requests (the cloud updates slowly, be gentle). */
    val cloudIntervalSeconds: Int = 300,
    val tariff: Tariff = Tariff(),
    val system: SystemConfig = SystemConfig(),
    val automation: AutomationSettings = AutomationSettings(),
    /** Automations may change device settings (otherwise they only suggest). */
    val automationsMayWrite: Boolean = false,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
        fun fromJson(text: String?): AppSettings =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: AppSettings()
    }
}
