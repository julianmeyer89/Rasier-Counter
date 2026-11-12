package de.rasier.speicher.server

import de.rasier.speicher.control.Alert
import de.rasier.speicher.control.CalibrationPhase
import de.rasier.speicher.energy.DailyEnergy
import de.rasier.speicher.energy.Tariff
import de.rasier.speicher.settings.HistoryPoint
import kotlinx.serialization.Serializable

/** Everything the dashboard shows at a glance. All power in W, energy in Wh, money in EUR. */
@Serializable
data class StateDto(
    val timestampMs: Long,
    val mode: String,
    val automationsMayWrite: Boolean,
    val link: LinkDto,
    val group: GroupDto,
    val meter: MeterDto?,
    val batteries: List<BatteryDto>,
    val today: EnergyDto?,
    val alerts: List<AlertDto>,
    val calibration: CalibrationDto?,
    val suggestions: List<CommandDto>,
    /** Timestamp of the most recent meter reading, for the "since last reading" shortcut. */
    val lastMeterReadingMs: Long? = null,
    /** Share of recent CT002 polls that returned fresh data (0–100), as a connection-quality gauge. */
    val meterFreshPercent: Int? = null,
)

@Serializable
data class LinkDto(val connected: Boolean, val detail: String, val lastDataMs: Long?)

@Serializable
data class GroupDto(
    val socPercent: Double?,
    val capacityWh: Int?,
    val storedWh: Double?,
    val pvW: Int,
    val outputW: Int,
    val inverterAcW: Int,
    val chargeW: Int,
    val gridW: Int?,
    val houseW: Int?,
    val socSpread: Int,
    val heldBatteryId: String?,
)

@Serializable
data class MeterDto(val phaseW: List<Int>, val totalW: Int)

@Serializable
data class BatteryDto(
    val id: String,
    val name: String,
    val socPercent: Int,
    val capacityWh: Int?,
    val pvInputW: List<Int>,
    val outputW: List<Int>,
    val outputEnabled: List<Boolean>,
    val dodPercent: Int?,
    val chargeMode: String,
    val adaptiveMode: Boolean?,
    val dailyPvWh: Int?,
    /** Measured usable capacity from clean discharges (Wh), its deviation from the device's claim (%),
     *  and whether that deviation suggests a calibration. */
    val measuredCapacityWh: Int? = null,
    val capacityDeviationPercent: Int? = null,
    val calibrationRecommended: Boolean = false,
    /** Device daily output counter `bs` (includes self-discharge) — kept for reference. */
    val dailyOutputWh: Int?,
    /** Real energy delivered to the inverter today (integrated live output). */
    val deliveredTodayWh: Int? = null,
    /** Energy lost to self-discharge today, and the recent idle self-discharge power (W). */
    val selfDischargeTodayWh: Int? = null,
    val selfDischargeW: Int? = null,
    /** True when the recent idle self-discharge is unusually high. */
    val selfDischargeHigh: Boolean = false,
    val temperatureC: Int?,
    val firmware: String?,
    val ageSeconds: Long,
)

@Serializable
data class EnergyDto(
    val date: String,
    val pvWh: Double,
    val houseWh: Double,
    val selfConsumedWh: Double,
    val gridImportWh: Double,
    val gridExportWh: Double,
    val batteryOutputWh: Double,
    val autarkyPercent: Double?,
    val savingsEuro: Double,
    val gridCostEuro: Double,
) {
    companion object {
        fun from(d: DailyEnergy, t: Tariff) = EnergyDto(
            date = d.date, pvWh = d.pvWh, houseWh = d.houseWh, selfConsumedWh = d.selfConsumedWh,
            gridImportWh = d.gridImportWh, gridExportWh = d.gridExportWh, batteryOutputWh = d.batteryOutputWh,
            autarkyPercent = d.autarky?.times(100), savingsEuro = d.savingsEuro(t), gridCostEuro = d.gridCostEuro(t),
        )
    }
}

@Serializable
data class AlertDto(val key: String, val severity: String, val title: String, val message: String, val timestampMs: Long) {
    companion object {
        fun from(a: Alert) = AlertDto(a.key, a.severity.name, a.title, a.message, a.timestampMs)
    }
}

