package de.rasier.speicher.energy

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A reading of the household electricity meter, typed in by the user.
 * 1.8.0 = total import from the grid, 2.8.0 = total export to the grid (kWh).
 */
@Serializable
data class MeterEntry(
    val timestampMs: Long,
    val importKwh: Double,
    val exportKwh: Double? = null,
)

/**
 * Highest value of a battery's own daily counters seen on a day. The B2500 resets these counters
 * at midnight, so one Bluetooth contact late in the day captures (almost) the whole day.
 */
@Serializable
data class DeviceDay(
    val date: String,
    val batteryId: String,
    val pvWh: Int = 0,
    val outputWh: Int = 0,
    val lastSeenMs: Long = 0,
)

/** Evaluation of the period between two meter readings. Energy in kWh. */
data class MeterPeriod(
    val fromMs: Long,
    val toMs: Long,
    val importKwh: Double,
    val exportKwh: Double?,
    /** Battery output (DC) from the batteries' daily counters; null if no day of the period was captured. */
    val batteryOutputKwh: Double?,
    val pvKwh: Double?,
    /** Days of the period for which battery counters were captured. */
    val batteryDaysCovered: Int,
    val days: Double,
    private val efficiency: Double,
) {
    val inverterKwh: Double? get() = batteryOutputKwh?.let { it * efficiency }

    /** House consumption = import − export + inverter output. */
    val houseKwh: Double? get() = inverterKwh?.let { importKwh - (exportKwh ?: 0.0) + it }

    /** Solar energy used in the house. */
    val selfConsumedKwh: Double? get() = inverterKwh?.let { (it - (exportKwh ?: 0.0)).coerceAtLeast(0.0) }

    val importPerWeekKwh: Double get() = if (days > 0) importKwh / days * 7 else 0.0

    fun savingsEuro(t: Tariff): Double? =
        selfConsumedKwh?.let { it * t.importCentPerKwh / 100 + (exportKwh ?: 0.0) * t.feedInCentPerKwh / 100 }

    fun gridCostEuro(t: Tariff): Double = importKwh * t.importCentPerKwh / 100
}

object MeterEvaluation {
    /** Records the battery daily counters of one reading (keeps the maximum per day and battery). */
    fun updateDeviceDays(
        days: List<DeviceDay>,
        batteryId: String,
        timestampMs: Long,
        pvWh: Int?,
        outputWh: Int?,
        zone: ZoneId,
    ): List<DeviceDay> {
        if (pvWh == null && outputWh == null) return days
        val date = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate().toString()
        val existing = days.firstOrNull { it.date == date && it.batteryId == batteryId }
        val updated = DeviceDay(
            date = date,
            batteryId = batteryId,
            pvWh = maxOf(existing?.pvWh ?: 0, pvWh ?: 0),
            outputWh = maxOf(existing?.outputWh ?: 0, outputWh ?: 0),
            lastSeenMs = timestampMs,
        )
        return days.filterNot { it.date == date && it.batteryId == batteryId } + updated
    }

    /**
     * Builds one period per pair of consecutive meter readings. A day counts towards the period of
     * the reading that follows it; days on which a reading was taken are split by simple ownership
     * (the day of the earlier reading belongs to the earlier period's successor).
     */
    fun periods(
        entries: List<MeterEntry>,
        deviceDays: List<DeviceDay>,
        efficiency: Double,
        zone: ZoneId,
    ): List<MeterPeriod> {
        val sorted = entries.sortedBy { it.timestampMs }
        return sorted.zipWithNext().mapNotNull { (a, b) ->
            val import = b.importKwh - a.importKwh
            if (import < 0) return@mapNotNull null // typo or meter replaced
            val export = if (a.exportKwh != null && b.exportKwh != null) (b.exportKwh - a.exportKwh).takeIf { it >= 0 } else null
            val fromDate = date(a.timestampMs, zone)
            val toDate = date(b.timestampMs, zone)
            // Days fully inside the period: from the day of reading a up to the day before reading b.
            val inPeriod = deviceDays.filter { it.date >= fromDate.toString() && it.date < toDate.toString() }
            val coveredDays = inPeriod.map { it.date }.distinct().size
            MeterPeriod(
                fromMs = a.timestampMs,
                toMs = b.timestampMs,
                importKwh = import,
                exportKwh = export,
                batteryOutputKwh = if (coveredDays > 0) inPeriod.sumOf { it.outputWh } / 1000.0 else null,
                pvKwh = if (coveredDays > 0) inPeriod.sumOf { it.pvWh } / 1000.0 else null,
                batteryDaysCovered = coveredDays,
                days = (b.timestampMs - a.timestampMs) / 86_400_000.0,
                efficiency = efficiency,
            )
        }
    }

    private fun date(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
