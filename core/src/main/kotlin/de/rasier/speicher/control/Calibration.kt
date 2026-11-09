package de.rasier.speicher.control

import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.ChargeMode
import de.rasier.speicher.model.SystemSnapshot
import kotlinx.serialization.Serializable

/**
 * Settings for the automatic SOC calibration.
 *
 * Marstek has no command that "calibrates" a B2500. The SOC estimate is corrected by a full cycle:
 * charge to 100 %, then discharge to the lower limit. This routine automates exactly that,
 * one battery at a time so the other one keeps supplying the house.
 */
@Serializable
data class CalibrationSettings(
    /** How long the battery has to stay at 100 % before discharging starts. */
    val holdFullMinutes: Int = 60,
    /** DoD used for the discharge phase (90 = down to 10 %). */
    val dischargeDodPercent: Int = 90,
    /** How long the battery stays at its lower limit before the original settings are restored. */
    val holdEmptyMinutes: Int = 30,
    /** Give up on a battery after this time (weather can delay a full charge for days). */
    val maxHoursPerBattery: Int = 120,
    /** Automatic schedule: run every N days (0 = only manually). */
    val intervalDays: Int = 45,
)

@Serializable
enum class CalibrationPhase { PREPARE, CHARGING, HOLD_FULL, DISCHARGING, HOLD_EMPTY, DONE, ABORTED }

@Serializable
data class SavedBatterySettings(val dodPercent: Int, val chargeMode: ChargeMode)

@Serializable
data class BatteryCalibrationResult(
    val batteryId: String,
    val success: Boolean,
    val message: String,
    val finishedMs: Long,
)

/** State of a running calibration. Serializable so it survives app restarts. */
@Serializable
data class CalibrationRun(
    val queue: List<String>,
    val index: Int = 0,
    val phase: CalibrationPhase = CalibrationPhase.PREPARE,
    val batteryStartedMs: Long,
    val conditionSinceMs: Long? = null,
    val saved: SavedBatterySettings? = null,
    val results: List<BatteryCalibrationResult> = emptyList(),
) {
    val currentBatteryId: String? get() = queue.getOrNull(index)
    val finished: Boolean get() = phase == CalibrationPhase.DONE || phase == CalibrationPhase.ABORTED
}

data class CalibrationStep(val run: CalibrationRun, val commands: List<BatteryCommand>, val events: List<String>)

class CalibrationMachine(private val settings: CalibrationSettings = CalibrationSettings()) {

    fun start(batteryIds: List<String>, nowMs: Long): CalibrationRun {
        require(batteryIds.isNotEmpty()) { "Keine Speicher für die Kalibrierung ausgewählt" }
        return CalibrationRun(queue = batteryIds, batteryStartedMs = nowMs)
    }

    /** Stops the calibration and restores the settings of the battery that is being calibrated. */
    fun abort(run: CalibrationRun, nowMs: Long): CalibrationStep {
        if (run.finished) return CalibrationStep(run, emptyList(), emptyList())
        val id = run.currentBatteryId
        val commands = if (id != null && run.saved != null) restore(id, run.saved) else emptyList()
        val result = id?.let { BatteryCalibrationResult(it, false, "Abgebrochen", nowMs) }
        return CalibrationStep(
            run.copy(phase = CalibrationPhase.ABORTED, results = run.results + listOfNotNull(result)),
            commands,
            listOf("Kalibrierung abgebrochen"),
        )
    }

    fun step(run: CalibrationRun, snapshot: SystemSnapshot, nowMs: Long): CalibrationStep {
        if (run.finished) return CalibrationStep(run, emptyList(), emptyList())
        val id = run.currentBatteryId ?: return CalibrationStep(run.copy(phase = CalibrationPhase.DONE), emptyList(), emptyList())

        if (nowMs - run.batteryStartedMs > settings.maxHoursPerBattery * 3_600_000L) {
            return finishBattery(run, id, false, "Zeitlimit von ${settings.maxHoursPerBattery} h überschritten", nowMs)
        }
        // Without fresh data nothing is changed; the time limit above still applies.
        val battery = snapshot.batteries.firstOrNull { it.id == id } ?: return CalibrationStep(run, emptyList(), emptyList())

        return when (run.phase) {
            CalibrationPhase.PREPARE -> prepare(run, battery)
            CalibrationPhase.CHARGING ->
                if (battery.socPercent >= 100) {
                    CalibrationStep(run.copy(phase = CalibrationPhase.HOLD_FULL, conditionSinceMs = nowMs), emptyList(), listOf("${battery.name}: 100 % erreicht"))
                } else {
                    CalibrationStep(run, emptyList(), emptyList())
                }
            CalibrationPhase.HOLD_FULL -> holdFull(run, battery, nowMs)
            CalibrationPhase.DISCHARGING -> discharging(run, battery, nowMs)
            CalibrationPhase.HOLD_EMPTY -> {
                val since = run.conditionSinceMs ?: nowMs
                if (nowMs - since >= settings.holdEmptyMinutes * 60_000L) {
                    finishBattery(run, id, true, "Vollzyklus abgeschlossen", nowMs)
                } else {
                    CalibrationStep(run, emptyList(), emptyList())
                }
            }
            CalibrationPhase.DONE, CalibrationPhase.ABORTED -> CalibrationStep(run, emptyList(), emptyList())
        }
    }

