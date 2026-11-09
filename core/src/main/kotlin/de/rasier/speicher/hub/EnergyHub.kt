package de.rasier.speicher.hub

import de.rasier.speicher.cloud.CloudDevice
import de.rasier.speicher.cloud.MarstekCloudClient
import de.rasier.speicher.control.Alert
import de.rasier.speicher.control.AutomationEngine
import de.rasier.speicher.control.AutomationState
import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.control.BatteryController
import de.rasier.speicher.control.GroupState
import de.rasier.speicher.energy.DailyEnergy
import de.rasier.speicher.energy.EnergyIntegrator
import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.MeterReading
import de.rasier.speicher.model.SystemSnapshot
import de.rasier.speicher.protocol.b2500.B2500Client
import de.rasier.speicher.protocol.b2500.toBatteryState
import de.rasier.speicher.protocol.ct002.Ct002Reader
import de.rasier.speicher.settings.AppSettings
import de.rasier.speicher.settings.DataMode
import de.rasier.speicher.settings.EventEntry
import de.rasier.speicher.settings.FileStore
import de.rasier.speicher.settings.HistoryPoint
import de.rasier.speicher.settings.toHistoryPoint
import de.rasier.speicher.sim.SimulatedSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Connection state of one data source, shown in the app. */
data class SourceStatus(val name: String, val ok: Boolean, val detail: String, val lastSuccessMs: Long?)

data class HubState(
    val settings: AppSettings,
    val snapshot: SystemSnapshot? = null,
    val group: GroupState = GroupState(emptyList()),
    val today: DailyEnergy? = null,
    val days: List<DailyEnergy> = emptyList(),
    val history: List<HistoryPoint> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val events: List<EventEntry> = emptyList(),
    val automation: AutomationState = AutomationState(),
    val sources: List<SourceStatus> = emptyList(),
    /** Commands the automations want to send while writing is disabled (shown as suggestions). */
    val suggestions: List<BatteryCommand> = emptyList(),
    val busy: Boolean = false,
)

/** Hardware access supplied by the platform (Android). Null entries mean "not available". */
class HubDevices(
    val ble: B2500Client? = null,
    val ctReaderFactory: ((host: String, readerId: String) -> Ct002Reader)? = null,
    val cloud: MarstekCloudClient? = null,
    /** MD5 of the Marstek password, read from secure storage when needed. */
    val passwordMd5: () -> String? = { null },
    /** Reader id for the CT002, generated once per installation. */
    val ctReaderId: () -> String = { "02a57e000000" },
)

/**
 * The heart of the app: polls the devices, runs the automations, integrates statistics and keeps
 * everything persistent. Platform independent; the Android service calls [poll] periodically.
 */
