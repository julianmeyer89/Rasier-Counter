package de.rasier.speicher.control

import de.rasier.speicher.model.SystemSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class AutomationSettings(
    val calibration: CalibrationSettings = CalibrationSettings(),
    val balancer: BalancerSettings = BalancerSettings(),
    val monitor: MonitorSettings = MonitorSettings(),
    /** Battery ids (Bluetooth MACs) that belong to the group, in display order. */
    val batteryIds: List<String> = emptyList(),
)

/** Everything the automations remember between ticks. Serializable for persistence. */
@Serializable
data class AutomationState(
    val calibration: CalibrationRun? = null,
    val lastCalibrationFinishedMs: Long? = null,
    val balancer: BalancerState = BalancerState(),
    val monitor: MonitorState = MonitorState(),
    val lastSeenMs: Map<String, Long> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
)

data class TickResult(
    val state: AutomationState,
    val commands: List<BatteryCommand>,
    val alerts: List<Alert>,
    val events: List<String>,
)

/**
 * Pure decision logic: takes the latest snapshot and the remembered state, returns the commands
 * to send. It never talks to devices itself, so it runs identically in the app, on a PC and in tests.
 */
class AutomationEngine(private val settings: AutomationSettings) {
    private val calibration = CalibrationMachine(settings.calibration)
    private val balancer = SocBalancer(settings.balancer)
    private val monitor = Monitor(settings.monitor)

    fun startCalibration(state: AutomationState, batteryIds: List<String>, nowMs: Long): TickResult {
        if (state.calibration?.finished == false) return TickResult(state, emptyList(), emptyList(), listOf("Kalibrierung läuft bereits"))
        // A running balancer hold is released first so the calibration starts from the user's settings.
        val release = state.balancer.heldBatteryId?.let { id -> state.balancer.originalDod?.let { listOf(BatteryCommand.SetDod(id, it)) } } ?: emptyList()
        return TickResult(
            state.copy(calibration = calibration.start(batteryIds, nowMs), balancer = BalancerState(lastWriteMs = nowMs)),
            release, emptyList(), listOf("Kalibrierung gestartet"),
        )
    }

    fun abortCalibration(state: AutomationState, nowMs: Long): TickResult {
        val run = state.calibration ?: return TickResult(state, emptyList(), emptyList(), emptyList())
        val step = calibration.abort(run, nowMs)
        return TickResult(state.copy(calibration = step.run, lastCalibrationFinishedMs = nowMs), step.commands, emptyList(), step.events)
    }

    fun tick(state: AutomationState, snapshot: SystemSnapshot, nowMs: Long): TickResult {
        val commands = mutableListOf<BatteryCommand>()
        val events = mutableListOf<String>()
        var s = state.copy(
            lastSeenMs = state.lastSeenMs + snapshot.batteries.associate { it.id to it.timestampMs },
            names = state.names + snapshot.batteries.associate { it.id to it.name },
        )

        // Scheduled calibration.
        val interval = settings.calibration.intervalDays
        val calibrationIdle = s.calibration == null || s.calibration!!.finished
        if (interval > 0 && calibrationIdle && settings.batteryIds.isNotEmpty()) {
            val last = s.lastCalibrationFinishedMs
            if (last != null && nowMs - last >= interval * 86_400_000L) {
                val started = startCalibration(s, settings.batteryIds, nowMs)
                s = started.state
                commands += started.commands
                events += "Geplante Kalibrierung (alle $interval Tage)"
            } else if (last == null) {
                // First run: start counting from now instead of calibrating immediately.
                s = s.copy(lastCalibrationFinishedMs = nowMs)
            }
        }

        val run = s.calibration
        if (run != null && !run.finished) {
            val step = calibration.step(run, snapshot, nowMs)
            s = s.copy(calibration = step.run)
            if (step.run.finished) s = s.copy(lastCalibrationFinishedMs = nowMs)
            commands += step.commands
            events += step.events
        }

        val calibrating = s.calibration?.finished == false
        val bal = balancer.step(s.balancer, snapshot.batteries, nowMs, paused = calibrating)
        s = s.copy(balancer = bal.state)
        commands += bal.commands
        events += bal.events

        val mon = monitor.step(s.monitor, snapshot, s.lastSeenMs, s.names, nowMs)
        s = s.copy(monitor = mon.state)

        return TickResult(s, commands, mon.alerts, events)
    }
}
