package de.rasier.speicher.control

import de.rasier.speicher.B1
import de.rasier.speicher.B2
import de.rasier.speicher.HOUR
import de.rasier.speicher.MIN
import de.rasier.speicher.battery
import de.rasier.speicher.model.ChargeMode
import de.rasier.speicher.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationTest {
    private val settings = CalibrationSettings(holdFullMinutes = 30, dischargeDodPercent = 90, holdEmptyMinutes = 10, maxHoursPerBattery = 48)
    private val machine = CalibrationMachine(settings)

    @Test
    fun `full cycle for two batteries restores original settings`() {
        var run = machine.start(listOf(B1, B2), 0)
        val sent = mutableListOf<BatteryCommand>()
        fun step(t: Long, b: de.rasier.speicher.model.BatteryState) {
            val s = machine.step(run, snapshot(t, b), t)
            run = s.run
            sent += s.commands
        }

        step(0, battery(B1, soc = 60, dod = 80, mode = ChargeMode.SIMULTANEOUS))
        assertEquals(CalibrationPhase.CHARGING, run.phase)
        assertEquals(BatteryCommand.SetChargeMode(B1, ChargeMode.CHARGE_THEN_DISCHARGE), sent.last())

        step(HOUR, battery(B1, soc = 100, dod = 80, mode = ChargeMode.CHARGE_THEN_DISCHARGE))
        assertEquals(CalibrationPhase.HOLD_FULL, run.phase)
        step(HOUR + 10 * MIN, battery(B1, soc = 100, dod = 80))
        assertEquals(CalibrationPhase.HOLD_FULL, run.phase)
        step(HOUR + 31 * MIN, battery(B1, soc = 100, dod = 80))
        assertEquals(CalibrationPhase.DISCHARGING, run.phase)
        assertTrue(BatteryCommand.SetDod(B1, 90) in sent)

        step(10 * HOUR, battery(B1, soc = 40, dod = 90, out = 200))
        assertEquals(CalibrationPhase.DISCHARGING, run.phase)
        step(20 * HOUR, battery(B1, soc = 11, dod = 90))
        assertEquals(CalibrationPhase.HOLD_EMPTY, run.phase)
        step(20 * HOUR + 11 * MIN, battery(B1, soc = 10, dod = 90))

        // Battery 1 done, original DoD 80 and SIMULTANEOUS restored, battery 2 is next.
        assertEquals(BatteryCommand.SetDod(B1, 80), sent[sent.size - 2])
        assertEquals(BatteryCommand.SetChargeMode(B1, ChargeMode.SIMULTANEOUS), sent.last())
        assertEquals(B2, run.currentBatteryId)
        assertEquals(CalibrationPhase.PREPARE, run.phase)
        assertTrue(run.results.single().success)
    }

    @Test
    fun `time limit stops a battery and restores settings`() {
        var run = machine.start(listOf(B1), 0)
        run = machine.step(run, snapshot(0, battery(B1, soc = 50, dod = 85)), 0).run
        val s = machine.step(run, snapshot(49 * HOUR, battery(B1, soc = 70, dod = 85)), 49 * HOUR)
        assertEquals(CalibrationPhase.DONE, s.run.phase)
        assertTrue(BatteryCommand.SetDod(B1, 85) in s.commands)
        assertEquals(false, s.run.results.single().success)
    }

    @Test
    fun `abort restores settings`() {
        var run = machine.start(listOf(B1), 0)
        run = machine.step(run, snapshot(0, battery(B1, soc = 50, dod = 85, mode = ChargeMode.SIMULTANEOUS)), 0).run
        val s = machine.abort(run, MIN)
        assertEquals(CalibrationPhase.ABORTED, s.run.phase)
        assertEquals(listOf(BatteryCommand.SetDod(B1, 85), BatteryCommand.SetChargeMode(B1, ChargeMode.SIMULTANEOUS)), s.commands)
    }

    @Test
    fun `missing data does not advance the calibration`() {
        var run = machine.start(listOf(B1), 0)
        run = machine.step(run, snapshot(0, battery(B1, soc = 50)), 0).run
        val s = machine.step(run, snapshot(MIN, battery(B2, soc = 100)), MIN)
        assertEquals(CalibrationPhase.CHARGING, s.run.phase)
        assertTrue(s.commands.isEmpty())
    }
}

class BalancerTest {
    private val balancer = SocBalancer(BalancerSettings(startSpreadPercent = 15, stopSpreadPercent = 5, minWriteIntervalMinutes = 20))

