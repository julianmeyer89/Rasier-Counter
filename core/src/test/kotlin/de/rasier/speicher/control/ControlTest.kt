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
    private val zone = java.time.ZoneId.of("UTC")
    private val settings = CalibrationSettings(
        holdMinutes = 0, dischargeDodPercent = 100, chargeToleranceSocPercent = 98,
        dischargeStartMinuteOfDay = 120, sunsetPvW = 10, sunsetSustainMinutes = 0,
        recoveryAttempts = 1, maxHoursPerBattery = 48,
    )
    private val machine = CalibrationMachine(settings, zone)
    private fun ms(iso: String) = java.time.Instant.parse(iso).toEpochMilli()

    @Test
    fun `full cycle charges, discharges in the evening, recharges and verifies`() {
        var run = machine.start(listOf(B1), ms("2024-06-01T12:00:00Z"))
        val sent = mutableListOf<BatteryCommand>()
        fun step(iso: String, soc: Int, pv: Int = 0, out: Int = 0, grid: Int? = null) {
            val t = ms(iso)
            val s = machine.step(run, snapshot(t, battery(B1, t = t, soc = soc, pv = pv, out = out, dod = 80), gridW = grid), t)
            run = s.run; sent += s.commands
        }
        step("2024-06-01T12:00:00Z", soc = 60)
        assertEquals(CalibrationPhase.CHARGING, run.phase)
        assertTrue(BatteryCommand.SetChargeMode(B1, ChargeMode.CHARGE_THEN_DISCHARGE) in sent)
        assertTrue(BatteryCommand.SetDod(B1, 0) in sent)                 // DoD 0 while charging

        step("2024-06-01T12:05:00Z", soc = 100); assertEquals(CalibrationPhase.HOLD_FULL, run.phase)
        step("2024-06-01T12:06:00Z", soc = 100); assertEquals(CalibrationPhase.WAIT_SUNSET, run.phase)
        step("2024-06-01T14:00:00Z", soc = 100, pv = 500); assertEquals(CalibrationPhase.WAIT_SUNSET, run.phase) // PV on -> wait
        step("2024-06-01T20:00:00Z", soc = 99, pv = 0)                    // sunset, tolerated 99%
        assertEquals(CalibrationPhase.EVENING_DISCHARGE, run.phase)
        assertTrue(BatteryCommand.SetDod(B1, 100) in sent)               // full discharge, Auto

        step("2024-06-01T20:05:00Z", soc = 95, pv = 0, out = 300); assertEquals(CalibrationPhase.EVENING_DISCHARGE, run.phase)
        step("2024-06-02T02:00:00Z", soc = 40, pv = 0, out = 200)        // 02:00 -> manual
        assertEquals(CalibrationPhase.MANUAL_EMPTY, run.phase)
        assertTrue(BatteryCommand.SetAdaptiveMode(B1, false) in sent)     // switch to manual

        step("2024-06-02T02:30:00Z", soc = 1, out = 100); assertEquals(CalibrationPhase.HOLD_EMPTY, run.phase)
        step("2024-06-02T02:31:00Z", soc = 1)                            // hold 0 -> recharge
        assertEquals(CalibrationPhase.RECHARGE, run.phase)
        assertTrue(BatteryCommand.SetAdaptiveMode(B1, true) in sent)      // back to auto for recharge

        step("2024-06-02T09:00:00Z", soc = 100, pv = 500); assertEquals(CalibrationPhase.HOLD_FULL2, run.phase)
        step("2024-06-02T09:01:00Z", soc = 100)                          // hold 0 -> release + verify
        assertEquals(CalibrationPhase.VERIFY, run.phase)
        assertTrue(BatteryCommand.SetDod(B1, 80) in sent)                // original DoD restored
        assertTrue(BatteryCommand.SetChargeMode(B1, ChargeMode.SIMULTANEOUS) in sent)

        step("2024-06-02T09:05:00Z", soc = 99, out = 250)               // delivering -> zero-export works
        assertEquals(CalibrationPhase.DONE, run.phase)
        assertTrue(run.results.single().success)
    }

    @Test
    fun `verify failure triggers reboot recovery and finally stops`() {
        var run = machine.start(listOf(B1), 0)
        val sent = mutableListOf<BatteryCommand>()
        // Jump straight into VERIFY by hand (the full path is covered above).
        run = run.copy(phase = CalibrationPhase.VERIFY, phaseSinceMs = 0, saved = SavedBatterySettings(80, ChargeMode.SIMULTANEOUS, true))
        fun step(t: Long, out: Int, grid: Int?, batAge: Long = 0) {
            val s = machine.step(run, snapshot(t, battery(B1, t = t - batAge, soc = 99, out = out, dod = 80), gridW = grid), t)
            run = s.run; sent += s.commands
        }
        // Load present, battery not delivering -> failure must be sustained first.
        step(0, out = 0, grid = 200); assertEquals(CalibrationPhase.VERIFY, run.phase)
        step(CalibrationMachine.VERIFY_FAIL_SUSTAIN_MS + MIN, out = 0, grid = 200)
        assertEquals(CalibrationPhase.RECOVER_MANUAL, run.phase)
        assertEquals(BatteryCommand.SetAdaptiveMode(B1, false), sent.last())

        val t0 = CalibrationMachine.VERIFY_FAIL_SUSTAIN_MS + MIN
        step(t0 + CalibrationMachine.RECOVER_MANUAL_WAIT_MS + 1000, out = 0, grid = 200)
        assertEquals(CalibrationPhase.RECOVER_WAIT, run.phase)
        assertTrue(sent.last() is BatteryCommand.Reboot)                 // remote restart

        // The battery only counts as back once a frame from AFTER the restart arrives: an old cached
        // frame must not start the settle timer, or "back to Auto" is sent while it is still booting.
        val t1 = t0 + CalibrationMachine.RECOVER_MANUAL_WAIT_MS + 1000
        step(t1 + 10_000, out = 0, grid = 200, batAge = 10_000)          // only the pre-restart frame
        assertEquals(CalibrationPhase.RECOVER_WAIT, run.phase)
        step(t1 + 40_000, out = 0, grid = 200, batAge = 40_000)          // still the same old frame
        assertEquals(CalibrationPhase.RECOVER_WAIT, run.phase)
        step(t1 + 50_000, out = 0, grid = 200)                           // fresh frame: now settling
        assertEquals(CalibrationPhase.RECOVER_WAIT, run.phase)
        step(t1 + 50_000 + CalibrationMachine.RECOVER_SETTLE_MS - 5_000, out = 0, grid = 200)
        assertEquals(CalibrationPhase.RECOVER_WAIT, run.phase)
        step(t1 + 50_000 + CalibrationMachine.RECOVER_SETTLE_MS + 1_000, out = 0, grid = 200)
        assertEquals(CalibrationPhase.VERIFY, run.phase)
        assertEquals(BatteryCommand.SetAdaptiveMode(B1, true), sent.last())
        assertEquals(1, run.recoveryAttempt)

        // Still failing and attempts exhausted (1) -> calibration stops.
        val t2 = t1 + 50_000 + CalibrationMachine.RECOVER_SETTLE_MS + 1_000
        step(t2, out = 0, grid = 200)
        step(t2 + CalibrationMachine.VERIFY_FAIL_SUSTAIN_MS + MIN, out = 0, grid = 200)
        assertEquals(CalibrationPhase.ABORTED, run.phase)
        assertEquals(false, run.results.single().success)
    }

    @Test
    fun `a battery that never returns after the restart stops the calibration and restores`() {
        val saved = SavedBatterySettings(80, ChargeMode.SIMULTANEOUS, true)
        val run = machine.start(listOf(B1), 0).copy(phase = CalibrationPhase.RECOVER_WAIT, phaseSinceMs = 0, saved = saved)
        // While it reboots the battery drops out of the snapshot entirely: that must not stall the run.
        val waiting = machine.step(run, snapshot(MIN, gridW = 100), MIN)
        assertEquals(CalibrationPhase.RECOVER_WAIT, waiting.run.phase)
        val t = CalibrationMachine.RECOVER_REACHABLE_CAP_MS + MIN
        val s = machine.step(run, snapshot(t, gridW = 100), t)
        assertEquals(CalibrationPhase.ABORTED, s.run.phase)
        assertTrue(BatteryCommand.SetDod(B1, 80) in s.commands)
        assertEquals(false, s.run.results.single().success)
    }

    @Test
    fun `verify does not accept a battery that is still in manual mode`() {
        val saved = SavedBatterySettings(80, ChargeMode.SIMULTANEOUS, true)
        val run = machine.start(listOf(B1), 0).copy(phase = CalibrationPhase.VERIFY, phaseSinceMs = 0, saved = saved)
        // 800 W out passes the plain output check, but the release never arrived (still manual mode).
        val manual = battery(B1, t = 5000, soc = 99, out = 800, dod = 80).copy(adaptiveMode = false)
        val waiting = machine.step(run, snapshot(5000, manual, gridW = -650), 5000)
        assertEquals(CalibrationPhase.VERIFY, waiting.run.phase)
        assertTrue(waiting.run.results.isEmpty())
        // After the grace period it stops and restores instead of reporting success.
        val t = CalibrationMachine.RELEASE_CONFIRM_MS + MIN
        val s = machine.step(run, snapshot(t, manual.copy(timestampMs = t), gridW = -650), t)
        assertEquals(CalibrationPhase.ABORTED, s.run.phase)
        assertEquals(false, s.run.results.single().success)
        assertTrue(BatteryCommand.SetAdaptiveMode(B1, true) in s.commands)
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
        assertTrue(BatteryCommand.SetDod(B1, 85) in s.commands)
        assertTrue(BatteryCommand.SetChargeMode(B1, ChargeMode.SIMULTANEOUS) in s.commands)
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
