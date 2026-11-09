package de.rasier.speicher.sim

import de.rasier.speicher.MIN
import de.rasier.speicher.control.AutomationEngine
import de.rasier.speicher.control.AutomationSettings
import de.rasier.speicher.control.AutomationState
import de.rasier.speicher.control.CalibrationPhase
import de.rasier.speicher.control.CalibrationSettings
import de.rasier.speicher.energy.EnergyIntegrator
import de.rasier.speicher.energy.SystemConfig
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end: simulator + automation engine + statistics over several simulated days. */
class SimulatorTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val start = LocalDate.of(2026, 6, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `a summer day produces plausible statistics`() {
        val sim = SimulatedSystem(zone)
        val integrator = EnergyIntegrator(SystemConfig(), zone)
        var t = start
        while (t <= start + 24 * 60 * MIN) {
            integrator.add(sim.advance(t))
            t += MIN
        }
        val day = integrator.totals.getValue("2026-06-01")
        assertTrue(day.pvWh > 5000, "PV ${day.pvWh}")
        assertTrue(day.houseWh > 5000, "Haus ${day.houseWh}")
        assertTrue(day.selfConsumedWh > 0)
        assertTrue(day.autarky!! in 0.3..1.0, "Autarkie ${day.autarky}")
    }

    @Test
    fun `calibration completes in the simulator`() = runTest {
        val sim = SimulatedSystem(zone, initialSoc = listOf(60.0, 60.0))
        val engine = AutomationEngine(
            AutomationSettings(
                calibration = CalibrationSettings(holdFullMinutes = 30, holdEmptyMinutes = 10, intervalDays = 0, maxHoursPerBattery = 96),
                batteryIds = sim.batteryIds,
            ),
        )
        var state = AutomationState()
        state = engine.startCalibration(state, sim.batteryIds, start).state
        var t = start
        val end = start + 8 * 24 * 60 * MIN
        while (t < end && state.calibration?.finished != true) {
            val result = engine.tick(state, sim.advance(t), t)
            result.commands.forEach { sim.execute(it) }
            state = result.state
            t += 5 * MIN
        }
        val run = state.calibration!!
        assertEquals(CalibrationPhase.DONE, run.phase)
        assertEquals(2, run.results.size)
        assertTrue(run.results.all { it.success }, run.results.toString())
    }
}
