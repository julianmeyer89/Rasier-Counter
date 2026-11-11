package de.rasier.speicher.settings

import de.rasier.speicher.control.Alert
import de.rasier.speicher.control.AutomationState
import de.rasier.speicher.energy.DailyEnergy
import de.rasier.speicher.energy.DeviceDay
import de.rasier.speicher.energy.MeterEntry
import de.rasier.speicher.model.SystemSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Short history for charts: one compact point per sample. */
@Serializable
data class HistoryPoint(
    val t: Long,
    val gridW: Int?,
    val pvW: Int,
    val outW: Int,
    val soc: List<Int>,
    /** Per-battery PV input and output power, in the battery order, for per-battery charts. */
    val batPvW: List<Int> = emptyList(),
    val batOutW: List<Int> = emptyList(),
)

fun SystemSnapshot.toHistoryPoint() = HistoryPoint(
    t = timestampMs,
    gridW = meter?.totalW,
    pvW = batteries.sumOf { it.totalPvW },
    outW = batteries.sumOf { it.totalOutputW },
    soc = batteries.map { it.socPercent },
    batPvW = batteries.map { it.totalPvW },
    batOutW = batteries.map { it.totalOutputW },
)

@Serializable
data class EventEntry(val t: Long, val text: String)

/** Everything that must survive an app restart, stored as JSON files in the app's private directory. */
class FileStore(private val dir: File) {
    private val json = AppSettings.json

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

    fun loadDeviceDays(): List<DeviceDay> = read("device_days.json", ListSerializer(DeviceDay.serializer())) ?: emptyList()
    fun saveDeviceDays(days: List<DeviceDay>) = write("device_days.json", ListSerializer(DeviceDay.serializer()), days)

    private fun <T> read(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(serializer, f.readText()) }.getOrNull()
    }

    /** Atomic write: a crash during saving never leaves a half-written file. */
    private fun <T> write(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