    private fun prepare(run: CalibrationRun, battery: BatteryState): CalibrationStep {
        val dod = battery.dodPercent ?: return CalibrationStep(run, emptyList(), listOf("${battery.name}: warte auf DoD-Wert"))
        val saved = SavedBatterySettings(dod, battery.chargeMode.takeIf { it != ChargeMode.UNKNOWN } ?: ChargeMode.SIMULTANEOUS)
        return CalibrationStep(
            run.copy(phase = CalibrationPhase.CHARGING, saved = saved, conditionSinceMs = null),
            listOf(BatteryCommand.SetChargeMode(battery.id, ChargeMode.CHARGE_THEN_DISCHARGE)),
            listOf("${battery.name}: lade auf 100 % (Einstellungen gesichert: DoD $dod %, ${saved.chargeMode})"),
        )
    }

    private fun holdFull(run: CalibrationRun, battery: BatteryState, nowMs: Long): CalibrationStep {
        if (battery.socPercent < 99) {
            return CalibrationStep(run.copy(phase = CalibrationPhase.CHARGING, conditionSinceMs = null), emptyList(), listOf("${battery.name}: unter 100 % gefallen, lade weiter"))
        }
        val since = run.conditionSinceMs ?: nowMs
        if (nowMs - since < settings.holdFullMinutes * 60_000L) return CalibrationStep(run, emptyList(), emptyList())
        return CalibrationStep(
            run.copy(phase = CalibrationPhase.DISCHARGING, conditionSinceMs = null),
            listOf(
                BatteryCommand.SetDod(battery.id, settings.dischargeDodPercent),
                BatteryCommand.SetChargeMode(battery.id, ChargeMode.SIMULTANEOUS),
            ),
            listOf("${battery.name}: voll, entlade bis ${100 - settings.dischargeDodPercent} %"),
        )
    }

    private fun discharging(run: CalibrationRun, battery: BatteryState, nowMs: Long): CalibrationStep {
        val floor = 100 - settings.dischargeDodPercent
        val empty = battery.socPercent <= floor + 1 ||
            (battery.socPercent <= floor + 3 && battery.totalOutputW == 0 && battery.totalPvW == 0)
        return if (empty) {
            CalibrationStep(run.copy(phase = CalibrationPhase.HOLD_EMPTY, conditionSinceMs = nowMs), emptyList(), listOf("${battery.name}: Untergrenze erreicht (${battery.socPercent} %)"))
        } else {
            CalibrationStep(run, emptyList(), emptyList())
        }
    }

    private fun finishBattery(run: CalibrationRun, id: String, success: Boolean, message: String, nowMs: Long): CalibrationStep {
        val commands = run.saved?.let { restore(id, it) } ?: emptyList()
        val nextIndex = run.index + 1
        val done = nextIndex >= run.queue.size
        val next = run.copy(
            index = nextIndex,
            phase = if (done) CalibrationPhase.DONE else CalibrationPhase.PREPARE,
            batteryStartedMs = nowMs,
            conditionSinceMs = null,
            saved = null,
            results = run.results + BatteryCalibrationResult(id, success, message, nowMs),
        )
        return CalibrationStep(next, commands, listOf("$id: $message, Einstellungen wiederhergestellt"))
    }

    private fun restore(id: String, saved: SavedBatterySettings) = listOf(
        BatteryCommand.SetDod(id, saved.dodPercent),
        BatteryCommand.SetChargeMode(id, saved.chargeMode),
    )
}
