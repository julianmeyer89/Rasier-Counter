package de.rasier.speicher.model

import kotlinx.serialization.Serializable

/** How a B2500 combines charging and discharging. */
@Serializable
enum class ChargeMode {
    /** PV charges the battery and feeds the outputs at the same time. */
    SIMULTANEOUS,

    /** The battery is charged to 100 % first, discharging only starts afterwards. */
    CHARGE_THEN_DISCHARGE,
    UNKNOWN,
}

/**
 * Live state of one B2500 as reported by the device (Bluetooth) or the cloud.
 * Power values in W, energy in Wh. Fields that the source did not report are null.
 */
@Serializable
data class BatteryState(
    /** Stable identifier: the Bluetooth MAC address (upper case, colon separated). */
    val id: String,
    val name: String,
    val timestampMs: Long,
    val socPercent: Int,
    val capacityWh: Int? = null,
    /** PV power per input (2 entries for the B2500). */
    val pvInputW: List<Int> = emptyList(),
    /** Output power per output (2 entries for the B2500). */
    val outputW: List<Int> = emptyList(),
    val outputEnabled: List<Boolean> = emptyList(),
    /** Depth of discharge in percent: 90 means the battery stops at 10 % SOC. */
    val dodPercent: Int? = null,
    val chargeMode: ChargeMode = ChargeMode.UNKNOWN,
    val adaptiveMode: Boolean? = null,
    val surplusFeedIn: Boolean? = null,
    val dailyPvWh: Int? = null,
    val dailyOutputWh: Int? = null,
    val firmware: String? = null,
    val deviceType: String? = null,
    val temperatureC: Int? = null,
) {
    val totalPvW: Int get() = pvInputW.sum()
    val totalOutputW: Int get() = outputW.sum()

    /** SOC at which the battery stops discharging according to its DoD setting. */
    val floorSocPercent: Int? get() = dodPercent?.let { (100 - it).coerceIn(0, 100) }
}

/** One reading of the grid meter (CT002). Positive power = import from the grid, negative = export. */
@Serializable
data class MeterReading(
    val timestampMs: Long,
    val phaseW: List<Int>,
    val totalW: Int = phaseW.sum(),
)

/** Everything the app knows about the system at one point in time. */
@Serializable
data class SystemSnapshot(
    val timestampMs: Long,
    val batteries: List<BatteryState>,
    val meter: MeterReading?,
)
