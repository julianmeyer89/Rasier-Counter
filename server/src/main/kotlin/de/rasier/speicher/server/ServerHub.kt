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
import java.time.Instant
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
    private val settingsFile = File(dataDir, "user_settings.json")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
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
    val ct002PollIntervalSeconds: Double get() = config.ct002PollIntervalSeconds
    val batteryPollIntervalSeconds: Int get() = config.batteryPollIntervalSeconds

    private var lastBatteryPollMs = 0L
    private var lastMeterTimestampMs = 0L
    // Sliding window of recent CT002 polls: true = a fresh reading arrived. Drives the quality gauge.
    private val meterFreshWindow = ArrayDeque<Boolean>()

    init {
        // Dashboard settings override the config file so they survive restarts and updates.
        loadSavedSettings()
    }

    suspend fun poll(nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
        // The CT002 is polled every cycle; the batteries only when their (longer) interval has elapsed.
        val pollBattery = nowMs - lastBatteryPollMs >= config.batteryPollIntervalSeconds * 1000L
        val replyWaitMs = (config.ct002PollIntervalSeconds * 600).toLong().coerceIn(300L, 1200L)
        val snapshot = try {
            link.poll(nowMs, pollMeter = true, pollBattery = pollBattery, replyWaitMs = replyWaitMs)
        } catch (e: Exception) {
            log("Abfrage fehlgeschlagen: ${e.message}")
            addEvents(listOf("Abfrage fehlgeschlagen: ${e.message}"), nowMs)
            recordMeterFresh(false)
            rebuild(nowMs)
            return@withLock
        }
        if (pollBattery) lastBatteryPollMs = nowMs
        // A CT002 reading counts as "fresh" when its timestamp advanced since the last cycle.
        val meterTs = snapshot.meter?.timestampMs ?: 0L
        recordMeterFresh(meterTs > lastMeterTimestampMs)
        if (meterTs > 0L) lastMeterTimestampMs = meterTs

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
        applySettings(update)
        runCatching { settingsFile.writeText(json.encodeToString(SettingsDto.serializer(), update)) }
            .onFailure { log("Einstellungen speichern fehlgeschlagen: ${it.message}") }
        rebuild(System.currentTimeMillis())
    }

    /** Applies the tunable settings onto [config] and rebuilds the engine/integrator. */
    private fun applySettings(update: SettingsDto) {
        config = config.copy(
            tariff = Tariff(update.importCentPerKwh, update.feedInCentPerKwh, update.baseFeeEuroPerMonth),
            system = config.system.copy(
                inverterEfficiency = (update.inverterEfficiencyPercent / 100).coerceIn(0.5, 1.0),
                inverterMaxW = update.inverterMaxW,
            ),
            ct002PollIntervalSeconds = update.ct002PollIntervalSeconds.coerceIn(0.5, 3600.0),
            batteryPollIntervalSeconds = update.batteryPollIntervalSeconds.coerceIn(5, 3600),
            automationsMayWrite = update.automationsMayWrite,
            automation = config.automation.copy(
                balancer = config.automation.balancer.copy(enabled = update.balancerEnabled),
                calibration = config.automation.calibration.copy(intervalDays = update.calibrationIntervalDays.coerceIn(0, 365)),
            ),
        )
        engine = AutomationEngine(config.automation.copy(batteryIds = link.batteryIds))
        integrator = EnergyIntegrator(config.system, zone).also { it.restore(store.loadDays()) }
    }

    /** Restores settings saved from the dashboard, so they survive a restart/update. */
    private fun loadSavedSettings() {
        if (!settingsFile.exists()) return
        runCatching { applySettings(json.decodeFromString(SettingsDto.serializer(), settingsFile.readText())) }
            .onFailure { log("Gespeicherte Einstellungen nicht lesbar: ${it.message}") }
    }

    fun settingsDto() = SettingsDto(
        importCentPerKwh = config.tariff.importCentPerKwh,
        feedInCentPerKwh = config.tariff.feedInCentPerKwh,
        baseFeeEuroPerMonth = config.tariff.baseFeeEuroPerMonth,
        inverterEfficiencyPercent = config.system.inverterEfficiency * 100,
        inverterMaxW = config.system.inverterMaxW,
        ct002PollIntervalSeconds = config.ct002PollIntervalSeconds,
        batteryPollIntervalSeconds = config.batteryPollIntervalSeconds,
        automationsMayWrite = config.automationsMayWrite,
        balancerEnabled = config.automation.balancer.enabled,
        calibrationIntervalDays = config.automation.calibration.intervalDays,
    )

    fun history(hours: Int): HistoryDto {
        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        return HistoryDto(history.filter { it.t >= cutoff })
    }

    fun events(): List<EventDto> = events.takeLast(100).reversed().map { EventDto(it.t, it.text) }

    enum class Bucket { HOUR, DAY, MONTH }

    /**
     * Statistics for the window [from, to] (inclusive dates), aggregated into [bucket] buckets.
     * HOUR buckets come from the recent power history (only for the last ~2 days); DAY and MONTH
     * buckets from the stored daily totals. The total and the meter-based total match the window.
     */
    fun stats(from: LocalDate, to: LocalDate, bucket: Bucket, label: String): StatsDto {
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val buckets: List<Pair<String, DailyEnergy>> = when (bucket) {
            Bucket.HOUR -> hourlyBuckets(from)
            // Labels in German date format: DAY -> "TT.MM.", MONTH -> "MM.JJJJ".
            Bucket.DAY -> daysIn(from, to).map { (it.date.substring(8, 10) + "." + it.date.substring(5, 7) + ".") to it }
            Bucket.MONTH -> daysIn(from, to).groupBy { it.date.substring(0, 7) }
                .toSortedMap().map { (ym, list) -> (ym.substring(5) + "." + ym.substring(0, 4)) to list.sum(ym) }
        }
        val total = buckets.map { it.second }.fold(DailyEnergy(date = label)) { a, b -> a + b }.copy(date = label)

        val allPeriods = MeterEvaluation.periods(meterEntries, deviceDays, config.system.inverterEfficiency, zone)
        val inWindow = allPeriods.filter { it.toMs > fromMs && it.fromMs < toMs }.reversed()
        val meterTotal = meterTotalFor(fromMs, toMs)

        return StatsDto(
            label = label,
            fromMs = fromMs,
            toMs = toMs,
            bucket = bucket.name,
            total = EnergyDto.from(total, config.tariff),
            buckets = buckets.map { BucketDto(it.first, EnergyDto.from(it.second, config.tariff)) },
            meterPeriods = inWindow.map {
                MeterPeriodDto(it.fromMs, it.toMs, it.importKwh, it.exportKwh, it.houseKwh, it.selfConsumedKwh, it.savingsEuro(config.tariff), it.importPerWeekKwh)
            },
            meterTotal = meterTotal?.let {
                MeterPeriodDto(it.fromMs, it.toMs, it.importKwh, it.exportKwh, it.houseKwh, it.selfConsumedKwh, it.savingsEuro(config.tariff), it.importPerWeekKwh)
            },
        )
    }

    private fun daysIn(from: LocalDate, to: LocalDate): List<DailyEnergy> =
        integrator.totals.values.filter { it.date >= from.toString() && it.date <= to.toString() }.sortedBy { it.date }

    /** Hourly buckets of one day, integrated from the power history (only available for recent days). */
    private fun hourlyBuckets(day: LocalDate): List<Pair<String, DailyEnergy>> {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val points = history.filter { it.t >= dayStart && it.t < dayStart + 86_400_000L }.sortedBy { it.t }
        val eff = config.system.inverterEfficiency
        val cap = config.system.inverterMaxW.toDouble()
        val buckets = Array(24) { DailyEnergy(date = "%02d".format(it)) }
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > 10 * 60_000L) continue
            val hour = Instant.ofEpochMilli(a.t).atZone(zone).hour
            val hours = dt / 3_600_000.0
            val pvW = (a.pvW + b.pvW) / 2.0
            val outW = (a.outW + b.outW) / 2.0
            val acW = (outW * eff).coerceAtMost(cap)
            val importW = (maxOf(a.gridW ?: 0, 0) + maxOf(b.gridW ?: 0, 0)) / 2.0
            val exportW = (maxOf(-(a.gridW ?: 0), 0) + maxOf(-(b.gridW ?: 0), 0)) / 2.0
            val houseW = (importW - exportW + acW).coerceAtLeast(0.0)
            val selfW = (acW - exportW).coerceAtLeast(0.0)
            buckets[hour] = buckets[hour] + DailyEnergy(
                date = buckets[hour].date, pvWh = pvW * hours, batteryOutputWh = outW * hours,
                inverterAcWh = acW * hours, gridImportWh = importW * hours, gridExportWh = exportW * hours,
                houseWh = houseW * hours, selfConsumedWh = selfW * hours,
            )
        }
        return buckets.mapIndexed { h, e -> "${"%02d".format(h)}h" to e.copy(date = "${"%02d".format(h)}h") }
    }

    /** Consumption between the first and last meter reading within the window, or null if < 2. */
    private fun meterTotalFor(fromMs: Long, toMs: Long): de.rasier.speicher.energy.MeterPeriod? {
        val inWindow = meterEntries.filter { it.timestampMs >= fromMs - DAY_MS && it.timestampMs <= toMs + DAY_MS }
            .sortedBy { it.timestampMs }
        if (inWindow.size < 2) return null
        return MeterEvaluation.periods(listOf(inWindow.first(), inWindow.last()), deviceDays, config.system.inverterEfficiency, zone).firstOrNull()
    }

    // ---- internals ----

    private fun recordMeterFresh(fresh: Boolean) {
        meterFreshWindow.addLast(fresh)
        while (meterFreshWindow.size > METER_QUALITY_WINDOW) meterFreshWindow.removeFirst()
    }

    /** Share of recent CT002 polls that returned fresh data, or null until we have a few samples. */
    private fun meterFreshPercent(): Int? {
        if (meterFreshWindow.size < 5) return null
        return (meterFreshWindow.count { it } * 100 / meterFreshWindow.size)
    }

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
            lastMeterReadingMs = meterEntries.maxOfOrNull { it.timestampMs },
            meterFreshPercent = meterFreshPercent(),
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
        const val DAY_MS = 86_400_000L
        const val METER_QUALITY_WINDOW = 120
    }
}
