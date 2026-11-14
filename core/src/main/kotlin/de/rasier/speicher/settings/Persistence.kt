package de.rasier.speicher.settings

import de.rasier.speicher.control.Alert
import de.rasier.speicher.control.AutomationState
import de.rasier.speicher.energy.DailyEnergy
import de.rasier.speicher.energy.DeviceDay
import de.rasier.speicher.energy.MeterEntry
import de.rasier.speicher.model.SystemSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Short history for charts: one compact point per minute.
 *
 * The server stores the AVERAGE over the minute, not a single instant, so the statistics rebuilt from
 * it match the live integration. [gridMinW]/[gridMaxW] keep short peaks visible despite the averaging.
 * [ids] names the batteries behind the positional lists; older points without it are positional only.
 */
@Serializable
data class HistoryPoint(
    val t: Long,
    val gridW: Int?,
    val gridMinW: Int? = null,
    val gridMaxW: Int? = null,
    val ids: List<String> = emptyList(),
    val pvW: Int,
    val outW: Int,
    val soc: List<Int>,
    /** Per-battery PV input and output power, in the battery order, for per-battery charts. */
    val batPvW: List<Int> = emptyList(),
    val batOutW: List<Int> = emptyList(),
    /**
     * Whether each battery was idle for the WHOLE minute. A minute mean cannot answer that: 400 W for
     * ten seconds averages to 67 W and slips under the idle threshold, so time that was not idle was
     * counted as idle and self-discharge read about twice as high in the day view as in the week view.
     */
    val batIdle: List<Boolean> = emptyList(),
    /** CT002 per-phase power (pwr_a/b/c), so a grid spike can be traced to one phase afterwards. */
    val meterPhaseW: List<Int> = emptyList(),
)

fun SystemSnapshot.toHistoryPoint() = HistoryPoint(
    t = timestampMs,
    gridW = meter?.totalW,
    pvW = batteries.sumOf { it.totalPvW },
    outW = batteries.sumOf { it.totalOutputW },
    soc = batteries.map { it.socPercent },
    batPvW = batteries.map { it.totalPvW },
    batOutW = batteries.map { it.totalOutputW },
    meterPhaseW = meter?.phaseW ?: emptyList(),
)

@Serializable
data class EventEntry(val t: Long, val text: String)

/**
 * Everything that must survive an app restart, stored as JSON files in the app's private directory.
 *
 * A file that cannot be decoded (e.g. because an update added a field) is NEVER silently dropped:
 * it is copied aside, reported through [log] and listed in [unreadable], so the caller can warn the
 * user instead of overwriting the old content with defaults.
 */
class FileStore(private val dir: File, private val log: (String) -> Unit = {}) {
    private val json = AppSettings.json
    private val _unreadable = mutableListOf<String>()

    /** Names of files that existed but could not be decoded during this session. */
    val unreadable: List<String> get() = _unreadable.toList()

    init {
        dir.mkdirs()
    }

    fun loadDays(): List<DailyEnergy> = read("days.json", ListSerializer(DailyEnergy.serializer())) ?: emptyList()
    fun saveDays(days: Collection<DailyEnergy>) = write("days.json", ListSerializer(DailyEnergy.serializer()), days.toList())

    fun loadAutomation(): AutomationState = read("automation.json", AutomationState.serializer()) ?: AutomationState()
    fun saveAutomation(state: AutomationState) = write("automation.json", AutomationState.serializer(), state)

    fun loadHistory(): List<HistoryPoint> = read("history.json", ListSerializer(HistoryPoint.serializer())) ?: emptyList()
    fun saveHistory(points: List<HistoryPoint>) = write("history.json", ListSerializer(HistoryPoint.serializer()), points)

    fun loadEvents(): List<EventEntry> = read("events.json", ListSerializer(EventEntry.serializer())) ?: emptyList()
    fun saveEvents(events: List<EventEntry>) = write("events.json", ListSerializer(EventEntry.serializer()), events)

    fun loadAlerts(): List<Alert> = read("alerts.json", ListSerializer(Alert.serializer())) ?: emptyList()
    fun saveAlerts(alerts: List<Alert>) = write("alerts.json", ListSerializer(Alert.serializer()), alerts)

    fun loadMeterEntries(): List<MeterEntry> = read("meter.json", ListSerializer(MeterEntry.serializer())) ?: emptyList()
    fun saveMeterEntries(entries: List<MeterEntry>) = write("meter.json", ListSerializer(MeterEntry.serializer()), entries)

    /**
     * Last usable capacity seen per battery. The device does not report it directly: it is derived
     * from stored energy divided by SOC, so a battery sitting at 0 % yields nothing. Without keeping
     * it across restarts, capacity and stored energy read as blank after every update until the
     * battery happens to be above 0 % again - and self-discharge, which needs the capacity, is not
     * measured in the meantime.
     */
    fun loadCapacities(): Map<String, Int> =
        read("capacities.json", MapSerializer(String.serializer(), Int.serializer())) ?: emptyMap()
    fun saveCapacities(byId: Map<String, Int>) =
        write("capacities.json", MapSerializer(String.serializer(), Int.serializer()), byId)

    fun loadDeviceDays(): List<DeviceDay> = read("device_days.json", ListSerializer(DeviceDay.serializer())) ?: emptyList()
    fun saveDeviceDays(days: List<DeviceDay>) = write("device_days.json", ListSerializer(DeviceDay.serializer()), days)

    private fun <T> read(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrElse {
            _unreadable += name
            log("$name nicht lesbar: ${it.message}")
            return null
        }
        return runCatching { json.decodeFromString(serializer, text) }.getOrElse { e ->
            // Keep the content instead of letting the next write overwrite it with defaults.
            val copy = File(dir, "$name.unlesbar")
            runCatching { f.copyTo(copy, overwrite = true) }
            _unreadable += name
            log("$name ist unlesbar (${e.message}). Kopie: ${copy.name}. Es gelten Standardwerte.")
            null
        }
    }

    /**
     * Atomic write: a crash during saving never leaves a half-written file. Once per [BACKUP_MS] the
     * previous content is kept as `<name>.bak`, so a bad write or a format change stays recoverable.
     */
    private fun <T> write(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        val target = File(dir, name)
        if (target.exists()) {
            val bak = File(dir, "$name.bak")
            if (!bak.exists() || System.currentTimeMillis() - bak.lastModified() > BACKUP_MS) {
                runCatching { target.copyTo(bak, overwrite = true) }
            }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        /** Minimum age of the `.bak` copy before it is refreshed (keeps the write load low). */
        const val BACKUP_MS = 12 * 3_600_000L
    }
}