@Serializable
data class CalibrationDto(
    val running: Boolean,
    val currentBatteryId: String?,
    val phase: String,
    val index: Int,
    val total: Int,
    val startedMs: Long,
    val lastFinishedMs: Long?,
    val results: List<CalibrationResultDto>,
)

@Serializable
data class CalibrationResultDto(val batteryId: String, val success: Boolean, val message: String, val finishedMs: Long)

@Serializable
data class CommandDto(val batteryId: String, val type: String, val value: Int)

@Serializable
data class HistoryDto(val points: List<HistoryPoint>)

@Serializable
data class StatsDto(
    val label: String,
    val fromMs: Long,
    val toMs: Long,
    val bucket: String,
    val total: EnergyDto,
    val buckets: List<BucketDto>,
    val meterPeriods: List<MeterPeriodDto>,
    /** Meter-reading-based total for the exact window, if readings bracket it (exact grid values). */
    val meterTotal: MeterPeriodDto?,
)

@Serializable
data class BucketDto(val label: String, val energy: EnergyDto)

@Serializable
data class MeterPeriodDto(
    val fromMs: Long,
    val toMs: Long,
    val importKwh: Double,
    val exportKwh: Double?,
    val houseKwh: Double?,
    val selfConsumedKwh: Double?,
    val savingsEuro: Double?,
    val importPerWeekKwh: Double,
)

@Serializable
data class SettingsDto(
    val importCentPerKwh: Double,
    val feedInCentPerKwh: Double,
    val baseFeeEuroPerMonth: Double,
    val inverterEfficiencyPercent: Double,
    val inverterMaxW: Int,
    // Defaults let settings saved before the split (single pollIntervalSeconds) still load.
    val ct002PollIntervalSeconds: Double = 2.0,
    val batteryPollIntervalSeconds: Int = 5,
    val selfDischargeAlertW: Int = 25,
    val calibrationDeviationPercent: Int = 15,
    val automationsMayWrite: Boolean,
    val balancerEnabled: Boolean,
    val calibrationIntervalDays: Int,
)

/**
 * Per-battery statistics. Value lists are aligned to [labels]. [unit] says what the chart values are:
 * "W" (average power per 5-minute slot, day view) or "Wh" (energy per day/month bucket). [totals] is
 * always energy in Wh (for the sum box). [soc] is in %, -1 where no data (day view only).
 */
@Serializable
data class BatteryStatsDto(val bucket: String, val unit: String, val labels: List<String>, val series: List<BatterySeriesDto>)

@Serializable
data class BatterySeriesDto(
    val id: String,
    val name: String,
    val pv: List<Int>,
    val output: List<Int>,
    val charge: List<Int>,
    val selfDischarge: List<Int>,
    val soc: List<Int> = emptyList(),
    val totals: BatteryTotals = BatteryTotals(),
)

/** Window energy totals per metric, in Wh, for the sum box. */
@Serializable
data class BatteryTotals(val pv: Int = 0, val output: Int = 0, val charge: Int = 0, val selfDischarge: Int = 0)

/**
 * One measured-capacity sample from a clean (PV-free) discharge run: energy that left the cells
 * divided by the SOC drop. Compared against the device's claimed capacity to flag SOC drift.
 */
@Serializable
data class CapacitySample(
    val batteryId: String,
    val timestampMs: Long,
    val measuredCapacityWh: Int,
    val dropPercent: Int,
    val deviceCapacityWh: Int,
)

@Serializable
data class EventDto(val t: Long, val text: String)

@Serializable
data class CommandRequest(val batteryId: String, val type: String, val value: Int)

@Serializable
data class CalibrationRequest(val batteryIds: List<String> = emptyList())

@Serializable
data class MeterRequest(val importKwh: Double, val exportKwh: Double? = null, val dateMs: Long? = null)

fun phaseLabel(p: CalibrationPhase): String = when (p) {
    CalibrationPhase.PREPARE -> "Vorbereitung"
    CalibrationPhase.CHARGING -> "Laden auf 100 %"
    CalibrationPhase.HOLD_FULL -> "100 % halten"
    CalibrationPhase.DISCHARGING -> "Entladen"
    CalibrationPhase.HOLD_EMPTY -> "Untergrenze halten"
    CalibrationPhase.DONE -> "abgeschlossen"
    CalibrationPhase.ABORTED -> "abgebrochen"
}
