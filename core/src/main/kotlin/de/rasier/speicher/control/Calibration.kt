package de.rasier.speicher.control

import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.ChargeMode
import de.rasier.speicher.model.SystemSnapshot
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * Settings for the automatic SOC calibration.
 *
 * Marstek has no "calibrate" command. The SOC estimate is re-anchored by a full cycle that lets the
 * BMS see the whole range: charge to 100 % and hold, discharge to ~0 % and hold, charge to 100 % and
 * hold again, then release to normal operation. One battery at a time so the other supplies the house.
 * The evening portion is discharged normally (Auto) so the energy is used by the house; only the rest
 * is force-emptied in Manual mode (the 800 W schedule set in the Marstek app) at [dischargeStartMinuteOfDay].
 */
@Serializable
data class CalibrationSettings(
    /** Hold time at 100 % (both times) and at the lower limit, in minutes. */
    val holdMinutes: Int = 30,
    /** DoD used for the deep discharge (100 = down to ~0 %). */
    val dischargeDodPercent: Int = 100,
    /** After the 100 % hold, tolerate self-discharge drift down to this SOC while waiting for sunset. */
    val chargeToleranceSocPercent: Int = 98,
    /** Local time of day (minutes since midnight) at which the manual force-empty starts. 120 = 02:00. */
    val dischargeStartMinuteOfDay: Int = 120,
    /** PV power at/below which "no PV" is assumed, sustained [sunsetSustainMinutes]. */
    val sunsetPvW: Int = 10,
    val sunsetSustainMinutes: Int = 20,
    /** How often to retry the CT002 recovery (manual -> reboot -> auto) before stopping. */
    val recoveryAttempts: Int = 3,
    /** Give up on a battery after this time (weather can delay a full charge for days). */
    val maxHoursPerBattery: Int = 120,
    /** Automatic schedule: run every N days (0 = only manually). */
    val intervalDays: Int = 45,
)

@Serializable
enum class CalibrationPhase {
    PREPARE, CHARGING, HOLD_FULL, WAIT_SUNSET, EVENING_DISCHARGE, MANUAL_EMPTY, HOLD_EMPTY,
    RECHARGE, HOLD_FULL2, VERIFY, RECOVER_MANUAL, RECOVER_WAIT, DONE, ABORTED,
}

/** The battery settings as they were before the calibration, restored when it ends.
 *  All fields have defaults so a file written by an older version stays readable. */
@Serializable
data class SavedBatterySettings(
    val dodPercent: Int = 90,
    val chargeMode: ChargeMode = ChargeMode.SIMULTANEOUS,
    val adaptive: Boolean? = null,
)

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
    /** When the current phase was entered (for holds/timeouts). */
    val phaseSinceMs: Long = 0,
    /** Start of a sub-condition inside a phase (PV-low sustain, failure sustain). */
    val conditionSinceMs: Long? = null,
    val saved: SavedBatterySettings? = null,
    val recoveryAttempt: Int = 0,
    val results: List<BatteryCalibrationResult> = emptyList(),
) {
    val currentBatteryId: String? get() = queue.getOrNull(index)
    val finished: Boolean get() = phase == CalibrationPhase.DONE || phase == CalibrationPhase.ABORTED
}

data class CalibrationStep(val run: CalibrationRun, val commands: List<BatteryCommand>, val events: List<String>)

