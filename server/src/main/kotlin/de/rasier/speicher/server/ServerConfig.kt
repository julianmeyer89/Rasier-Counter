package de.rasier.speicher.server

import de.rasier.speicher.control.AutomationSettings
import de.rasier.speicher.energy.SystemConfig
import de.rasier.speicher.energy.Tariff
import java.io.File
import java.util.Properties

/**
 * Server configuration. Credentials and certificate paths come from a properties file and/or
 * environment variables and are NEVER committed. Env vars (RASIER_*) override the file.
 */
data class ServerConfig(
    val demo: Boolean,
    val cloudBaseUrl: String,
    val email: String,
    /** MD5 hash of the Marstek password (lower-case hex). The plain password is never stored. */
    val passwordMd5: String,
    /** Directory holding ca.crt, client.crt, client.key and broker-url (from the hame-relay image). */
    val certDir: String,
    /** Explicit broker URL override (otherwise read from <certDir>/broker-url). */
    val brokerUrl: String?,
    val dataDir: String,
    val webPort: Int,
    /** Shared secret required by the web/API (defense in depth behind Tailscale). Empty = none. */
    val authToken: String,
    /** Poll interval for the CT002 smart meter (seconds, fractional). The meter changes fast; this is the loop rate. */
    val ct002PollIntervalSeconds: Double,
    /** Poll interval for the batteries (seconds). They change slowly, so we poll them less often. */
    val batteryPollIntervalSeconds: Int,
    val cloudHttpIntervalSeconds: Int,
    val automationsMayWrite: Boolean,
    val useNoFlashCommands: Boolean,
    /** Log the raw cloud payloads for diagnosis. */
    val debug: Boolean,
    val tariff: Tariff,
    val system: SystemConfig,
    val automation: AutomationSettings,
) {
    companion object {
        fun load(file: File?): ServerConfig {
            val p = Properties()
            file?.takeIf { it.exists() }?.inputStream()?.use { p.load(it) }

            fun str(key: String, default: String = ""): String =
                System.getenv("RASIER_${key.uppercase()}") ?: p.getProperty(key) ?: default
            fun int(key: String, default: Int): Int = str(key).toIntOrNull() ?: default
            fun dbl(key: String, default: Double): Double = str(key).toDoubleOrNull() ?: default
            fun bool(key: String, default: Boolean): Boolean = str(key).lowercase().let {
                if (it.isBlank()) default else it in setOf("1", "true", "yes", "on", "ja")
            }

            val rawPassword = str("password")
            val passwordMd5 = str("password_md5").ifBlank {
                if (rawPassword.isNotBlank()) de.rasier.speicher.cloud.MarstekCloudClient.md5Hex(rawPassword) else ""
            }

            return ServerConfig(
                demo = bool("demo", false),
                cloudBaseUrl = str("cloud_base_url", "https://eu.hamedata.com"),
                email = str("email"),
                passwordMd5 = passwordMd5,
                certDir = str("cert_dir", "certs"),
                brokerUrl = str("broker_url").ifBlank { null },
                dataDir = str("data_dir", "data"),
                webPort = int("web_port", 8080),
                authToken = str("auth_token"),
                // Legacy single "poll_interval_seconds" still works as the battery fallback.
                ct002PollIntervalSeconds = dbl("ct002_poll_interval_seconds", 2.0).coerceIn(0.5, 3600.0),
                batteryPollIntervalSeconds = int("battery_poll_interval_seconds", int("poll_interval_seconds", 5)).coerceIn(5, 3600),
                cloudHttpIntervalSeconds = int("cloud_http_interval_seconds", 300),
                automationsMayWrite = bool("automations_may_write", false),
                useNoFlashCommands = bool("use_no_flash_commands", false),
                debug = bool("debug", false),
                tariff = Tariff(
                    importCentPerKwh = dbl("import_cent_per_kwh", 30.0),
                    feedInCentPerKwh = dbl("feed_in_cent_per_kwh", 0.0),
                    baseFeeEuroPerMonth = dbl("base_fee_euro_per_month", 0.0),
                ),
                system = SystemConfig(
                    inverterEfficiency = dbl("inverter_efficiency", 0.95),
                    inverterMaxW = int("inverter_max_w", 1600),
                ),
                automation = AutomationSettings(),
            )
        }
    }
}
