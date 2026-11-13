package de.rasier.speicher.control

import de.rasier.speicher.model.SystemSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class MonitorSettings(
    /** A battery without new data for this long is reported as unreachable. */
    val batteryStaleMinutes: Int = 15,
    /** Grid import (W) that is considered "the batteries should be helping". */
    val importThresholdW: Int = 150,
    /** Grid export (W) that is considered "the batteries feed in too much". */
    val exportThresholdW: Int = 150,
    /** How long a condition must last before an alert is raised. */
    val conditionMinutes: Int = 10,
    val socSpreadWarnPercent: Int = 25,
)

@Serializable
data class MonitorState(
    /** Since when each condition has been true (key -> epoch ms). */
    val since: Map<String, Long> = emptyMap(),
)

data class MonitorStep(val state: MonitorState, val alerts: List<Alert>)

/**
 * Detects problems the Marstek app does not report, in particular a broken link between the CT002
 * and the batteries: the house draws from the grid while charged batteries stay idle, or the
 * batteries keep feeding in although power is already being exported.
 */
class Monitor(private val settings: MonitorSettings = MonitorSettings()) {

    fun step(
        state: MonitorState,
        snapshot: SystemSnapshot,
        lastSeenMs: Map<String, Long>,
        names: Map<String, String>,
        nowMs: Long,
        /** A calibration drives the batteries on purpose; its export and idle phases are not faults. */
        calibrating: Boolean = false,
    ): MonitorStep {
        val active = mutableMapOf<String, Alert>()
        val conditionMs = settings.conditionMinutes * 60_000L

        for ((id, seen) in lastSeenMs) {
            if (nowMs - seen > settings.batteryStaleMinutes * 60_000L) {
                val name = names[id] ?: id
                active["stale:$id"] = Alert(
                    "stale:$id", Alert.Severity.WARNING, "$name nicht erreichbar",
                    "Seit ${(nowMs - seen) / 60_000} Minuten keine Daten von $name.", nowMs,
                )
            }
        }

        val meter = snapshot.meter
        val batteries = snapshot.batteries
        if (meter != null && batteries.isNotEmpty() && !calibrating) {
            val idleWithCharge = batteries.filter { b ->
                val floor = b.floorSocPercent ?: 10
                b.socPercent > floor + 5 && b.totalOutputW < 20
            }
            if (meter.totalW > settings.importThresholdW && idleWithCharge.size == batteries.size) {
                active["ct:idle"] = Alert(
                    "ct:idle", Alert.Severity.WARNING, "Speicher reagieren nicht auf den Verbrauch",
                    "Seit mindestens ${settings.conditionMinutes} min werden ${meter.totalW} W aus dem Netz bezogen, " +
                        "obwohl die Speicher geladen sind und nichts abgeben. Wahrscheinlich ist die Verbindung " +
                        "zwischen CT002 und Speichern unterbrochen (WLAN, Repeater, Gastnetz).", nowMs,
                )
            }
            val feeding = batteries.filter { it.totalOutputW > 100 }
            if (-meter.totalW > settings.exportThresholdW && feeding.isNotEmpty()) {
                active["ct:export"] = Alert(
                    "ct:export", Alert.Severity.WARNING, "Speicher speisen ins Netz ein",
                    "Seit mindestens ${settings.conditionMinutes} min werden ${-meter.totalW} W ins Netz eingespeist, " +
                        "während ${feeding.joinToString { it.name }} Leistung abgibt. Die Nulleinspeisung des CT002 " +
                        "greift nicht, vermutlich ist die CT002-Verbindung gestört.", nowMs,
                )
            }
        }

        val group = GroupState(batteries)
        if (group.socSpread >= settings.socSpreadWarnPercent) {
            active["soc:spread"] = Alert(
                "soc:spread", Alert.Severity.INFO, "Ladestände weichen stark ab",
                "Die Speicher unterscheiden sich um ${group.socSpread} %. Eine Kalibrierung kann helfen.", nowMs,
            )
        }

        // Only report conditions that have lasted long enough; stale-device alerts are immediate.
        val since = state.since.filterKeys { it in active } + active.keys.filter { it !in state.since }.associateWith { nowMs }
        val alerts = active.values.filter { a ->
            a.key.startsWith("stale:") || nowMs - (since[a.key] ?: nowMs) >= conditionMs
        }
        return MonitorStep(MonitorState(since), alerts)
    }
}