class CalibrationMachine(
    private val settings: CalibrationSettings = CalibrationSettings(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val holdMs get() = settings.holdMinutes * 60_000L

    fun start(batteryIds: List<String>, nowMs: Long): CalibrationRun {
        require(batteryIds.isNotEmpty()) { "Keine Speicher für die Kalibrierung ausgewählt" }
        return CalibrationRun(queue = batteryIds, batteryStartedMs = nowMs, phaseSinceMs = nowMs)
    }

    /** Stops the calibration and restores the settings of the battery being calibrated. */
    fun abort(run: CalibrationRun, nowMs: Long): CalibrationStep {
        if (run.finished) return CalibrationStep(run, emptyList(), emptyList())
        val id = run.currentBatteryId
        val commands = if (id != null && run.saved != null) restoreCmds(id, run.saved) else emptyList()
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
            val cmds = run.saved?.let { restoreCmds(id, it) } ?: emptyList()
            return advance(run, id, false, "Zeitlimit von ${settings.maxHoursPerBattery} h überschritten", nowMs, cmds)
        }
        // While the battery reboots it drops out of the snapshot. That is expected and must not stall
        // the run, so the restart phase keeps going without battery data; every other phase waits.
        val b = snapshot.batteries.firstOrNull { it.id == id }
        if (b == null) {
            return if (run.phase == CalibrationPhase.RECOVER_WAIT) recoverWait(run, id, null, nowMs)
            else CalibrationStep(run, emptyList(), emptyList())
        }
        val name = b.name

        return when (run.phase) {
            CalibrationPhase.PREPARE -> prepare(run, b, nowMs)

            CalibrationPhase.CHARGING ->
                if (b.socPercent >= 100) go(run, CalibrationPhase.HOLD_FULL, nowMs, ev = "$name: 100 % erreicht, halte")
                else none(run)

            CalibrationPhase.HOLD_FULL -> {
                if (b.socPercent < 99) go(run, CalibrationPhase.CHARGING, nowMs, ev = "$name: unter 100 % – lädt weiter")
                else if (nowMs - run.phaseSinceMs < holdMs) none(run)
                else go(run, CalibrationPhase.WAIT_SUNSET, nowMs, ev = "$name: ${settings.holdMinutes} min bei 100 % gehalten, warte auf Abend")
            }

            CalibrationPhase.WAIT_SUNSET -> {
                if (b.socPercent < settings.chargeToleranceSocPercent)
                    go(run, CalibrationPhase.CHARGING, nowMs, ev = "$name: SOC unter ${settings.chargeToleranceSocPercent} % – lädt nach")
                else {
                    val pvLow = b.totalPvW <= settings.sunsetPvW
                    val since = if (pvLow) (run.conditionSinceMs ?: nowMs) else null
                    if (pvLow && since != null && nowMs - since >= settings.sunsetSustainMinutes * 60_000L)
                        CalibrationStep(run.copy(phase = CalibrationPhase.EVENING_DISCHARGE, phaseSinceMs = nowMs, conditionSinceMs = null),
                            listOf(BatteryCommand.SetDod(id, settings.dischargeDodPercent)),
                            listOf("$name: Sonnenuntergang – Abend-Entladung (Auto) läuft"))
                    else CalibrationStep(run.copy(conditionSinceMs = since), emptyList(), emptyList())
                }
            }

            CalibrationPhase.EVENING_DISCHARGE -> {
                if (b.socPercent <= 1) go(run, CalibrationPhase.HOLD_EMPTY, nowMs, ev = "$name: bereits leer")
                else if (nowMs >= nextLocalTime(run.phaseSinceMs, settings.dischargeStartMinuteOfDay))
                    CalibrationStep(run.copy(phase = CalibrationPhase.MANUAL_EMPTY, phaseSinceMs = nowMs, conditionSinceMs = null),
                        listOf(BatteryCommand.SetAdaptiveMode(id, false)),
                        listOf("$name: ${timeLabel(settings.dischargeStartMinuteOfDay)} – Rest im Manuell-Modus entladen"))
                else none(run)
            }

            CalibrationPhase.MANUAL_EMPTY -> {
                val floor = 100 - settings.dischargeDodPercent
                val empty = b.socPercent <= floor + 1 ||
                    (b.socPercent <= floor + 3 && b.totalOutputW == 0 && b.totalPvW == 0)
                if (empty) go(run, CalibrationPhase.HOLD_EMPTY, nowMs, ev = "$name: Untergrenze erreicht (${b.socPercent} %)")
                else none(run)
            }

            CalibrationPhase.HOLD_EMPTY ->
                if (nowMs - run.phaseSinceMs < holdMs) none(run)
                else CalibrationStep(run.copy(phase = CalibrationPhase.RECHARGE, phaseSinceMs = nowMs, conditionSinceMs = null),
                    listOf(BatteryCommand.SetAdaptiveMode(id, true), BatteryCommand.SetDod(id, 0)),
                    listOf("$name: unten gehalten – lade erneut voll"))

            CalibrationPhase.RECHARGE ->
                if (b.socPercent >= 100) go(run, CalibrationPhase.HOLD_FULL2, nowMs, ev = "$name: 100 % erreicht (2.)")
                else none(run)

            CalibrationPhase.HOLD_FULL2 -> {
                if (b.socPercent < 99) go(run, CalibrationPhase.RECHARGE, nowMs, ev = "$name: unter 100 % (2.) – lädt weiter")
                else if (nowMs - run.phaseSinceMs < holdMs) none(run)
                else {
                    val saved = run.saved
                    val cmds = listOf(
                        BatteryCommand.SetDod(id, saved?.dodPercent ?: 90),
                        BatteryCommand.SetChargeMode(id, ChargeMode.SIMULTANEOUS),
                        BatteryCommand.SetAdaptiveMode(id, true),
                    )
                    CalibrationStep(run.copy(phase = CalibrationPhase.VERIFY, phaseSinceMs = nowMs, conditionSinceMs = null),
                        cmds, listOf("$name: freigegeben (gleichzeitig, Auto) – prüfe Nulleinspeisung"))
                }
            }

            CalibrationPhase.VERIFY -> verify(run, b, snapshot, nowMs)

            CalibrationPhase.RECOVER_MANUAL ->
                if (nowMs - run.phaseSinceMs < RECOVER_MANUAL_WAIT_MS) none(run)
                else CalibrationStep(run.copy(phase = CalibrationPhase.RECOVER_WAIT, phaseSinceMs = nowMs, conditionSinceMs = null),
                    listOf(BatteryCommand.Reboot(id)), listOf("$name: Neustart"))

            CalibrationPhase.RECOVER_WAIT -> recoverWait(run, id, b, nowMs)

            CalibrationPhase.DONE, CalibrationPhase.ABORTED -> none(run)
        }
    }

    private fun prepare(run: CalibrationRun, b: BatteryState, nowMs: Long): CalibrationStep {
        val dod = b.dodPercent ?: return CalibrationStep(run, emptyList(), listOf("${b.name}: warte auf DoD-Wert"))
        val saved = SavedBatterySettings(dod, b.chargeMode.takeIf { it != ChargeMode.UNKNOWN } ?: ChargeMode.SIMULTANEOUS, b.adaptiveMode)
        return CalibrationStep(
            run.copy(phase = CalibrationPhase.CHARGING, phaseSinceMs = nowMs, conditionSinceMs = null, saved = saved),
            listOf(BatteryCommand.SetChargeMode(b.id, ChargeMode.CHARGE_THEN_DISCHARGE), BatteryCommand.SetDod(b.id, 0)),
            listOf("${b.name}: Kalibrierung – lade auf 100 % (DoD 0 %); gesichert: DoD $dod %, ${saved.chargeMode}, Auto=${saved.adaptive}"),
        )
    }

    private fun verify(run: CalibrationRun, b: BatteryState, snapshot: SystemSnapshot, nowMs: Long): CalibrationStep {
        val id = b.id; val name = b.name
        // The release must actually have arrived at the device. Without this a battery still running
        // the 800 W manual schedule would pass the output check and the run would report success.
        // Only an explicit contradiction counts; fields the device does not report cannot be judged.
        val saved = run.saved
        val released = b.adaptiveMode != false &&
            b.chargeMode != ChargeMode.CHARGE_THEN_DISCHARGE &&
            (saved == null || b.dodPercent == null || b.dodPercent == saved.dodPercent)
        if (!released) {
            // The command is re-sent until confirmed; give it time, then stop rather than claim success.
            if (nowMs - run.phaseSinceMs < RELEASE_CONFIRM_MS) return CalibrationStep(run.copy(conditionSinceMs = null), emptyList(), emptyList())
            val detail = "Auto=${b.adaptiveMode}, Lademodus=${b.chargeMode}, DoD=${b.dodPercent}"
            return CalibrationStep(
                run.copy(
                    phase = CalibrationPhase.ABORTED,
                    results = run.results + BatteryCalibrationResult(id, false, "Freigabe kam am Gerät nicht an ($detail) – gestoppt", nowMs),
                ),
                run.saved?.let { restoreCmds(id, it) } ?: emptyList(),
                listOf("$name: Freigabe nicht bestätigt ($detail) – Kalibrierung GESTOPPT"),
            )
        }
        if (b.totalOutputW > VERIFY_DELIVER_W) return advance(run, id, true, "Vollzyklus abgeschlossen, Nulleinspeisung läuft", nowMs)
        val gridW = snapshot.meter?.totalW
        val failing = gridW != null && gridW > VERIFY_IMPORT_W && b.totalOutputW < VERIFY_OUTPUT_W
        return if (failing) {
            val since = run.conditionSinceMs ?: nowMs
            if (nowMs - since < VERIFY_FAIL_SUSTAIN_MS) return CalibrationStep(run.copy(conditionSinceMs = since), emptyList(), emptyList())
            if (run.recoveryAttempt >= settings.recoveryAttempts) {
                CalibrationStep(
                    run.copy(phase = CalibrationPhase.ABORTED,
                        results = run.results + BatteryCalibrationResult(id, false, "Nulleinspeisung nach ${settings.recoveryAttempts} Versuchen nicht aktiv – gestoppt", nowMs)),
                    emptyList(),
                    listOf("$name: Nulleinspeisung nicht aktiv – Kalibrierung GESTOPPT (bitte Gerät prüfen)"),
                )
            } else {
                CalibrationStep(run.copy(phase = CalibrationPhase.RECOVER_MANUAL, phaseSinceMs = nowMs, conditionSinceMs = null),
                    listOf(BatteryCommand.SetAdaptiveMode(id, false)),
                    listOf("$name: keine Nulleinspeisung – Erholung ${run.recoveryAttempt + 1}/${settings.recoveryAttempts}"))
            }
        } else {
            if (nowMs - run.phaseSinceMs >= VERIFY_TIMEOUT_MS) advance(run, id, true, "Vollzyklus abgeschlossen (keine Last zum Prüfen)", nowMs)
            else CalibrationStep(run.copy(conditionSinceMs = null), emptyList(), emptyList())
        }
    }

    /**
     * Waits for the battery to come back after the restart. [b] is null while it does not answer.
     *
     * The device only counts as back when a frame arrives that is NEWER than the restart command and
     * a minimum wait has passed, so the settle time cannot run out while the device is still booting
     * and "back to Auto" is never sent into the void.
     */
    private fun recoverWait(run: CalibrationRun, id: String, b: BatteryState?, nowMs: Long): CalibrationStep {
        val name = b?.name ?: id
        val waited = nowMs - run.phaseSinceMs
        val back = b != null && waited >= RECOVER_MIN_WAIT_MS &&
            b.timestampMs > run.phaseSinceMs && nowMs - b.timestampMs < RECOVER_REACHABLE_MS
        if (!back) {
            if (waited < RECOVER_REACHABLE_CAP_MS) return CalibrationStep(run.copy(conditionSinceMs = null), emptyList(), emptyList())
            // It never came back. Restoring is still queued (commands are re-sent until confirmed).
            return CalibrationStep(
                run.copy(
                    phase = CalibrationPhase.ABORTED,
                    results = run.results + BatteryCalibrationResult(id, false, "Nach dem Neustart ${RECOVER_REACHABLE_CAP_MS / 60_000} min nicht zurückgekehrt – gestoppt", nowMs),
                ),
                run.saved?.let { restoreCmds(id, it) } ?: emptyList(),
                listOf("$name: nach dem Neustart nicht zurückgekehrt – Kalibrierung GESTOPPT"),
            )
        }
        val since = run.conditionSinceMs ?: nowMs
        if (nowMs - since < RECOVER_SETTLE_MS) return CalibrationStep(run.copy(conditionSinceMs = since), emptyList(), emptyList())
        return CalibrationStep(run.copy(phase = CalibrationPhase.VERIFY, phaseSinceMs = nowMs, conditionSinceMs = null, recoveryAttempt = run.recoveryAttempt + 1),
            listOf(BatteryCommand.SetAdaptiveMode(id, true)),
            listOf("$name: wieder erreichbar – zurück auf Auto, prüfe Nulleinspeisung"))
    }

    private fun advance(run: CalibrationRun, id: String, success: Boolean, message: String, nowMs: Long, commands: List<BatteryCommand> = emptyList()): CalibrationStep {
        val nextIndex = run.index + 1
        val done = nextIndex >= run.queue.size
        val next = run.copy(
            index = nextIndex,
            phase = if (done) CalibrationPhase.DONE else CalibrationPhase.PREPARE,
            batteryStartedMs = nowMs, phaseSinceMs = nowMs, conditionSinceMs = null,
            saved = null, recoveryAttempt = 0,
            results = run.results + BatteryCalibrationResult(id, success, message, nowMs),
        )
        return CalibrationStep(next, commands, listOf("$id: $message"))
    }

    private fun none(run: CalibrationRun) = CalibrationStep(run, emptyList(), emptyList())
    private fun go(run: CalibrationRun, phase: CalibrationPhase, nowMs: Long, ev: String) =
        CalibrationStep(run.copy(phase = phase, phaseSinceMs = nowMs, conditionSinceMs = null), emptyList(), listOf(ev))

    private fun restoreCmds(id: String, saved: SavedBatterySettings) = listOf(
        BatteryCommand.SetDod(id, saved.dodPercent),
        BatteryCommand.SetChargeMode(id, saved.chargeMode),
        BatteryCommand.SetAdaptiveMode(id, saved.adaptive ?: true),
    )

    /** Next instant strictly after [fromMs] at the given local minute-of-day. */
    private fun nextLocalTime(fromMs: Long, minuteOfDay: Int): Long {
        val from = Instant.ofEpochMilli(fromMs)
        var t = from.atZone(zone).toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone)
        if (!t.toInstant().isAfter(from)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    private fun timeLabel(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    companion object {
        const val VERIFY_DELIVER_W = 20          // battery output above this = zero-export clearly works
        const val VERIFY_IMPORT_W = 50           // grid import above this = there is load to cover
        const val VERIFY_OUTPUT_W = 10           // battery output below this while importing = not delivering
        const val VERIFY_FAIL_SUSTAIN_MS = 120_000L
        const val VERIFY_TIMEOUT_MS = 10 * 60_000L
        const val RELEASE_CONFIRM_MS = 5 * 60_000L   // time the released settings get to show up on the device
        const val RECOVER_MANUAL_WAIT_MS = 15_000L
        const val RECOVER_MIN_WAIT_MS = 20_000L      // never treat the battery as back before this (it reboots ~35 s)
        const val RECOVER_REACHABLE_MS = 30_000L // battery counts as back when its frame is younger than this
        const val RECOVER_SETTLE_MS = 60_000L
        const val RECOVER_REACHABLE_CAP_MS = 10 * 60_000L
    }
}
