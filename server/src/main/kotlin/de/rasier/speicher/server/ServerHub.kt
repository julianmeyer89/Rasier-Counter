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
import kotlinx.coroutines.delay
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
    private var lastHistorySampleMs = 0L
    private var lastBatterySnapshot: SystemSnapshot? = null
    private val chargeRemainder = HashMap<String, Double>()
    private val dischargeRemainder = HashMap<String, Double>()
    private var ctStaleSinceMs = 0L   // 0 = CT002 healthy; else since when it went silent while batteries report
    private var lastReconnectMs = 0L
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
        val meterFresh = meterTs > lastMeterTimestampMs
        recordMeterFresh(meterFresh)
        if (meterTs > 0L) lastMeterTimestampMs = meterTs
        // Self-heal: if the CT002 goes silent while the batteries keep reporting, the CT's cloud
        // topic has likely rotated and we are subscribed to the old one. Mark since when, so the end
        // of this method can reconnect (a fresh login re-derives the current topic).
        if (meterFresh) ctStaleSinceMs = 0L
        else if (snapshot.batteries.isNotEmpty() && ctStaleSinceMs == 0L) ctStaleSinceMs = nowMs

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

        // Downsample the display history to ~1 point/min so it covers days even at a fast poll rate.
        // (Daily totals keep full poll resolution; this list only feeds the charts.)
        if (nowMs - lastHistorySampleMs >= HISTORY_SAMPLE_MS) {
            lastHistorySampleMs = nowMs
            history = (history + snapshot.toHistoryPoint()).filter { nowMs - it.t <= HISTORY_MS }.takeLast(MAX_HISTORY)
        }
        persist(nowMs)
        rebuild(nowMs)
        recoverMeterIfStuck(nowMs)
    }

    /**
     * Reconnects the link when the CT002 has been silent for a while although the batteries still
     * report. That pattern means the CT's cloud topic rotated and we are on the stale one; a fresh
     * login (close+connect) re-derives the current topic and re-subscribes. A cooldown avoids thrash.
     */
    private suspend fun recoverMeterIfStuck(nowMs: Long) {
        if (ctStaleSinceMs == 0L || nowMs - ctStaleSinceMs < CT_STALE_RECONNECT_MS) return
        if (nowMs - lastReconnectMs < RECONNECT_COOLDOWN_MS) return
        lastReconnectMs = nowMs
        addEvents(listOf("CT002 seit ${(nowMs - ctStaleSinceMs) / 60_000} min stumm (Speicher ok) – verbinde neu…"), nowMs)
        runCatching { link.close() }
        var ok = false
        for (attempt in 1..3) {
            ok = runCatching { link.connect() }.onFailure { log("Neuverbindung (Versuch $attempt) fehlgeschlagen: ${it.message}") }.isSuccess
            if (ok) break
            delay(3000L)
        }
        if (ok) {
            ctStaleSinceMs = 0L
            addEvents(listOf("Neu verbunden – CT002-Topic aufgefrischt."), nowMs)
        } else {
            addEvents(listOf("Neuverbindung fehlgeschlagen, neuer Versuch nach Cooldown."), nowMs)
        }
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

    /** Per-battery charge/discharge/PV/output for the window, bucketed like the statistics chart. */
    fun batteryStats(from: LocalDate, to: LocalDate, bucket: Bucket): BatteryStatsDto = when (bucket) {
        Bucket.FIVE_MIN -> batteryFiveMinute(from)
        else -> batteryDaily(from, to, monthly = bucket == Bucket.MONTH)
    }

    private fun batteryName(id: String) = lastBatteries[id]?.name ?: id.takeLast(6)

    /** Daily (or monthly) per-battery energy from the stored device day counters. */
    private fun batteryDaily(from: LocalDate, to: LocalDate, monthly: Boolean): BatteryStatsDto {
        val fromS = from.toString(); val toS = to.toString()
        val recent = deviceDays.filter { it.date in fromS..toS }
        val keyOf: (DeviceDay) -> String = { if (monthly) it.date.substring(0, 7) else it.date }
        val keys = recent.map(keyOf).distinct().sorted()
        val labels = keys.map { if (monthly) it.substring(5) + "." + it.substring(0, 4) else it.substring(8, 10) + "." + it.substring(5, 7) + "." }
        val series = link.batteryIds.mapNotNull { id ->
            val byKey = recent.filter { it.batteryId == id }.groupBy(keyOf)
            if (byKey.isEmpty()) return@mapNotNull null
            BatterySeriesDto(
                id = id, name = batteryName(id),
                charge = keys.map { k -> byKey[k]?.sumOf { it.chargeWh } ?: 0 },
                discharge = keys.map { k -> byKey[k]?.sumOf { it.dischargeWh } ?: 0 },
                pv = keys.map { k -> byKey[k]?.sumOf { it.pvWh } ?: 0 },
                output = keys.map { k -> byKey[k]?.sumOf { it.outputWh } ?: 0 },
                soc = keys.map { -1 },
            )
        }
        return BatteryStatsDto(if (monthly) Bucket.MONTH.name else Bucket.DAY.name, labels, series)
    }

    /**
     * 5-minute per-battery data of one day from the power history. Charge/discharge come from the
     * state-of-charge changes (times the battery capacity), which the history has reliably, so a day's
     * charging shows even when per-battery power history is sparse. PV/output come from the per-battery
     * power history (may be empty for older points). [soc] is the average state of charge per slot.
     */
    private fun batteryFiveMinute(day: LocalDate): BatteryStatsDto {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val slotMs = 5 * 60_000L; val slots = 288
        val ids = link.batteryIds
        val caps = ids.map { lastBatteries[it]?.capacityWh ?: 0 }
        val points = history.filter { it.t >= dayStart && it.t < dayStart + 86_400_000L }.sortedBy { it.t }
        val charge = Array(ids.size) { DoubleArray(slots) }
        val discharge = Array(ids.size) { DoubleArray(slots) }
        val pv = Array(ids.size) { DoubleArray(slots) }
        val output = Array(ids.size) { DoubleArray(slots) }
        val socSum = Array(ids.size) { DoubleArray(slots) }
        val socCnt = Array(ids.size) { IntArray(slots) }
        for (p in points) {
            val slot = ((p.t - dayStart) / slotMs).toInt().coerceIn(0, slots - 1)
            for (idx in ids.indices) {
                val s = p.soc.getOrNull(idx) ?: continue
                socSum[idx][slot] += s; socCnt[idx][slot]++
            }
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > 10 * 60_000L) continue
            val slot = ((a.t - dayStart) / slotMs).toInt().coerceIn(0, slots - 1)
            val hours = dt / 3_600_000.0
            for (idx in ids.indices) {
                // Charge/discharge from the SOC change times capacity (robust, always in the history).
                val sa = a.soc.getOrNull(idx); val sb = b.soc.getOrNull(idx)
                if (sa != null && sb != null && caps[idx] > 0) {
                    val dE = (sb - sa) / 100.0 * caps[idx]
                    if (dE >= 0) charge[idx][slot] += dE else discharge[idx][slot] += -dE
                }
                // PV/output from per-battery power history, if present.
                val apv = a.batPvW.getOrNull(idx); val bpv = b.batPvW.getOrNull(idx)
                if (apv != null && bpv != null) {
                    pv[idx][slot] += (apv + bpv) / 2.0 * hours
                    val aout = a.batOutW.getOrNull(idx) ?: 0; val bout = b.batOutW.getOrNull(idx) ?: 0
                    output[idx][slot] += (aout + bout) / 2.0 * hours
                }
            }
        }
        val labels = (0 until slots).map {
            val m = (it * slotMs / 60_000L).toInt(); "%02d:%02d".format(m / 60, m % 60)
        }
        val series = ids.mapIndexed { idx, id ->
            BatterySeriesDto(
                id = id, name = batteryName(id),
                charge = charge[idx].map { it.roundToInt() },
                discharge = discharge[idx].map { it.roundToInt() },
                pv = pv[idx].map { it.roundToInt() },
                output = output[idx].map { it.roundToInt() },
                soc = (0 until slots).map { if (socCnt[idx][it] > 0) (socSum[idx][it] / socCnt[idx][it]).roundToInt() else -1 },
            )
        }
        return BatteryStatsDto(Bucket.FIVE_MIN.name, labels, series)
    }

    enum class Bucket { FIVE_MIN, HOUR, DAY, MONTH }

    /**
     * Statistics for the window [from, to] (inclusive dates), aggregated into [bucket] buckets.
     * HOUR buckets come from the recent power history (only for the last ~2 days); DAY and MONTH
     * buckets from the stored daily totals. The total and the meter-based total match the window.
     */
    fun stats(from: LocalDate, to: LocalDate, bucket: Bucket, label: String): StatsDto {
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val buckets: List<Pair<String, DailyEnergy>> = when (bucket) {
            Bucket.FIVE_MIN -> fiveMinuteBuckets(from)
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

    /** 5-minute buckets of one day (288), integrated from the power history. Labels "HH:MM". */
    private fun fiveMinuteBuckets(day: LocalDate): List<Pair<String, DailyEnergy>> {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val slotMs = 5 * 60_000L
        val slots = 288
        val points = history.filter { it.t >= dayStart && it.t < dayStart + 86_400_000L }.sortedBy { it.t }
        val eff = config.system.inverterEfficiency
        val cap = config.system.inverterMaxW.toDouble()
        fun label(slot: Int): String {
            val minOfDay = (slot * slotMs / 60_000L).toInt()
            return "%02d:%02d".format(minOfDay / 60, minOfDay % 60)
        }
        val buckets = Array(slots) { DailyEnergy(date = label(it)) }
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > 10 * 60_000L) continue
            val slot = ((a.t - dayStart) / slotMs).toInt().coerceIn(0, slots - 1)
            val hours = dt / 3_600_000.0
            val pvW = (a.pvW + b.pvW) / 2.0
            val outW = (a.outW + b.outW) / 2.0
            val acW = (outW * eff).coerceAtMost(cap)
            val importW = (maxOf(a.gridW ?: 0, 0) + maxOf(b.gridW ?: 0, 0)) / 2.0
            val exportW = (maxOf(-(a.gridW ?: 0), 0) + maxOf(-(b.gridW ?: 0), 0)) / 2.0
            val houseW = (importW - exportW + acW).coerceAtLeast(0.0)
            val selfW = (acW - exportW).coerceAtLeast(0.0)
            buckets[slot] = buckets[slot] + DailyEnergy(
                date = buckets[slot].date, pvWh = pvW * hours, batteryOutputWh = outW * hours,
                inverterAcWh = acW * hours, gridImportWh = importW * hours, gridExportWh = exportW * hours,
                houseWh = houseW * hours, selfConsumedWh = selfW * hours,
            )
        }
        return buckets.map { it.date to it }
    }

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
        // Integrate per-battery charge/discharge (net PV-minus-output) since the previous snapshot.
        // Sub-Wh increments are carried in [chargeRemainder]/[dischargeRemainder] so fast polling adds up.
        val prev = lastBatterySnapshot
        lastBatterySnapshot = snapshot
        if (prev != null) {
            val dt = snapshot.timestampMs - prev.timestampMs
            if (dt in 1..(10 * 60_000L)) {
                val hours = dt / 3_600_000.0
                val prevById = prev.batteries.associateBy { it.id }
                for (b in snapshot.batteries) {
                    val a = prevById[b.id] ?: continue
                    val netA = a.totalPvW - a.totalOutputW
                    val netB = b.totalPvW - b.totalOutputW
                    val chargeW = (maxOf(netA, 0) + maxOf(netB, 0)) / 2.0
                    val dischargeW = (maxOf(-netA, 0) + maxOf(-netB, 0)) / 2.0
                    val cTot = (chargeRemainder[b.id] ?: 0.0) + chargeW * hours
                    val dTot = (dischargeRemainder[b.id] ?: 0.0) + dischargeW * hours
                    val cInt = cTot.toInt(); val dInt = dTot.toInt()
                    chargeRemainder[b.id] = cTot - cInt
                    dischargeRemainder[b.id] = dTot - dInt
                    days = MeterEvaluation.addBatteryEnergy(days, b.id, snapshot.timestampMs, cInt, dInt, zone)
                }
            }
        }
        if (days != deviceDays) {
            val cutoff = LocalDate.now(zone).minusDays(800).toString()
            deviceDays = days.filter { it.date >= cutoff }
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
        store.saveDeviceDays(deviceDays)
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
        const val HISTORY_MS = 8 * 24 * 3_600_000L
        const val MAX_HISTORY = 12_000
        const val HISTORY_SAMPLE_MS = 60_000L
        const val MAX_EVENTS = 500
        const val SAVE_INTERVAL_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val METER_QUALITY_WINDOW = 120
        const val CT_STALE_RECONNECT_MS = 180_000L   // reconnect if the CT002 is silent this long while batteries report
        const val RECONNECT_COOLDOWN_MS = 300_000L   // minimum gap between self-heal reconnects
    }
}
