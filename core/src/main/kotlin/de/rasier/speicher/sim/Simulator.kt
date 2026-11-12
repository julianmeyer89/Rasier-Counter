package de.rasier.speicher.sim

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.control.BatteryController
import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.ChargeMode
import de.rasier.speicher.model.MeterReading
import de.rasier.speicher.model.SystemSnapshot
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Simulates the user's setup: 2x B2500 (2 PV modules each) feeding one HMS-1600-4T, a house load
 * and a CT002 that regulates the batteries to zero export. Used for the demo mode and for tests.
 * It is a plausible model, not a physical replica of the firmware.
 */
class SimulatedSystem(
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Peak PV power per battery (2 modules), W. */
    private val pvPeakW: Int = 900,
    private val capacityWh: Int = 2240,
    private val baseLoadW: Int = 250,
    private val inverterEfficiency: Double = 0.95,
    initialSoc: List<Double> = listOf(55.0, 40.0),
) : BatteryController {

    private class SimBattery(
        val id: String,
        val name: String,
        var socWh: Double,
        var dod: Int = 90,
        var chargeMode: ChargeMode = ChargeMode.SIMULTANEOUS,
        var adaptive: Boolean = true,
        var fullReached: Boolean = false,
        var pvW: Int = 0,
        var outW: Int = 0,
        var dailyPvWh: Double = 0.0,
        var dailyOutWh: Double = 0.0,
    )

    private val batteries = initialSoc.mapIndexed { i, soc ->
        SimBattery("AA:BB:CC:00:00:0${i + 1}", "Speicher ${i + 1}", soc / 100.0 * capacityWh)
    }

    private var lastMs: Long? = null
    private var gridW = 0
    private var currentDay: String? = null

    val batteryIds: List<String> get() = batteries.map { it.id }

    /** Advances the simulation to [nowMs] and returns the resulting snapshot. */
    @Synchronized
    fun advance(nowMs: Long): SystemSnapshot {
        val prev = lastMs ?: nowMs
        lastMs = nowMs
        val dtH = (nowMs - prev).coerceIn(0, 10 * 60_000L) / 3_600_000.0
        val time = Instant.ofEpochMilli(nowMs).atZone(zone)
        val day = time.toLocalDate().toString()
        if (day != currentDay) {
            currentDay = day
            batteries.forEach { it.dailyPvWh = 0.0; it.dailyOutWh = 0.0 }
        }
        val hour = time.hour + time.minute / 60.0

        // PV: sine between 6:00 and 20:00, slightly different per battery (orientation).
        batteries.forEachIndexed { i, b ->
            val shifted = hour - i * 0.5
            val pv = if (shifted in 6.0..20.0) sin((shifted - 6.0) / 14.0 * PI) * pvPeakW else 0.0
            b.pvW = max(0.0, pv * (0.9 + 0.1 * sin(nowMs / 600_000.0 + i))).roundToInt()
        }
        val load = houseLoad(hour, nowMs)

        // CT002 zero-export: share the house demand between batteries in Auto mode that may discharge.
        // A battery in Manual mode (adaptive off) runs the user's fixed 800 W schedule regardless of load.
        val available = batteries.filter { it.adaptive && canDischarge(it) }
        val demandDc = load / inverterEfficiency
        val perBattery = if (available.isEmpty()) 0.0 else demandDc / available.size
        batteries.forEach { b ->
            val soc = b.socWh / capacityWh * 100
            b.outW = when {
                !b.adaptive -> if (soc <= 100 - b.dod) 0 else 800 // Manual 800 W force-discharge
                b in available -> {
                    val target = min(perBattery, 800.0)
                    // A B2500 cannot go below ~80 W with a Hoymiles inverter selected.
                    if (target < 80) 0 else target.roundToInt()
                }
                else -> 0
            }
        }
        // Surplus PV passes through when the battery is full.
        batteries.forEach { b ->
            if (b.socWh >= capacityWh && b.pvW > b.outW) b.outW = min(b.pvW, 800)
        }

        batteries.forEach { b ->
            val net = (b.pvW - b.outW) * dtH
            b.socWh = (b.socWh + net).coerceIn(0.0, capacityWh.toDouble())
            if (b.socWh >= capacityWh) b.fullReached = true
            b.dailyPvWh += b.pvW * dtH
            b.dailyOutWh += b.outW * dtH
        }
        val acW = batteries.sumOf { it.outW } * inverterEfficiency
        gridW = (load - acW).roundToInt()

        return SystemSnapshot(
            timestampMs = nowMs,
            batteries = batteries.map { it.toState(nowMs) },
            meter = MeterReading(nowMs, splitPhases(gridW)),
        )
    }

    private fun canDischarge(b: SimBattery): Boolean {
        val soc = b.socWh / capacityWh * 100
        if (soc <= 100 - b.dod) return false
        if (b.chargeMode == ChargeMode.CHARGE_THEN_DISCHARGE && !b.fullReached) return false
        return true
    }

    private fun houseLoad(hour: Double, nowMs: Long): Int {
        val morning = if (hour in 6.5..8.5) 600 else 0
        val noon = if (hour in 11.5..13.0) 900 else 0
        val evening = if (hour in 17.5..22.0) 500 else 0
        val noise = (sin(nowMs / 97_000.0) * 60).roundToInt()
        return baseLoadW + morning + noon + evening + noise
    }

    private fun splitPhases(total: Int): List<Int> = listOf(total - 2 * (total / 5), total / 5, total / 5)

    private fun SimBattery.toState(nowMs: Long) = BatteryState(
        id = id,
        name = name,
        timestampMs = nowMs,
        socPercent = (socWh / capacityWh * 100).roundToInt(),
        capacityWh = capacityWh,
        pvInputW = listOf(pvW / 2, pvW - pvW / 2),
        outputW = listOf(outW / 2, outW - outW / 2),
        outputEnabled = listOf(true, true),
        dodPercent = dod,
        chargeMode = chargeMode,
        adaptiveMode = adaptive,
        dailyPvWh = dailyPvWh.roundToInt(),
        dailyOutputWh = dailyOutWh.roundToInt(),
        firmware = "110.9 (Simulation)",
        deviceType = "HMJ-2",
        temperatureC = 24,
    )

    override suspend fun execute(command: BatteryCommand): Boolean = apply(command)

    @Synchronized
    private fun apply(command: BatteryCommand): Boolean {
        val b = batteries.firstOrNull { it.id == command.batteryId } ?: return false
        when (command) {
            is BatteryCommand.SetDod -> b.dod = command.percent.coerceIn(0, 100)
            is BatteryCommand.SetChargeMode -> {
                b.chargeMode = command.mode
                if (command.mode == ChargeMode.CHARGE_THEN_DISCHARGE) b.fullReached = false
            }
            is BatteryCommand.SetAdaptiveMode -> b.adaptive = command.enabled
            is BatteryCommand.Reboot -> {} // simulated restart: no state change
        }
        return true
    }
}
