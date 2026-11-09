package de.rasier.speicher.energy

import de.rasier.speicher.model.SystemSnapshot
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Electricity tariff, editable in the app. Prices in cent per kWh, base fee in euro per month. */
@Serializable
data class Tariff(
    val importCentPerKwh: Double = 30.0,
    val feedInCentPerKwh: Double = 0.0,
    val baseFeeEuroPerMonth: Double = 0.0,
)

/** System parameters that cannot be measured without extra hardware. */
@Serializable
data class SystemConfig(
    /** Share of the batteries' DC output that arrives as AC (HMS-1600-4T: about 95 %). */
    val inverterEfficiency: Double = 0.95,
    /** Maximum AC output of the inverter in W. */
    val inverterMaxW: Int = 1600,
    /** Intervals longer than this are not integrated (no data = no guessing). */
    val maxGapMs: Long = 5 * 60_000L,
)

/**
 * Energy totals for one day. All values in Wh.
 *
 * Without a DTU the inverter output is estimated from the battery outputs times the efficiency.
 * Grid values only cover the time in which meter readings were available ([meterCoverageMs]).
 */
@Serializable
data class DailyEnergy(
    val date: String,
    val gridImportWh: Double = 0.0,
    val gridExportWh: Double = 0.0,
    val pvWh: Double = 0.0,
    val batteryOutputWh: Double = 0.0,
    val inverterAcWh: Double = 0.0,
    /** House consumption, only for intervals where meter and battery data were both available. */
    val houseWh: Double = 0.0,
    /** Solar energy used in the house instead of being bought. */
    val selfConsumedWh: Double = 0.0,
    val meterCoverageMs: Long = 0,
    val batteryCoverageMs: Long = 0,
) {
    /** Savings in euro: self-consumed energy at the import price plus feed-in compensation. */
    fun savingsEuro(t: Tariff): Double =
        selfConsumedWh / 1000.0 * t.importCentPerKwh / 100.0 + gridExportWh / 1000.0 * t.feedInCentPerKwh / 100.0

    /** Cost of the energy bought from the grid (without base fee). */
    fun gridCostEuro(t: Tariff): Double = gridImportWh / 1000.0 * t.importCentPerKwh / 100.0

    /** Share of house consumption covered by the solar system, 0..1, or null without data. */
    val autarky: Double? get() = if (houseWh > 0) (selfConsumedWh / houseWh).coerceIn(0.0, 1.0) else null

    operator fun plus(o: DailyEnergy) = DailyEnergy(
        date = date,
        gridImportWh = gridImportWh + o.gridImportWh,
        gridExportWh = gridExportWh + o.gridExportWh,
        pvWh = pvWh + o.pvWh,
        batteryOutputWh = batteryOutputWh + o.batteryOutputWh,
        inverterAcWh = inverterAcWh + o.inverterAcWh,
        houseWh = houseWh + o.houseWh,
        selfConsumedWh = selfConsumedWh + o.selfConsumedWh,
        meterCoverageMs = meterCoverageMs + o.meterCoverageMs,
        batteryCoverageMs = batteryCoverageMs + o.batteryCoverageMs,
    )
}

/**
 * Integrates power snapshots into daily energy totals (trapezoidal rule).
 *
 * The CT002 has no energy counters, so import/export must be computed from power readings.
 * Intervals are split at midnight (local time) so each day gets exactly its share.
 */
class EnergyIntegrator(
    private val config: SystemConfig = SystemConfig(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private var last: SystemSnapshot? = null
    private val days = linkedMapOf<String, DailyEnergy>()

    val totals: Map<String, DailyEnergy> get() = days

    fun restore(saved: Collection<DailyEnergy>) {
        saved.forEach { days[it.date] = it }
    }

    fun add(snapshot: SystemSnapshot) {
        val prev = last
        last = snapshot
        if (prev == null) return
        val dt = snapshot.timestampMs - prev.timestampMs
        if (dt <= 0 || dt > config.maxGapMs) return

        // Split the interval at local midnight.
        var start = prev.timestampMs
        while (start < snapshot.timestampMs) {
            val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(nextMidnight, snapshot.timestampMs)
            integrate(day, prev, snapshot, end - start)
            start = end
        }
    }

    private fun integrate(day: LocalDate, a: SystemSnapshot, b: SystemSnapshot, ms: Long) {
        val hours = ms / 3_600_000.0
        val key = day.toString()
        var e = DailyEnergy(date = key)

        val batteriesA = a.batteries.associateBy { it.id }
        val pairs = b.batteries.mapNotNull { nb -> batteriesA[nb.id]?.let { it to nb } }
        val hasBatteries = pairs.isNotEmpty()
        val pvW = pairs.sumOf { (x, y) -> (x.totalPvW + y.totalPvW) / 2.0 }
        val outW = pairs.sumOf { (x, y) -> (x.totalOutputW + y.totalOutputW) / 2.0 }
        val acW = (outW * config.inverterEfficiency).coerceAtMost(config.inverterMaxW.toDouble())
        if (hasBatteries) {
            e = e.copy(
                pvWh = pvW * hours,
                batteryOutputWh = outW * hours,
                inverterAcWh = acW * hours,
                batteryCoverageMs = ms,
            )
        }

        val ma = a.meter
        val mb = b.meter
        if (ma != null && mb != null) {
            // Split import/export per sample so mixed intervals are not netted out.
            val importW = (maxOf(ma.totalW, 0) + maxOf(mb.totalW, 0)) / 2.0
            val exportW = (maxOf(-ma.totalW, 0) + maxOf(-mb.totalW, 0)) / 2.0
            e = e.copy(
                gridImportWh = importW * hours,
                gridExportWh = exportW * hours,
                meterCoverageMs = ms,
            )
            if (hasBatteries) {
                val houseW = (importW - exportW + acW).coerceAtLeast(0.0)
                val selfW = (acW - exportW).coerceAtLeast(0.0)
                e = e.copy(houseWh = houseW * hours, selfConsumedWh = selfW * hours)
            }
        }
        days[key] = (days[key] ?: DailyEnergy(date = key)) + e
    }
}

/** Sums days into a period (week, month, year). */
fun List<DailyEnergy>.sum(label: String): DailyEnergy =
    fold(DailyEnergy(date = label)) { acc, d -> acc + d }.copy(date = label)