    @Test
    fun `holds the emptier battery while discharging and releases when level`() {
        var state = BalancerState()
        val s1 = balancer.step(state, listOf(battery(B1, soc = 30, out = 200, dod = 90), battery(B2, soc = 60, out = 200)), HOUR, paused = false)
        assertEquals(listOf<BatteryCommand>(BatteryCommand.SetDod(B1, 70)), s1.commands)
        state = s1.state
        assertEquals(B1, state.heldBatteryId)

        val s2 = balancer.step(state, listOf(battery(B1, soc = 30, dod = 70), battery(B2, soc = 45, out = 400)), 2 * HOUR, paused = false)
        assertTrue(s2.commands.isEmpty())

        val s3 = balancer.step(s2.state, listOf(battery(B1, soc = 30, dod = 70), battery(B2, soc = 34, out = 400)), 3 * HOUR, paused = false)
        assertEquals(listOf<BatteryCommand>(BatteryCommand.SetDod(B1, 90)), s3.commands)
        assertNull(s3.state.heldBatteryId)
    }

    @Test
    fun `does nothing while charging or below threshold`() {
        val charging = balancer.step(BalancerState(), listOf(battery(B1, soc = 20, pv = 500, out = 100), battery(B2, soc = 60)), HOUR, false)
        assertTrue(charging.commands.isEmpty())
        val small = balancer.step(BalancerState(), listOf(battery(B1, soc = 50, out = 200), battery(B2, soc = 60, out = 200)), HOUR, false)
        assertTrue(small.commands.isEmpty())
    }

    @Test
    fun `releases when paused`() {
        val held = BalancerState(heldBatteryId = B1, originalDod = 90, lastWriteMs = 0)
        val s = balancer.step(held, listOf(battery(B1, soc = 30, dod = 70), battery(B2, soc = 60)), MIN, paused = true)
        assertEquals(listOf<BatteryCommand>(BatteryCommand.SetDod(B1, 90)), s.commands)
    }

    @Test
    fun `respects minimum write interval`() {
        val s = balancer.step(BalancerState(lastWriteMs = HOUR), listOf(battery(B1, soc = 20, out = 200), battery(B2, soc = 60)), HOUR + 5 * MIN, false)
        assertTrue(s.commands.isEmpty())
    }
}

class MonitorTest {
    private val monitor = Monitor(MonitorSettings(conditionMinutes = 10))

    @Test
    fun `idle batteries with grid import raise CT alert after the delay`() {
        val snap = { t: Long -> snapshot(t, battery(B1, t = t, soc = 80, out = 0), battery(B2, t = t, soc = 70, out = 0), gridW = 400) }
        val seen = mapOf(B1 to 0L, B2 to 0L)
        val first = monitor.step(MonitorState(), snap(0), seen, emptyMap(), 0)
        assertTrue(first.alerts.none { it.key == "ct:idle" })
        val later = monitor.step(first.state, snap(11 * MIN), seen.mapValues { 11 * MIN }, emptyMap(), 11 * MIN)
        assertTrue(later.alerts.any { it.key == "ct:idle" })
    }

    @Test
    fun `stale battery is reported`() {
        val r = monitor.step(MonitorState(), snapshot(HOUR), mapOf(B1 to 0L), mapOf(B1 to "Speicher 1"), HOUR)
        assertEquals("Speicher 1 nicht erreichbar", r.alerts.single().title)
    }
}

class GroupTest {
    @Test
    fun `capacity weighted soc`() {
        val g = GroupState(listOf(battery(B1, soc = 100, capacity = 1000), battery(B2, soc = 0, capacity = 3000)))
        assertEquals(25.0, g.socPercent!!, 1e-9)
        assertEquals(1000.0, g.storedWh!!, 1e-9)
        assertEquals(100, g.socSpread)
    }
}

class AutomationEngineTest {
    @Test
    fun `scheduled calibration starts after the interval`() {
        val engine = AutomationEngine(AutomationSettings(calibration = CalibrationSettings(intervalDays = 30), batteryIds = listOf(B1, B2)))
        var state = AutomationState()
        val snap = snapshot(0, battery(B1), battery(B2))
        state = engine.tick(state, snap, 0).state
        assertNull(state.calibration)
        val r = engine.tick(state, snap, 31 * 24 * HOUR)
        assertEquals(CalibrationPhase.CHARGING, r.state.calibration!!.phase)
        assertTrue(r.commands.any { it is BatteryCommand.SetChargeMode })
    }
}