class EnergyHub(
    initialSettings: AppSettings,
    private val store: FileStore,
    private val devices: HubDevices,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(HubState(settings = initialSettings))
    val state: StateFlow<HubState> = _state

    private var settings = initialSettings
    private var engine = AutomationEngine(settings.automation)
    private var integrator = newIntegrator()
    private var simulator: SimulatedSystem? = null
    private var ctReader: Ct002Reader? = null
    private val lastBatteries = linkedMapOf<String, BatteryState>()
    private var lastMeter: MeterReading? = null
    private var lastCloudMs = 0L
    private var cloudDevices: List<CloudDevice> = emptyList()
    private var pollCount = 0L
    private val writeGuard = WriteGuard()
    private val sources = linkedMapOf<String, SourceStatus>()
    private var lastSaveMs = 0L

    init {
        val history = store.loadHistory()
        _state.value = _state.value.copy(
            automation = store.loadAutomation(),
            history = history,
            events = store.loadEvents(),
            alerts = store.loadAlerts(),
        )
        refreshDays()
    }

    private fun newIntegrator() = EnergyIntegrator(settings.system, zone).also { it.restore(store.loadDays()) }

    suspend fun updateSettings(new: AppSettings) = mutex.withLock {
        val modeChanged = new.mode != settings.mode
        settings = new
        engine = AutomationEngine(new.automation)
        if (modeChanged) {
            simulator = null
            lastBatteries.clear()
            lastMeter = null
        }
        ctReader?.close()
        ctReader = null
        integrator = newIntegrator()
        _state.value = _state.value.copy(settings = new)
    }

    /** One polling cycle. Safe to call from a timer; overlapping calls are serialized. */
    suspend fun poll(nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
        _state.value = _state.value.copy(busy = true)
        try {
            val snapshot = when (settings.mode) {
                DataMode.DEMO -> demoSnapshot(nowMs)
                DataMode.LIVE -> liveSnapshot(nowMs)
            }
            process(snapshot, nowMs)
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    suspend fun startCalibration(batteryIds: List<String>) = mutex.withLock {
        val now = System.currentTimeMillis()
        val r = engine.startCalibration(_state.value.automation, batteryIds, now)
        apply(r.state, r.commands, r.events, now)
    }

    suspend fun abortCalibration() = mutex.withLock {
        val now = System.currentTimeMillis()
        val r = engine.abortCalibration(_state.value.automation, now)
        apply(r.state, r.commands, r.events, now)
    }

    /** Manual change from the app (always allowed, it is an explicit user action). */
    suspend fun sendManual(command: BatteryCommand): Boolean = mutex.withLock {
        val ok = execute(command, manual = true)
        addEvents(listOf((if (ok) "Gesendet: " else "Fehlgeschlagen: ") + describe(command)), System.currentTimeMillis())
        ok
    }

    /** Closes the CT002 socket and saves everything. Waits for a running poll to finish first. */
    suspend fun close() = mutex.withLock {
        ctReader?.close()
        ctReader = null
        persist(force = true)
    }

    // ---------------------------------------------------------------- sources

    private fun demoSnapshot(nowMs: Long): SystemSnapshot {
        val sim = simulator ?: SimulatedSystem(zone).also { simulator = it }
        setSource("Demo", true, "Simulierte Geräte", nowMs)
        return sim.advance(nowMs)
    }

    private suspend fun liveSnapshot(nowMs: Long): SystemSnapshot {
        pollCount++
        val configured = settings.batteries.filter { it.bleAddress.isNotBlank() }
        val ble = devices.ble
        for (cfg in configured) {
            if (ble == null) break
            try {
                val reading = ble.read(cfg.bleAddress, withDetails = pollCount % 10 == 1L)
                val prev = lastBatteries[cfg.bleAddress]
                var state = reading.toBatteryState(cfg.bleAddress, cfg.name, nowMs, prev?.capacityWh)
                // Adaptive mode is only read every 10th cycle; keep the last known value.
                if (state.adaptiveMode == null) state = state.copy(adaptiveMode = prev?.adaptiveMode)
                lastBatteries[cfg.bleAddress] = state
                setSource(cfg.name, true, "Bluetooth, FW ${reading.runtime.firmware}", nowMs)
            } catch (e: Exception) {
                setSource(cfg.name, false, "Bluetooth: ${e.message ?: e.javaClass.simpleName}", null)
            }
        }

        readCt002(nowMs)
        readCloud(nowMs)

        val freshMs = settings.pollIntervalSeconds * 3_000L
        val batteries = configured.mapNotNull { lastBatteries[it.bleAddress] }.filter { nowMs - it.timestampMs <= freshMs }
        val meter = lastMeter?.takeIf { nowMs - it.timestampMs <= freshMs }
        return SystemSnapshot(nowMs, batteries, meter)
    }

    private suspend fun readCt002(nowMs: Long) {
        val factory = devices.ctReaderFactory ?: return
        if (settings.ct002Ip.isBlank()) return
        try {
            val reply = withContext(Dispatchers.IO) {
                val reader = ctReader ?: factory(settings.ct002Ip, devices.ctReaderId()).also { ctReader = it }
                reader.read()
            }
            if (reply != null) {
                lastMeter = reply.toMeterReading(nowMs)
                setSource("CT002", true, "WLAN, ${reply.ctType}, Signal ${reply.wifiRssi ?: "?"} dBm", nowMs)
            } else {
                setSource("CT002", false, "keine Antwort (nicht im Heimnetz?)", null)
            }
        } catch (e: Exception) {
            ctReader?.close()
            ctReader = null
            setSource("CT002", false, "WLAN: ${e.message ?: e.javaClass.simpleName}", null)
        }
    }

    /** Cloud as slow fallback (e.g. away from home): fills batteries without fresh Bluetooth data. */
    private suspend fun readCloud(nowMs: Long) {
        val cloud = devices.cloud ?: return
        val md5 = devices.passwordMd5() ?: return
        if (settings.email.isBlank()) return
        if (nowMs - lastCloudMs < settings.cloudIntervalSeconds * 1000L) return
        lastCloudMs = nowMs
        try {
            cloudDevices = withContext(Dispatchers.IO) { cloud.deviceList(settings.email, md5) }
            setSource("Marstek-Cloud", true, "${cloudDevices.size} Geräte", nowMs)
        } catch (e: Exception) {
            setSource("Marstek-Cloud", false, MarstekCloudClient.redact(e.message) ?: "Fehler", null)
            return
        }
        val freshMs = settings.pollIntervalSeconds * 3_000L
        for (cfg in settings.batteries) {
            val local = lastBatteries[cfg.bleAddress.ifBlank { "cloud:${cfg.cloudDevid}" }]
            if (local != null && nowMs - local.timestampMs <= freshMs) continue
            val dev = cloudDevices.firstOrNull { it.devid == cfg.cloudDevid } ?: continue
            val soc = dev.soc ?: continue
            val id = cfg.bleAddress.ifBlank { "cloud:${dev.devid}" }
            val reportMs = dev.reportTimeMs ?: nowMs
            // Cloud values can be old; they are marked with their own timestamp.
            lastBatteries[id] = BatteryState(
                id = id, name = cfg.name, timestampMs = minOf(reportMs, nowMs), socPercent = soc,
                capacityWh = local?.capacityWh,
                outputW = listOfNotNull(dev.dischargeW), deviceType = dev.type, firmware = dev.version,
            )
        }
    }

    private fun setSource(name: String, ok: Boolean, detail: String, nowMs: Long?) {
        val last = if (ok) nowMs else sources[name]?.lastSuccessMs
        sources[name] = SourceStatus(name, ok, detail, last)
    }

    // ---------------------------------------------------------------- processing

    private suspend fun process(snapshot: SystemSnapshot, nowMs: Long) {
        integrator.add(snapshot)
        val tick = engine.tick(_state.value.automation, snapshot, nowMs)
        apply(tick.state, tick.commands, tick.events, nowMs)

        val history = (_state.value.history + snapshot.toHistoryPoint())
            .filter { nowMs - it.t <= HISTORY_MS }
            .let { if (it.size > MAX_HISTORY) it.takeLast(MAX_HISTORY) else it }
        _state.value = _state.value.copy(
            settings = settings,
            snapshot = snapshot,
            group = GroupState(snapshot.batteries),
            alerts = tick.alerts,
            history = history,
            sources = sources.values.toList(),
        )
        refreshDays()
        persist(force = false)
    }

    private suspend fun apply(state: AutomationState, commands: List<BatteryCommand>, events: List<String>, nowMs: Long) {
        val notes = events.toMutableList()
        val suggestions = mutableListOf<BatteryCommand>()
        for (c in commands) {
            if (!settings.automationsMayWrite) {
                suggestions += c
                notes += "Vorschlag (Schreiben aus): ${describe(c)}"
                continue
            }
            val ok = execute(c, manual = false)
            notes += (if (ok) "Automatik: " else "Automatik fehlgeschlagen: ") + describe(c)
        }
        _state.value = _state.value.copy(automation = state, suggestions = if (commands.isEmpty()) _state.value.suggestions else suggestions)
        addEvents(notes, nowMs)
        store.saveAutomation(state)
    }

    private suspend fun execute(command: BatteryCommand, manual: Boolean): Boolean {
        val current = lastBatteries[command.batteryId]
        if (!manual && current != null && isNoOp(command, current)) return true
        val now = System.currentTimeMillis()
        if (!manual && !writeGuard.allow(command.batteryId, now, zone)) {
            log("Schreiblimit erreicht für ${command.batteryId}, übersprungen: $command")
            return false
        }
        val controller: BatteryController = when (settings.mode) {
            DataMode.DEMO -> simulator ?: return false
            DataMode.LIVE -> {
                val ble = devices.ble ?: return false
                BatteryController { cmd -> ble.write(cmd.batteryId, cmd) }
            }
        }
        writeGuard.record(command.batteryId, now, zone)
        return try {
            controller.execute(command)
        } catch (e: Exception) {
            log("Schreiben fehlgeschlagen: ${e.message}")
            false
        }
    }

    private fun isNoOp(c: BatteryCommand, b: BatteryState) = when (c) {
        is BatteryCommand.SetDod -> b.dodPercent == c.percent
        is BatteryCommand.SetChargeMode -> b.chargeMode == c.mode
        is BatteryCommand.SetAdaptiveMode -> b.adaptiveMode == c.enabled
    }

    private fun describe(c: BatteryCommand): String {
        val name = settings.batteries.firstOrNull { it.bleAddress == c.batteryId }?.name
            ?: lastBatteries[c.batteryId]?.name ?: c.batteryId
        return when (c) {
            is BatteryCommand.SetDod -> "$name: Entladetiefe ${c.percent} %"
            is BatteryCommand.SetChargeMode -> "$name: Lademodus ${c.mode}"
            is BatteryCommand.SetAdaptiveMode -> "$name: adaptiver Modus ${if (c.enabled) "an" else "aus"}"
        }
    }

    private fun addEvents(texts: List<String>, nowMs: Long) {
        if (texts.isEmpty()) return
        texts.forEach { log(it) }
        val events = (_state.value.events + texts.map { EventEntry(nowMs, it) }).takeLast(MAX_EVENTS)
        _state.value = _state.value.copy(events = events)
        store.saveEvents(events)
    }

    private fun refreshDays() {
        val days = integrator.totals.values.sortedBy { it.date }
        val today = LocalDate.now(zone).toString()
        _state.value = _state.value.copy(days = days, today = days.firstOrNull { it.date == today })
    }

    private fun persist(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveMs < SAVE_INTERVAL_MS) return
        lastSaveMs = now
        // Keep two years of daily statistics.
        val cutoff = LocalDate.now(zone).minusDays(730).toString()
        store.saveDays(integrator.totals.values.filter { it.date >= cutoff })
        store.saveHistory(_state.value.history)
        store.saveAlerts(_state.value.alerts)
    }

    companion object {
        const val HISTORY_MS = 48 * 3_600_000L
        const val MAX_HISTORY = 6_000
        const val MAX_EVENTS = 300
        const val SAVE_INTERVAL_MS = 60_000L
    }
}

/**
 * Limits automatic setting changes per battery and day. Every change is written to the battery's
 * flash memory, so automations must stay rare. Manual changes are not limited.
 */
class WriteGuard(private val maxPerDay: Int = 8) {
    private val counts = mutableMapOf<String, Pair<String, Int>>()

    fun allow(batteryId: String, nowMs: Long, zone: ZoneId): Boolean {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
        val (d, n) = counts[batteryId] ?: (day to 0)
        return d != day || n < maxPerDay
    }

    fun record(batteryId: String, nowMs: Long, zone: ZoneId) {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
        val (d, n) = counts[batteryId] ?: (day to 0)
        counts[batteryId] = day to (if (d == day) n + 1 else 1)
    }
}
