package de.rasier.speicher.server

import de.rasier.speicher.control.Alert
import de.rasier.speicher.control.AutomationEngine
import de.rasier.speicher.control.AutomationSettings
import de.rasier.speicher.control.AutomationState
import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.control.GroupState
import de.rasier.speicher.energy.DailyEnergy
import de.rasier.speicher.energy.DeviceDay
import de.rasier.speicher.energy.EnergyIntegrator
import de.rasier.speicher.energy.MeterEntry
import de.rasier.speicher.energy.MeterEvaluation
import de.rasier.speicher.energy.Tariff
import de.rasier.speicher.energy.sum
import de.rasier.speicher.hub.WriteGuard
import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.MeterReading
import de.rasier.speicher.model.SystemSnapshot
import de.rasier.speicher.settings.EventEntry
import de.rasier.speicher.settings.FileStore
import de.rasier.speicher.settings.HistoryPoint
import de.rasier.speicher.settings.toHistoryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Orchestrates the server: polls the device link, runs the shared core logic (statistics,
 * calibration, balancing, monitoring), persists everything, and exposes a snapshot for the web API.
 */
class ServerHub(
    private var config: ServerConfig,
    private val link: DeviceLink,
    dataDir: File,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val log: (String) -> Unit = { println(it) },
) {
    private val mutex = Mutex()
    private val store = FileStore(dataDir)
    private var integrator = EnergyIntegrator(config.system, zone).also { it.restore(store.loadDays()) }
    private var engine = AutomationEngine(config.automation.copy(batteryIds = link.batteryIds))
    private val writeGuard = WriteGuard()

    private var autoState: AutomationState = store.loadAutomation()
    private var deviceDays: List<DeviceDay> = store.loadDeviceDays()
    private var meterEntries: List<MeterEntry> = store.loadMeterEntries()
    private var history: List<HistoryPoint> = store.loadHistory()
    private var events: List<EventEntry> = store.loadEvents()
    private var alerts: List<Alert> = store.loadAlerts()
    private val lastBatteries = linkedMapOf<String, BatteryState>()
    private var lastMeter: MeterReading? = null
    private var lastSnapshot: SystemSnapshot? = null
    private var suggestions: List<BatteryCommand> = emptyList()
    private var lastSaveMs = 0L

    private val _state = MutableStateFlow(emptyStateDto())
    val state: StateFlow<StateDto> = _state

    val tariff: Tariff get() = config.tariff
    val pollIntervalSeconds: Int get() = config.pollIntervalSeconds

    suspend fun poll(nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
        val snapshot = try {
            link.poll(nowMs)
        } catch (e: Exception) {
            log("Abfrage fehlgeschlagen: ${e.message}")
            addEvents(listOf("Abfrage fehlgeschlagen: ${e.message}"), nowMs)
            rebuild(nowMs)
            return@withLock
        }
        snapshot.batteries.forEach { lastBatteries[it.id] = it }
        snapshot.meter?.let { lastMeter = it }
        lastSnapshot = snapshot

        integrator.add(snapshot)
        if (!config.demo) recordDeviceDays(snapshot)

        val tick = engine.tick(autoState, snapshot, nowMs)
        autoState = tick.state
        applyCommands(tick.commands, nowMs, automatic = true)
        alerts = tick.alerts
        addEvents(tick.events, nowMs)
        store.saveAutomation(autoState)
        store.saveAlerts(alerts)

        history = (history + snapshot.toHistoryPoint()).filter { nowMs - it.t <= HISTORY_MS }.takeLast(MAX_HISTORY)
        persist(nowMs)
        rebuild(nowMs)
    }

    suspend fun manualCommand(command: BatteryCommand): Boolean = mutex.withLock {
        val ok = runCatching { link.send(command) }.getOrDefault(false)
        addEvents(listOf((if (ok) "Gesendet: " else "Fehlgeschlagen: ") + describe(command)), System.currentTimeMillis())
        rebuild(System.currentTimeMillis())
        ok
    }

    suspend fun startCalibration(batteryIds: List<String>) = mutex.withLock {
        val now = System.currentTimeMillis()
        val ids = batteryIds.ifEmpty { link.batteryIds }
        val r = engine.startCalibration(autoState, ids, now)
        autoState = r.state
        applyCommands(r.commands, now, automatic = true)
        addEvents(r.events, now)
        store.saveAutomation(autoState)
        rebuild(now)
    }

    suspend fun abortCalibration() = mutex.withLock {
        val now = System.currentTimeMillis()
        val r = engine.abortCalibration(autoState, now)
        autoState = r.state
        applyCommands(r.commands, now, automatic = true)
        addEvents(r.events, now)
        store.saveAutomation(autoState)
        rebuild(now)
    }

    suspend fun addMeterEntry(entry: MeterEntry) = mutex.withLock {
        meterEntries = (meterEntries + entry).sortedBy { it.timestampMs }
        store.saveMeterEntries(meterEntries)
        rebuild(System.currentTimeMillis())
    }

    suspend fun updateSettings(update: SettingsDto) = mutex.withLock {
        config = config.copy(
            tariff = Tariff(update.importCentPerKwh, update.feedInCentPerKwh, update.baseFeeEuroPerMonth),
            system = config.system.copy(
                inverterEfficiency = (update.inverterEfficiencyPercent / 100).coerceIn(0.5, 1.0),
                inverterMaxW = update.inverterMaxW,
            ),
            pollIntervalSeconds = update.pollIntervalSeconds.coerceIn(10, 3600),
            automationsMayWrite = update.automationsMayWrite,
            automation = config.automation.copy(
                balancer = config.automation.balancer.copy(enabled = update.balancerEnabled),
                calibration = config.automation.calibration.copy(intervalDays = update.calibrationIntervalDays.coerceIn(0, 365)),
            ),
        )
        engine = AutomationEngine(config.automation.copy(batteryIds = link.batteryIds))
        integrator = EnergyIntegrator(config.system, zone).also { it.restore(store.loadDays()) }
        rebuild(System.currentTimeMillis())
    }

    fun settingsDto() = SettingsDto(
        importCentPerKwh = config.tariff.importCentPerKwh,
        feedInCentPerKwh = config.tariff.feedInCentPerKwh,
        baseFeeEuroPerMonth = config.tariff.baseFeeEuroPerMonth,
        inverterEfficiencyPercent = config.system.inverterEfficiency * 100,
        inverterMaxW = config.system.inverterMaxW,
        pollIntervalSeconds = config.pollIntervalSeconds,
        automationsMayWrite = config.automationsMayWrite,
        balancerEnabled = config.automation.balancer.enabled,
        calibrationIntervalDays = config.automation.calibration.intervalDays,
    )

    fun history(hours: Int): HistoryDto {
        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        return HistoryDto(history.filter { it.t >= cutoff })
    }

    fun events(): List<EventDto> = events.takeLast(100).reversed().map { EventDto(it.t, it.text) }

    fun stats(periodDays: Long, label: String): StatsDto {
        val from = LocalDate.now(zone).minusDays(periodDays - 1).toString()
        val days = integrator.totals.values.filter { it.date >= from }.sortedBy { it.date }
        val total = days.sum(label)
        val periods = MeterEvaluation.periods(meterEntries, deviceDays, config.system.inverterEfficiency, zone).reversed()
        return StatsDto(
            period = label,
            total = EnergyDto.from(total, config.tariff),
            days = days.map { EnergyDto.from(it, config.tariff) },
            meterPeriods = periods.map {
                MeterPeriodDto(it.fromMs, it.toMs, it.importKwh, it.exportKwh, it.houseKwh, it.selfConsumedKwh, it.savingsEuro(config.tariff), it.importPerWeekKwh)
            },
        )
    }

    // ---- internals ----

    private fun recordDeviceDays(snapshot: SystemSnapshot) {
        var days = deviceDays
        for (b in snapshot.batteries) {
            days = MeterEvaluation.updateDeviceDays(days, b.id, b.timestampMs, b.dailyPvWh, b.dailyOutputWh, zone)
        }
        if (days != deviceDays) {
            val cutoff = LocalDate.now(zone).minusDays(800).toString()
            deviceDays = days.filter { it.date >= cutoff }
            store.saveDeviceDays(deviceDays)
        }
    }

    private suspend fun applyCommands(commands: List<BatteryCommand>, nowMs: Long, automatic: Boolean) {
        if (commands.isEmpty()) {
            if (automatic) suggestions = emptyList()
            return
        }
        val newSuggestions = mutableListOf<BatteryCommand>()
        for (c in commands) {
            if (automatic && !config.automationsMayWrite) {
                newSuggestions += c
                addEvents(listOf("Vorschlag (Schreiben aus): ${describe(c)}"), nowMs)
                continue
            }
            if (automatic && !writeGuard.allow(c.batteryId, nowMs, zone)) {
                addEvents(listOf("Schreiblimit erreicht, übersprungen: ${describe(c)}"), nowMs)
                continue
            }
            if (automatic) writeGuard.record(c.batteryId, nowMs, zone)
            val ok = runCatching { link.send(c) }.getOrDefault(false)
            addEvents(listOf((if (ok) "Automatik: " else "Automatik fehlgeschlagen: ") + describe(c)), nowMs)
        }
        if (automatic) suggestions = newSuggestions
    }

    private fun addEvents(texts: List<String>, nowMs: Long) {
        if (texts.isEmpty()) return
        texts.forEach(log)
        events = (events + texts.map { EventEntry(nowMs, it) }).takeLast(MAX_EVENTS)
        store.saveEvents(events)
    }

    private fun persist(nowMs: Long) {
        if (nowMs - lastSaveMs < SAVE_INTERVAL_MS) return
        lastSaveMs = nowMs
        val cutoff = LocalDate.now(zone).minusDays(730).toString()
        store.saveDays(integrator.totals.values.filter { it.date >= cutoff })
        store.saveHistory(history)
    }

    private fun describe(c: BatteryCommand): String {
        val name = lastBatteries[c.batteryId]?.name ?: c.batteryId
        return when (c) {
            is BatteryCommand.SetDod -> "$name: Entladetiefe ${c.percent} %"
            is BatteryCommand.SetChargeMode -> "$name: Lademodus ${c.mode}"
            is BatteryCommand.SetAdaptiveMode -> "$name: adaptiver Modus ${if (c.enabled) "an" else "aus"}"
        }
    }

    private fun today(): DailyEnergy? {
        val today = LocalDate.now(zone).toString()
        return integrator.totals[today]
    }

    private fun rebuild(nowMs: Long) {
        val batteries = lastBatteries.values.sortedBy { it.name }
        val group = GroupState(batteries)
        val meter = lastMeter
        val acW = (group.outputW * config.system.inverterEfficiency).roundToInt()
        val calibration = autoState.calibration?.let { run ->
            CalibrationDto(
                running = !run.finished,
                currentBatteryId = run.currentBatteryId,
                phase = phaseLabel(run.phase),
                index = run.index,
                total = run.queue.size,
                startedMs = run.batteryStartedMs,
                lastFinishedMs = autoState.lastCalibrationFinishedMs,
                results = run.results.map { CalibrationResultDto(it.batteryId, it.success, it.message, it.finishedMs) },
            )
        }
        _state.value = StateDto(
            timestampMs = nowMs,
            mode = if (config.demo) "demo" else "live",
            automationsMayWrite = config.automationsMayWrite,
            link = LinkDto(link.status.connected, link.status.detail, link.status.lastDataMs),
            group = GroupDto(
                socPercent = group.socPercent,
                capacityWh = group.totalCapacityWh,
                storedWh = group.storedWh,
                pvW = group.pvW,
                outputW = group.outputW,
                inverterAcW = acW,
                chargeW = group.chargeW,
                gridW = meter?.totalW,
                houseW = meter?.let { (it.totalW + acW).coerceAtLeast(0) },
                socSpread = group.socSpread,
                heldBatteryId = autoState.balancer.heldBatteryId,
            ),
            meter = meter?.let { MeterDto(it.phaseW, it.totalW) },
            batteries = batteries.map {
                BatteryDto(
                    id = it.id, name = it.name, socPercent = it.socPercent, capacityWh = it.capacityWh,
                    pvInputW = it.pvInputW, outputW = it.outputW, outputEnabled = it.outputEnabled,
                    dodPercent = it.dodPercent, chargeMode = it.chargeMode.name, adaptiveMode = it.adaptiveMode,
                    dailyPvWh = it.dailyPvWh, dailyOutputWh = it.dailyOutputWh, temperatureC = it.temperatureC,
                    firmware = it.firmware, ageSeconds = ((nowMs - it.timestampMs) / 1000).coerceAtLeast(0),
                )
            },
            today = today()?.let { EnergyDto.from(it, config.tariff) },
            alerts = alerts.map { AlertDto.from(it) },
            calibration = calibration,
            suggestions = suggestions.map { toCommandDto(it) },
        )
    }

    private fun toCommandDto(c: BatteryCommand) = when (c) {
        is BatteryCommand.SetDod -> CommandDto(c.batteryId, "dod", c.percent)
        is BatteryCommand.SetChargeMode -> CommandDto(c.batteryId, "chargeMode", if (c.mode == de.rasier.speicher.model.ChargeMode.CHARGE_THEN_DISCHARGE) 1 else 0)
        is BatteryCommand.SetAdaptiveMode -> CommandDto(c.batteryId, "adaptive", if (c.enabled) 1 else 0)
    }

    private fun emptyStateDto() = StateDto(
        timestampMs = 0, mode = if (config.demo) "demo" else "live", automationsMayWrite = config.automationsMayWrite,
        link = LinkDto(false, "startet…", null),
        group = GroupDto(null, null, null, 0, 0, 0, 0, null, null, 0, null),
        meter = null, batteries = emptyList(), today = null, alerts = emptyList(), calibration = null, suggestions = emptyList(),
    )

    companion object {
        const val HISTORY_MS = 48 * 3_600_000L
        const val MAX_HISTORY = 6_000
        const val MAX_EVENTS = 500
        const val SAVE_INTERVAL_MS = 60_000L
    }
}
