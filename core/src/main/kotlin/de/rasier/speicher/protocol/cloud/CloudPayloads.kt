package de.rasier.speicher.protocol.cloud

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.ChargeMode
import de.rasier.speicher.model.MeterReading

/**
 * Parses and builds the cloud MQTT payloads of the Marstek devices.
 * Payloads are plain `key=value,key=value` text. Commands start with `cd=<code>`.
 * Implemented from the protocol description in docs/PROTOKOLLE.md.
 */
object CloudPayloads {

    fun parseFields(payload: String): Map<String, String> =
        payload.split(",").mapNotNull { token ->
            val i = token.indexOf('=')
            if (i <= 0) null else token.substring(0, i).trim() to token.substring(i + 1).trim()
        }.toMap()

    /** True if the payload looks like a B2500 runtime frame (cd=1 reply). */
    fun isBatteryRuntime(fields: Map<String, String>): Boolean = fields.containsKey("pe") && fields.containsKey("kn")

    /** True if the payload looks like a CT002 runtime frame (cd=1 reply). */
    fun isMeterRuntime(fields: Map<String, String>): Boolean = fields.containsKey("pwr_t") || fields.containsKey("pwr_a")

    fun parseBattery(fields: Map<String, String>, id: String, name: String, type: String?, timestampMs: Long): BatteryState? {
        val soc = fields.int("pe") ?: return null
        val vv = fields["vv"]
        val sv = fields["sv"]
        // `kn` is the currently stored energy (Wh), not the total capacity. Derive the full capacity
        // from it and the state of charge (kn / soc * 100).
        val remainingWh = fields.int("kn")
        val capacityWh = if (remainingWh != null && soc in 1..100) Math.round(remainingWh * 100.0 / soc).toInt() else null
        return BatteryState(
            id = id,
            name = name,
            timestampMs = timestampMs,
            socPercent = soc,
            capacityWh = capacityWh,
            pvInputW = listOfNotNull(fields.int("w1"), fields.int("w2")),
            outputW = listOfNotNull(fields.int("g1"), fields.int("g2")),
            outputEnabled = listOf(fields.bool("o1"), fields.bool("o2")),
            dodPercent = fields.int("do"),
            chargeMode = when (fields.int("cs")) {
                0 -> ChargeMode.SIMULTANEOUS
                1 -> ChargeMode.CHARGE_THEN_DISCHARGE
                else -> ChargeMode.UNKNOWN
            },
            adaptiveMode = fields.int("md")?.let { it == 1 },
            surplusFeedIn = fields.int("tc_dis")?.let { it == 0 },
            dailyPvWh = fields.int("pt"),
            dailyOutputWh = fields.int("bs"),
            firmware = if (vv != null) "$vv.${sv ?: 0}" else null,
            deviceType = type,
            temperatureC = fields.int("th"),
        )
    }

    fun parseMeter(fields: Map<String, String>, timestampMs: Long): MeterReading? {
        val a = fields.int("pwr_a")
        val b = fields.int("pwr_b")
        val c = fields.int("pwr_c")
        val total = fields.int("pwr_t")
        if (a == null && total == null) return null
        val phases = listOf(a ?: 0, b ?: 0, c ?: 0)
        return MeterReading(timestampMs, phases, total ?: phases.sum())
    }

    /** The command that polls full runtime info. */
    const val POLL_RUNTIME = "cd=01"

    /**
     * Builds the command payload for a setting change.
     * @param noFlash use the no-flash command codes (need newer firmware; fall back to flash on older units).
     */
    fun encode(command: BatteryCommand, noFlash: Boolean = false): String = when (command) {
        is BatteryCommand.SetDod -> "cd=${if (noFlash) 19 else 5},md=${command.percent.coerceIn(0, 100)}"
        is BatteryCommand.SetChargeMode -> {
            val md = when (command.mode) {
                ChargeMode.SIMULTANEOUS -> 0
                ChargeMode.CHARGE_THEN_DISCHARGE -> 1
                ChargeMode.UNKNOWN -> throw IllegalArgumentException("Unbekannter Lademodus")
            }
            "cd=${if (noFlash) 17 else 3},md=$md"
        }
        is BatteryCommand.SetAdaptiveMode ->
            // md=1 = Selbstanpassung/Automodus; md=0 = Manuell, dann läuft der in der Marstek-App
            // gesetzte Zeitplan. Die Manuell-Richtung ist über die Cloud noch nicht verifiziert –
            // am echten Gerät bestätigen, bevor die Kalibrierung sich darauf verlässt.
            "cd=${if (noFlash) 18 else 4},md=${if (command.enabled) 1 else 0}"
    }

    private fun Map<String, String>.int(key: String): Int? = this[key]?.trim()?.toIntOrNull()
    private fun Map<String, String>.bool(key: String): Boolean = this[key]?.trim() == "1"
}
