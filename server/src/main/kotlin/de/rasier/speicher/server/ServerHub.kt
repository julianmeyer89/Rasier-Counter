package de.rasier.speicher.server

import de.rasier.speicher.control.Alert
import de.rasier.speicher.control.AutomationEngine
import de.rasier.speicher.control.AutomationSettings
import de.rasier.speicher.control.AutomationState
import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.control.appliedTo
import de.rasier.speicher.control.CalibrationPhase
import de.rasier.speicher.control.CalibrationRun
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val store = FileStore(dataDir, log)
    private val settingsFile = File(dataDir, "user_settings.json")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private var integrator = EnergyIntegrator(config.system, zone).also { it.restore(store.loadDays()) }
    private var engine = AutomationEngine(config.automation.copy(batteryIds = link.batteryIds), zone)
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
    /** Time zone used for day boundaries and schedules; the web layer labels dates with it. */
    val timeZone: ZoneId get() = zone
    /** Ids of the batteries behind the link; commands for anything else are refused. */
    val batteryIds: List<String> get() = link.batteryIds
    val ct002PollIntervalSeconds: Double get() = config.ct002PollIntervalSeconds
    val batteryPollIntervalSeconds: Int get() = config.batteryPollIntervalSeconds

    private var lastBatteryPollMs = 0L
    private var lastMeterTimestampMs = 0L
    private var lastHistorySampleMs = 0L
    // Running sums since the last history point: the stored value is the minute's average instead of a
    // single instant, so the statistics rebuilt from it match the live integration.
    private var accGrid = 0.0
    private var accGridN = 0
    private var accGridMin = 0
    private var accGridMax = 0
    private var accMeterPhase: List<Int> = emptyList()
    private val accBatPv = linkedMapOf<String, Double>()
    private val accBatOut = linkedMapOf<String, Double>()
    private val accBatN = linkedMapOf<String, Int>()
    private val accSoc = linkedMapOf<String, Int>()
    private var lastBatterySnapshot: SystemSnapshot? = null
    private val chargeRemainder = HashMap<String, Double>()
    private val dischargeRemainder = HashMap<String, Double>()
    private val deliveredRemainder = HashMap<String, Double>()
    private val pvRemainder = HashMap<String, Double>()
    // Today's idle self-discharge, per battery, for the "W" estimate and anomaly alert; reset at midnight.
    private var selfDisDay = ""
    private val idleHoursToday = HashMap<String, Double>()
    private val idleSelfWhToday = HashMap<String, Double>()
    private var ctStaleSinceMs = 0L   // 0 = CT002 healthy; else since when it went silent while batteries report
    private var lastReconnectMs = 0L
    // While set in the future, CT002 poll requests are suppressed (during an Auto/Manual switch) so the
    // mode-change command is not drowned out by meter polls. Cached CT readings still flow through.
    private var ctPauseUntilMs = 0L
    // Background scope for fire-and-forget push notifications (never blocks the poll loop).
    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Repeated failed polls mean the link is dead. The self-heal below only runs after a SUCCESSFUL
    // poll, so the failure path has to rebuild the connection itself.
    private var pollFailures = 0
    private var lastFailureEventMs = 0L
    private var lastRelinkMs = 0L
    private var linkDownPushed = false
    private var meterDownPushed = false
    private var linkDownSinceMs = 0L  // 0 = cloud link healthy; else since when no device data arrived
    private var meterRecoveryTries = 0
    // Only write when something actually changed: at a 1.7 s poll rate this used to be ~50.000
    // pointless writes a day.
    private var savedAutoState: AutomationState? = null
    private var savedAlerts: List<Alert>? = null
    private var lastHistorySaveMs = 0L
    private var lastAutoSaveMs = 0L
    private val pushedAlertKeys = mutableSetOf<String>()
    /** Last capacity a battery reported; `kn / soc` is undefined at 0 %. */
    private val lastKnownCapacity = linkedMapOf<String, Int>()
    // Settings that were sent but not yet confirmed by the device, plus the warnings about those
    // that never arrived (kept apart from the monitor alerts, which are replaced on every tick).
    private val pending = mutableListOf<PendingWrite>()
    private var writeAlerts: List<Alert> = emptyList()
    // Sliding window of recent CT002 polls: true = a fresh reading arrived. Drives the quality gauge.
    private val meterFreshWindow = ArrayDeque<Boolean>()
    // Per-battery reachability, so a reboot (device stops answering, then returns) is visible in the log.
    private val batteryReachable = HashMap<String, Boolean>()
    // Capacity-measurement state: per-battery current clean-discharge run, and the persisted samples.
    private val capWinActive = HashMap<String, Boolean>()
    private val capWinPeak = HashMap<String, Int>()
    private val capWinTrough = HashMap<String, Int>()
    private val capWinLast = HashMap<String, Int>()
    private val capWinEnergy = HashMap<String, Double>()
    private val capacitySamplesFile = File(dataDir, "capacity_samples.json")
    private var capacitySamples: List<CapacitySample> = emptyList()

    init {
        // Dashboard settings override the config file so they survive restarts and updates.
        loadSavedSettings()
        runCatching {
            if (capacitySamplesFile.exists())
                capacitySamples = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CapacitySample.serializer()), capacitySamplesFile.readText())
        }.onFailure { log("Kapazitäts-Messungen nicht lesbar: ${it.message}") }
        // Losing the stored state silently would be the worst case during a calibration, so say it.
        val lost = store.unreadable
        if (lost.isNotEmpty()) {
            val now = System.currentTimeMillis()
            addEvents(lost.map { "ACHTUNG: $it war nicht lesbar – Kopie als $it.unlesbar gesichert, es gelten Standardwerte." }, now)
            writeAlerts = writeAlerts + Alert(
                "store-unreadable", Alert.Severity.WARNING, "Gespeicherte Daten nicht lesbar",
                "${lost.joinToString()} konnte(n) nicht gelesen werden. Die Inhalte liegen als .unlesbar daneben. " +
                    "Lief eine Kalibrierung, prüfe bitte Entladetiefe und Lademodus der Speicher von Hand.", now,
            )
            pushNtfy("Gespeicherte Daten nicht lesbar", lost.joinToString(), "high", "warning")
        }
    }

    /** A setting that was sent and is being checked against what the device reports. */
    private class PendingWrite(val command: BatteryCommand, var sentMs: Long, var attempts: Int)

    suspend fun poll(nowMs: Long = System.currentTimeMillis()) = mutex.withLock {
        // The CT002 is polled every cycle; the batteries only when their (longer) interval has elapsed.
        val pollBattery = nowMs - lastBatteryPollMs >= config.batteryPollIntervalSeconds * 1000L
        val ctPaused = nowMs < ctPauseUntilMs
        val replyWaitMs = (config.ct002PollIntervalSeconds * 600).toLong().coerceIn(300L, 1200L)
        val snapshot = try {
            link.poll(nowMs, pollMeter = !ctPaused, pollBattery = pollBattery, replyWaitMs = replyWaitMs)
        } catch (e: Exception) {
            pollFailures++
            log("Abfrage fehlgeschlagen: ${e.message}")
            // One event per minute, not one per cycle: at a 1.7 s rate the 500-entry log would be
            // overwritten within minutes and bury the cause.
            if (nowMs - lastFailureEventMs >= FAILURE_EVENT_MS) {
                lastFailureEventMs = nowMs
                addEvents(listOf("Abfrage fehlgeschlagen (${pollFailures}x): ${e.message}"), nowMs)
            }
            recordMeterFresh(false)
            rebuild(nowMs)
            relinkIfDead(nowMs)
            return@withLock
        }
        if (pollFailures > 0) {
            addEvents(listOf("Abfrage läuft wieder (nach $pollFailures Fehlversuchen)."), nowMs)
            pollFailures = 0
            linkDownPushed = false
        }
        if (pollBattery) lastBatteryPollMs = nowMs
        // A CT002 reading counts as "fresh" when its timestamp advanced since the last cycle.
        val meterTs = snapshot.meter?.timestampMs ?: 0L
        val meterFresh = meterTs > lastMeterTimestampMs
        // A deliberate CT002 pause (mode switch) must not count against the quality gauge or trip the
        // self-heal reconnect: skip the freshness/stale accounting while paused.
        if (!ctPaused) {
            recordMeterFresh(meterFresh)
            // Self-heal: if the CT002 goes silent while the batteries keep reporting, the CT's cloud
            // topic has likely rotated and we are subscribed to the old one. Mark since when, so the end
            // of this method can reconnect (a fresh login re-derives the current topic).
            if (meterFresh) { ctStaleSinceMs = 0L; meterRecoveryTries = 0 }
            else if (snapshot.batteries.isNotEmpty() && ctStaleSinceMs == 0L) ctStaleSinceMs = nowMs
        }
        if (meterTs > 0L) lastMeterTimestampMs = meterTs

        // Judge the cloud link by the data it delivers, not by whether a poll threw. relinkIfDead only
        // counts thrown polls, so a link that answers but carries nothing - reporting not connected with
        // no device frames at all - raised no alert and sent no push. That is how the system could sit
        // silent for nine hours while every other condition had a warning.
        val dataMs = link.status.lastDataMs
        val linkHealthy = link.status.connected && dataMs != null && nowMs - dataMs < LINK_STALE_MS
        if (linkHealthy) {
            if (linkDownPushed) {
                pushNtfy("Cloud wieder verbunden", "Der Server bekommt wieder Daten aus der Marstek-Cloud.", "default", "white_check_mark")
                addEvents(listOf("Cloud-Verbindung wieder da."), nowMs)
            }
            linkDownSinceMs = 0L
            linkDownPushed = false
        } else if (linkDownSinceMs == 0L) {
            linkDownSinceMs = nowMs
        }

        snapshot.batteries.forEach { b ->
            b.capacityWh?.let { lastKnownCapacity[b.id] = it }
            lastBatteries[b.id] = if (b.capacityWh == null) b.copy(capacityWh = lastKnownCapacity[b.id]) else b
        }
        snapshot.meter?.let { lastMeter = it }
        lastSnapshot = snapshot
        checkReachability(nowMs)
        confirmPending(nowMs)

        integrator.add(snapshot)
        if (!config.demo) recordDeviceDays(snapshot)

        val prevCal = autoState.calibration
        val tick = engine.tick(autoState, snapshot, nowMs)
        // With writing disabled nothing is actually sent, so the automation must not move on as if it
        // had been: only the bookkeeping is kept, and the same commands are offered again next cycle
        // (which is also what keeps the suggestions on screen instead of flashing by once).
        var next = if (config.automationsMayWrite) tick.state
        else autoState.copy(lastSeenMs = tick.state.lastSeenMs, names = tick.state.names, monitor = tick.state.monitor)
        var commands = tick.commands
        // A scheduled run must pass the same checks as a manual one, or it would block the battery for
        // days in winter and end in a stop.
        val startedBySchedule = (prevCal == null || prevCal.finished) && next.calibration?.finished == false
        if (startedBySchedule) {
            val blockers = calibrationBlockers(next.calibration!!.queue)
            if (blockers.isNotEmpty()) {
                addEvents(listOf("Geplante Kalibrierung nicht gestartet: ${blockers.joinToString("; ")}"), nowMs)
                pushNtfy("Kalibrierung nicht gestartet", blockers.joinToString("; "), "default", "warning")
                next = next.copy(calibration = null, lastCalibrationFinishedMs = nowMs)
                commands = emptyList()
            }
        }
        autoState = next
        applyCommands(commands, nowMs, automatic = true)
        alerts = tick.alerts
        addEvents(tick.events, nowMs)
        notifyCalibration(prevCal, autoState.calibration, nowMs)
        pushNewAlerts(alerts)
        saveStateIfChanged(nowMs)

        // Display history: ~1 point/min, but averaged over every poll of that minute.
        accumulateHistory(snapshot)
        if (nowMs - lastHistorySampleMs >= HISTORY_SAMPLE_MS) {
            lastHistorySampleMs = nowMs
            flushHistory(nowMs)
        }
        persist(nowMs)
        rebuild(nowMs)
        recoverMeterIfStuck(nowMs)
        relinkIfDead(nowMs)
    }

    /**
     * Reconnects the link when the CT002 has been silent for a while although the batteries still
     * report. That pattern means the CT's cloud topic rotated and we are on the stale one; a fresh
     * login (close+connect) re-derives the current topic and re-subscribes. A cooldown avoids thrash.
     */
    private suspend fun recoverMeterIfStuck(nowMs: Long) {
        if (ctStaleSinceMs == 0L || nowMs - ctStaleSinceMs < CT_STALE_RECONNECT_MS) return
        if (nowMs - lastReconnectMs < RECONNECT_COOLDOWN_MS) return
        // A few attempts per silent spell; after that the warning and the push have to do the work
        // instead of reconnecting every few minutes for hours.
        if (meterRecoveryTries >= METER_RECOVERY_TRIES) return
        meterRecoveryTries++
        lastReconnectMs = nowMs
        addEvents(listOf("CT002 seit ${(nowMs - ctStaleSinceMs) / 60_000} min stumm (Speicher ok) – verbinde neu…"), nowMs)
        if (relink()) {
            // Reconnecting does NOT mean data flows again: only a fresh reading clears the silence (see
            // poll). Otherwise a successful reconnect would reset the clock every few minutes and the
            // "no meter data" warning could never reach its threshold.
            addEvents(listOf("Neu verbunden – warte auf CT002-Daten."), nowMs)
        } else {
            // The link is closed now, so every poll fails from here on: relinkIfDead keeps trying.
            addEvents(listOf("Neuverbindung fehlgeschlagen, wird weiter versucht."), nowMs)
        }
    }

    /**
     * Rebuilds the link after repeated failed polls. Without this a dropped connection (or a failed
     * self-heal, which leaves the link closed) would silence the server until a manual restart.
     */
    private suspend fun relinkIfDead(nowMs: Long) {
        // Two separate reasons, because the second one went unhandled for five hours: polls that throw,
        // and polls that succeed while no device data arrives at all. The link can report itself alive
        // and deliver nothing - a rejected session, a rotated certificate - and in that state neither
        // this nor recoverMeterIfStuck used to fire (the latter needs batteries to be reporting).
        val starvedMs = if (linkDownSinceMs == 0L) 0L else nowMs - linkDownSinceMs
        val reason = when {
            pollFailures >= RELINK_AFTER_FAILURES -> "antwortet seit $pollFailures Abfragen nicht"
            starvedMs >= LINK_RELINK_MS -> "liefert seit ${starvedMs / 60_000} min keine Gerätedaten"
            else -> return
        }
        if (nowMs - lastRelinkMs < RELINK_RETRY_MS) return
        lastRelinkMs = nowMs
        addEvents(listOf("Verbindung $reason – baue sie neu auf…"), nowMs)
        if (relink()) {
            addEvents(listOf("Verbindung wiederhergestellt."), nowMs)
        } else {
            addEvents(listOf("Neuaufbau fehlgeschlagen, nächster Versuch in ${RELINK_RETRY_MS / 60_000} min."), nowMs)
            if (!linkDownPushed) {
                linkDownPushed = true
                pushNtfy("Verbindung gestoert", "Der Server erreicht die Marstek-Cloud nicht ($reason).", "high", "warning")
            }
        }
        rebuild(nowMs)
    }

    /** Closes and re-opens the link, a few attempts apart. True when it is usable again. */
    private suspend fun relink(): Boolean {
        runCatching { link.close() }
        for (attempt in 1..3) {
            if (runCatching { link.connect() }.onFailure { log("Neuverbindung (Versuch $attempt) fehlgeschlagen: ${it.message}") }.isSuccess) return true
            delay(3000L)
        }
        return false
    }

    /**
     * Cloud commands are fire-and-forget (MQTT QoS 0): a lost command looks exactly like a successful
     * one. Every sent setting is therefore compared with what the device reports and re-sent until it
     * matches, so the calibration can no longer continue on an assumption that never came true.
     */
    private suspend fun confirmPending(nowMs: Long) {
        if (pending.isEmpty()) return
        val settled = mutableListOf<PendingWrite>()
        for (p in pending) {
            val key = writeKey(p.command)
            val b = lastBatteries[p.command.batteryId]
            val applied = b?.let { p.command.appliedTo(it) }
            if (applied == true) {
                settled += p
                writeAlerts = writeAlerts.filterNot { it.key == key }
                log("Bestätigt: ${describe(p.command)}")
                continue
            }
            // Judge only against a frame the device produced after the command went out.
            val fresh = b != null && b.timestampMs > p.sentMs
            if (!fresh || nowMs - p.sentMs < CONFIRM_GRACE_MS) continue
            if (applied == null) { settled += p; continue } // device does not report it: nothing to verify
            if (p.attempts >= MAX_SEND_ATTEMPTS) {
                settled += p
                addEvents(listOf("Nicht übernommen nach ${p.attempts} Versuchen: ${describe(p.command)}"), nowMs)
                writeAlerts = writeAlerts.filterNot { it.key == key } + Alert(
                    key, Alert.Severity.WARNING, "Einstellung kam nicht an",
                    "${describe(p.command)} wurde ${p.attempts}x gesendet, das Gerät meldet weiter einen anderen Wert.", nowMs,
                )
                pushNtfy("Einstellung kam nicht an", describe(p.command), "high", "warning")
                continue
            }
            p.attempts++
            p.sentMs = nowMs
            val ok = runCatching { link.send(p.command) }.getOrDefault(false)
            addEvents(listOf("Erneut gesendet (${p.attempts}): ${describe(p.command)}" + if (ok) "" else " – Senden fehlgeschlagen"), nowMs)
            if (ok && p.command is BatteryCommand.SetAdaptiveMode) pauseCtForSwitch(nowMs)
        }
        pending.removeAll(settled)
    }

    private fun writeKey(c: BatteryCommand) = "write-${c.batteryId}-${c::class.simpleName}"

    /** Remembers a sent setting so it can be verified and re-sent if the device ignored it. */
    private fun trackPending(c: BatteryCommand, nowMs: Long) {
        if (c is BatteryCommand.Reboot) return // a restart leaves no lasting trace to check against
        pending.removeAll { it.command.batteryId == c.batteryId && it.command::class == c::class }
        pending += PendingWrite(c, nowMs, 1)
    }

    suspend fun manualCommand(command: BatteryCommand): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        val ok = runCatching { link.send(command) }.getOrDefault(false)
        addEvents(listOf((if (ok) "Gesendet: " else "Fehlgeschlagen: ") + describe(command)), now)
        if (ok) trackPending(command, now)
        if (ok && command is BatteryCommand.SetAdaptiveMode) pauseCtForSwitch(now)
        rebuild(now)
        ok
    }

    /**
     * Starts a calibration. Returns the reasons why it did NOT start, empty when it is running.
     * [force] skips the checks for a user who knows what they are doing.
     */
    suspend fun startCalibration(batteryIds: List<String>, force: Boolean = false): List<String> = mutex.withLock {
        val now = System.currentTimeMillis()
        val ids = batteryIds.ifEmpty { link.batteryIds }
        val blockers = if (force) emptyList() else calibrationBlockers(ids)
        if (blockers.isNotEmpty()) {
            addEvents(listOf("Kalibrierung nicht gestartet: ${blockers.joinToString("; ")}"), now)
            rebuild(now)
            return@withLock blockers
        }
        val r = engine.startCalibration(autoState, ids, now)
        autoState = r.state
        applyCommands(r.commands, now, automatic = true)
        addEvents(r.events, now)
        store.saveAutomation(autoState)
        rebuild(now)
        emptyList()
    }

    /**
     * Why a calibration cannot sensibly run right now: it blocks a battery for days, so the obvious
     * obstacles are checked before the first command instead of ending in a stop much later.
     */
    fun calibrationBlockers(ids: List<String>): List<String> {
        val out = mutableListOf<String>()
        if (!config.automationsMayWrite) out += "\"Automatik darf Einstellungen ändern\" ist aus"
        val cal = config.automation.calibration
        for (id in ids) {
            val b = lastBatteries[id] ?: run { out += "${batteryName(id)} liefert keine Daten"; continue }
            val name = b.name
            val dod = b.dodPercent
            if (dod == null) out += "$name: Entladetiefe unbekannt"
            else if (dod < MIN_CALIBRATION_DOD) out += "$name: Entladetiefe $dod % – nach der Freigabe könnte er nicht entladen"
            val slot = b.manualSlot
            if (slot != null && (!slot.enabled || slot.watts <= 0))
                out += "$name: kein Manuell-Zeitplan hinterlegt (zum Leeren nötig)"
            val cap = b.capacityWh
            val pvPerDay = recentDailyPv(id)
            if (cap != null && pvPerDay != null && pvPerDay > 0) {
                val needWh = (100 - b.socPercent).coerceAtLeast(0) / 100.0 * cap
                val days = needWh / pvPerDay
                if (days * 24 > cal.maxHoursPerBattery)
                    out += "$name: PV-Ertrag reicht nicht (zuletzt ~${pvPerDay.roundToInt()} Wh/Tag, " +
                        "bräuchte ~${days.roundToInt()} Tage, Limit ${cal.maxHoursPerBattery / 24} Tage)"
            }
        }
        return out
    }

    /** Average PV yield per day of the last few completed days, from the device counters. */
    private fun recentDailyPv(id: String): Double? {
        val today = LocalDate.now(zone).toString()
        val days = deviceDays.filter { it.batteryId == id && it.date < today }.sortedBy { it.date }.takeLast(3)
        if (days.isEmpty()) return null
        return days.sumOf { it.pvWh }.toDouble() / days.size
    }

    /** Warns when the CT002 stops delivering while the batteries keep reporting. */
    private fun meterAlerts(nowMs: Long): List<AlertDto> {
        if (ctStaleSinceMs == 0L || nowMs - ctStaleSinceMs < METER_ALERT_MS) {
            if (ctStaleSinceMs == 0L) meterDownPushed = false
            return emptyList()
        }
        val minutes = (nowMs - ctStaleSinceMs) / 60_000
        if (!meterDownPushed) {
            meterDownPushed = true
            pushNtfy("CT002 ohne Daten", "Seit $minutes min keine Zaehlerdaten. Netz, Haus und Autarkie fehlen solange.", "high", "warning")
        }
        return listOf(AlertDto("meter-down", "WARN", "CT002 liefert keine Daten",
            "Seit $minutes Minuten kommen keine Zählerwerte. Netzbezug, Hausverbrauch und Autarkie werden " +
                "für diese Zeit nicht gezählt. Der Server versucht die Verbindung selbst aufzufrischen.", nowMs))
    }

    /**
     * Warns when no device data has arrived for a while. Deliberately separate from [relinkIfDead],
     * which reacts to polls that throw: the link can report itself connected and still deliver nothing
     * (a rotated client certificate, a rejected login), and that case produced no warning at all.
     */
    private fun linkAlerts(nowMs: Long): List<AlertDto> {
        if (linkDownSinceMs == 0L || nowMs - linkDownSinceMs < LINK_ALERT_MS) return emptyList()
        val minutes = (nowMs - linkDownSinceMs) / 60_000
        if (!linkDownPushed) {
            linkDownPushed = true
            pushNtfy("Keine Daten aus der Cloud", "Seit $minutes min keine Geraetedaten. Steuerung und Zaehlung stehen solange still.", "high", "warning")
        }
        return listOf(AlertDto("link-down", "WARN", "Keine Daten aus der Marstek-Cloud",
            "Seit $minutes Minuten kommen keine Gerätedaten an. Solange wird nichts gesteuert und nichts " +
                "gezählt. Der Server baut die Verbindung selbst immer wieder neu auf; hilft das nicht, hat " +
                "Marstek meist das Client-Zertifikat gewechselt – dann erneuert es der Zertifikat-Wächter.", nowMs))
    }

    suspend fun abortCalibration() = mutex.withLock {
        val now = System.currentTimeMillis()
        val prevCal = autoState.calibration
        val r = engine.abortCalibration(autoState, now)
        autoState = r.state
        applyCommands(r.commands, now, automatic = true)
        addEvents(r.events, now)
        notifyCalibration(prevCal, autoState.calibration, now)
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
            selfDischargeAlertW = update.selfDischargeAlertW.coerceIn(1, 1000),
            calibrationDeviationPercent = update.calibrationDeviationPercent.coerceIn(1, 100),
            // ntfy topics allow only [A-Za-z0-9_-]; strip the rest so a stray space/newline can't
            // turn every push into a silently-swallowed URISyntaxException.
            ntfyTopic = update.ntfyTopic.trim().filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }.take(120),
            automationsMayWrite = update.automationsMayWrite,
            automation = config.automation.copy(
                balancer = config.automation.balancer.copy(enabled = update.balancerEnabled),
                calibration = config.automation.calibration.copy(
                    intervalDays = update.calibrationIntervalDays.coerceIn(0, 365),
                    holdMinutes = update.calibrationHoldMinutes.coerceIn(0, 240),
                    dischargeStartMinuteOfDay = update.calibrationDischargeStartMinuteOfDay.coerceIn(0, 1439),
                    chargeToleranceSocPercent = update.calibrationToleranceSocPercent.coerceIn(50, 100),
                    recoveryAttempts = update.calibrationRecoveryAttempts.coerceIn(0, 10),
                ),
            ),
        )
        engine = AutomationEngine(config.automation.copy(batteryIds = link.batteryIds), zone)
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
        selfDischargeAlertW = config.selfDischargeAlertW,
        calibrationDeviationPercent = config.calibrationDeviationPercent,
        automationsMayWrite = config.automationsMayWrite,
        balancerEnabled = config.automation.balancer.enabled,
        calibrationIntervalDays = config.automation.calibration.intervalDays,
        calibrationHoldMinutes = config.automation.calibration.holdMinutes,
        calibrationDischargeStartMinuteOfDay = config.automation.calibration.dischargeStartMinuteOfDay,
        calibrationToleranceSocPercent = config.automation.calibration.chargeToleranceSocPercent,
        calibrationRecoveryAttempts = config.automation.calibration.recoveryAttempts,
        ntfyTopic = config.ntfyTopic,
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

    /**
     * True when our own PV measurement covers (nearly) the whole day, so it can be preferred over the
     * battery's own counter. [MIN_MEASURED_COVERAGE] leaves room for short gaps and for the hour a
     * clock change adds or removes.
     */
    private fun measuredCoversDay(d: DeviceDay, nowMs: Long): Boolean {
        if (d.measuredPvMs <= 0) return false
        val day = try { LocalDate.parse(d.date) } catch (_: Exception) { return false }
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - start
        val elapsed = (nowMs - start).coerceAtMost(dayMs)
        return elapsed > 0 && d.measuredPvMs * 100.0 / elapsed >= MIN_MEASURED_COVERAGE
    }

    /** Daily (or monthly) per-battery energy (Wh) from the stored device day counters. */
    private fun batteryDaily(from: LocalDate, to: LocalDate, monthly: Boolean): BatteryStatsDto {
        val nowMs = System.currentTimeMillis()
        val fromS = from.toString(); val toS = to.toString()
        val recent = deviceDays.filter { it.date in fromS..toS }
        val keyOf: (DeviceDay) -> String = { if (monthly) it.date.substring(0, 7) else it.date }
        val keys = recent.map(keyOf).distinct().sorted()
        val labels = keys.map { if (monthly) it.substring(5) + "." + it.substring(0, 4) else it.substring(8, 10) + "." + it.substring(5, 7) + "." }
        val series = link.batteryIds.mapNotNull { id ->
            val byKey = recent.filter { it.batteryId == id }.groupBy(keyOf)
            if (byKey.isEmpty()) return@mapNotNull null
            // Our own measurement, to match the day view, the output series and the overall solar
            // yield — all of which are integrated live power. The device counter `pt` stands in for a
            // day we did not watch end to end: the battery keeps it itself and it covers the whole day,
            // while our measurement only starts when the server does. Deciding by "did we measure
            // anything at all" instead would drop the day an update was deployed from 318 Wh to 1 Wh
            // and leave it there.
            val pv = keys.map { k -> byKey[k]?.sumOf { d -> if (measuredCoversDay(d, nowMs)) d.measuredPvWh else d.pvWh } ?: 0 }
            // Output and charge follow the same rule as PV, each with the device counter that measures
            // the same thing: `it` (to the inverter) against our integrated output, `bc` (into the cells)
            // against our integrated PV surplus. Before this, charge preferred the device counter
            // whenever it existed while the day view integrated, so the same day read 344 Wh in the day
            // view and 138 Wh in the week view - the very thing this chart was supposed to stop doing.
            val output = keys.map { k -> byKey[k]?.sumOf { d -> if (measuredCoversDay(d, nowMs)) d.deliveredWh else d.deviceInverterWh } ?: 0 }
            val charge = keys.map { k -> byKey[k]?.sumOf { d -> if (measuredCoversDay(d, nowMs)) d.chargeWh else if (d.deviceChargeWh > 0) d.deviceChargeWh else d.chargeWh } ?: 0 }
            val self = keys.map { k -> byKey[k]?.sumOf { it.selfDischargeWh } ?: 0 }
            BatterySeriesDto(
                id = id, name = batteryName(id), pv = pv, output = output, charge = charge, selfDischarge = self,
                soc = keys.map { -1 },
                totals = BatteryTotals(pv.sum(), output.sum(), charge.sum(), self.sum()),
            )
        }
        return BatteryStatsDto(if (monthly) Bucket.MONTH.name else Bucket.DAY.name, "Wh", labels, series)
    }

    /**
     * 5-minute per-battery data of one day from the power history. Chart values are AVERAGE POWER (W)
     * per slot; [totals] are the day's energy (Wh). PV/output/charge come from the per-battery power
     * history; self-discharge from the SOC drop while idle; [soc] is the average state of charge.
     */
    private fun batteryFiveMinute(day: LocalDate): BatteryStatsDto {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        // Not dayStart + 24 h: a clock change makes the day 23 or 25 hours long, and a fixed span
        // either cuts the last hour off October's day or pulls an hour of the next day into March's.
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val slotMs = 5 * 60_000L; val slots = 288
        val ids = link.batteryIds
        val caps = ids.map { lastBatteries[it]?.capacityWh ?: 0 }
        val points = history.filter { it.t >= dayStart && it.t < dayEnd }.sortedBy { it.t }
        // Energy (Wh) per slot, plus covered hours per slot, so power = energy / coveredHours.
        val pvE = Array(ids.size) { DoubleArray(slots) }
        val outE = Array(ids.size) { DoubleArray(slots) }
        val chgE = Array(ids.size) { DoubleArray(slots) }
        val selfE = Array(ids.size) { DoubleArray(slots) }
        val cov = Array(ids.size) { DoubleArray(slots) }
        val socSum = Array(ids.size) { DoubleArray(slots) }
        val socCnt = Array(ids.size) { IntArray(slots) }
        for (p in points) {
            val slot = ((p.t - dayStart) / slotMs).toInt().coerceIn(0, slots - 1)
            for (idx in ids.indices) {
                val s = p.slotOf(ids[idx], idx)?.let { p.soc.getOrNull(it) } ?: continue
                socSum[idx][slot] += s; socCnt[idx][slot]++
            }
        }
        val maxGap = config.system.maxGapMs
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > maxGap) continue
            val slot = ((a.t - dayStart) / slotMs).toInt().coerceIn(0, slots - 1)
            val hours = dt / 3_600_000.0
            for (idx in ids.indices) {
                // Look the battery up by id: when one drops out of a snapshot the lists get shorter and
                // a positional read would silently attribute the other battery's values to it.
                val ai = a.slotOf(ids[idx], idx) ?: continue
                val bi = b.slotOf(ids[idx], idx) ?: continue
                val apv = a.batPvW.getOrNull(ai) ?: continue
                val bpv = b.batPvW.getOrNull(bi) ?: continue
                val aout = a.batOutW.getOrNull(ai) ?: 0; val bout = b.batOutW.getOrNull(bi) ?: 0
                cov[idx][slot] += hours
                pvE[idx][slot] += (apv + bpv) / 2.0 * hours
                outE[idx][slot] += (aout + bout) / 2.0 * hours
                chgE[idx][slot] += (maxOf(apv - aout, 0) + maxOf(bpv - bout, 0)) / 2.0 * hours
                val sa = a.soc.getOrNull(ai); val sb = b.soc.getOrNull(bi)
                if (sa != null && sb != null && sb < sa && caps[idx] > 0 &&
                    aout <= IDLE_W && bout <= IDLE_W && apv <= IDLE_W && bpv <= IDLE_W) {
                    selfE[idx][slot] += (sa - sb) / 100.0 * caps[idx]
                }
            }
        }
        val labels = (0 until slots).map {
            val m = (it * slotMs / 60_000L).toInt(); "%02d:%02d".format(m / 60, m % 60)
        }
        fun power(e: Array<DoubleArray>, idx: Int) = (0 until slots).map { s -> if (cov[idx][s] > 0) (e[idx][s] / cov[idx][s]).roundToInt() else 0 }
        val series = ids.mapIndexed { idx, id ->
            BatterySeriesDto(
                id = id, name = batteryName(id),
                pv = power(pvE, idx), output = power(outE, idx), charge = power(chgE, idx), selfDischarge = power(selfE, idx),
                soc = (0 until slots).map { if (socCnt[idx][it] > 0) (socSum[idx][it] / socCnt[idx][it]).roundToInt() else -1 },
                totals = BatteryTotals(
                    pvE[idx].sum().roundToInt(), outE[idx].sum().roundToInt(), chgE[idx].sum().roundToInt(), selfE[idx].sum().roundToInt(),
                ),
            )
        }
        return BatteryStatsDto(Bucket.FIVE_MIN.name, "W", labels, series)
    }

    enum class Bucket { FIVE_MIN, HOUR, DAY, MONTH }

    /**
     * Statistics for the window [from, to] (inclusive dates), aggregated into [bucket] buckets.
     * HOUR buckets come from the recent power history (only for the last ~2 days); DAY and MONTH
     * buckets from the stored daily totals. The total and the meter-based total match the window.
     */
    fun stats(from: LocalDate, to: LocalDate, bucket: Bucket, label: String): StatsDto {
        // Only used to work out how much of a day the server was actually watching, so the wall clock
        // is good enough here - it never feeds an energy value.
        val nowMs = System.currentTimeMillis()
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
            total = EnergyDto.from(total, config.tariff, nowMs, zone),
            buckets = buckets.map { BucketDto(it.first, EnergyDto.from(it.second, config.tariff, nowMs, zone)) },
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
        // Not dayStart + 24 h: a clock change makes the day 23 or 25 hours long, and a fixed span
        // either cuts the last hour off October's day or pulls an hour of the next day into March's.
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val slotMs = 5 * 60_000L
        val slots = 288
        val points = history.filter { it.t >= dayStart && it.t < dayEnd }.sortedBy { it.t }
        val eff = config.system.inverterEfficiency
        val cap = config.system.inverterMaxW.toDouble()
        // The same gap limit the integrator uses. With a longer one here the statistics bridge gaps
        // that the daily totals drop, and the server reports two different solar yields for the same
        // day - measured on the VM: 676.6 Wh in the daily total against 687.7 Wh in the hourly view.
        val maxGap = config.system.maxGapMs
        fun label(slot: Int): String {
            val minOfDay = (slot * slotMs / 60_000L).toInt()
            return "%02d:%02d".format(minOfDay / 60, minOfDay % 60)
        }
        val buckets = Array(slots) { DailyEnergy(date = label(it)) }
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > maxGap) continue
            val slot = ((a.t - dayStart) / slotMs).toInt().coerceIn(0, slots - 1)
            val hours = dt / 3_600_000.0
            val pvW = (a.pvW + b.pvW) / 2.0
            val outW = (a.outW + b.outW) / 2.0
            val acW = (outW * eff).coerceAtMost(cap)
            // Grid-dependent values only when the CT002 had data in BOTH samples (like the integrator);
            // otherwise house/self would wrongly count the battery output during a meter gap.
            val g = gridEnergy(a, b, acW, hours)
            buckets[slot] = buckets[slot] + DailyEnergy(
                date = buckets[slot].date, pvWh = pvW * hours, batteryOutputWh = outW * hours,
                inverterAcWh = acW * hours, gridImportWh = g.import, gridExportWh = g.export,
                houseWh = g.house, selfConsumedWh = g.self,
            )
        }
        return buckets.map { it.date to it }
    }

    private class GridEnergy(val import: Double, val export: Double, val house: Double, val self: Double)

    /** Grid import/export/house/self energy for one interval, or all zero when the CT002 had no data. */
    private fun gridEnergy(a: HistoryPoint, b: HistoryPoint, acW: Double, hours: Double): GridEnergy {
        val ga = a.gridW; val gb = b.gridW
        if (ga == null || gb == null) return GridEnergy(0.0, 0.0, 0.0, 0.0)
        val importW = (maxOf(ga, 0) + maxOf(gb, 0)) / 2.0
        val exportW = (maxOf(-ga, 0) + maxOf(-gb, 0)) / 2.0
        val houseW = (importW - exportW + acW).coerceAtLeast(0.0)
        val selfW = (acW - exportW).coerceAtLeast(0.0)
        return GridEnergy(importW * hours, exportW * hours, houseW * hours, selfW * hours)
    }

    /** Hourly buckets of one day, integrated from the power history (only available for recent days). */
    private fun hourlyBuckets(day: LocalDate): List<Pair<String, DailyEnergy>> {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        // Not dayStart + 24 h: a clock change makes the day 23 or 25 hours long, and a fixed span
        // either cuts the last hour off October's day or pulls an hour of the next day into March's.
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val points = history.filter { it.t >= dayStart && it.t < dayEnd }.sortedBy { it.t }
        val eff = config.system.inverterEfficiency
        val cap = config.system.inverterMaxW.toDouble()
        val maxGap = config.system.maxGapMs
        val buckets = Array(24) { DailyEnergy(date = "%02d".format(it)) }
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > maxGap) continue
            val hour = Instant.ofEpochMilli(a.t).atZone(zone).hour
            val hours = dt / 3_600_000.0
            val pvW = (a.pvW + b.pvW) / 2.0
            val outW = (a.outW + b.outW) / 2.0
            val acW = (outW * eff).coerceAtMost(cap)
            val g = gridEnergy(a, b, acW, hours)
            buckets[hour] = buckets[hour] + DailyEnergy(
                date = buckets[hour].date, pvWh = pvW * hours, batteryOutputWh = outW * hours,
                inverterAcWh = acW * hours, gridImportWh = g.import, gridExportWh = g.export,
                houseWh = g.house, selfConsumedWh = g.self,
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

    /** Logs when a battery stops answering (e.g. during a reboot) and when it is reachable again. */
    private fun checkReachability(nowMs: Long) {
        for (id in link.batteryIds) {
            val ts = lastBatteries[id]?.timestampMs ?: continue
            val reachable = nowMs - ts < REACH_STALE_MS
            val prev = batteryReachable[id]
            batteryReachable[id] = reachable
            if (prev != null && prev != reachable) {
                val name = lastBatteries[id]?.name ?: id.takeLast(6)
                addEvents(listOf(if (reachable) "$name wieder erreichbar" else "$name nicht mehr erreichbar (keine Antwort seit ${REACH_STALE_MS / 1000} s – z. B. Neustart)"), nowMs)
            }
        }
    }

    /** Adds one poll to the running averages behind the next history point. */
    private fun accumulateHistory(s: SystemSnapshot) {
        s.meter?.let { m ->
            if (accGridN == 0) { accGridMin = m.totalW; accGridMax = m.totalW }
            else { accGridMin = minOf(accGridMin, m.totalW); accGridMax = maxOf(accGridMax, m.totalW) }
            accGrid += m.totalW
            accGridN++
            accMeterPhase = m.phaseW
        }
        for (b in s.batteries) {
            accBatPv[b.id] = (accBatPv[b.id] ?: 0.0) + b.totalPvW
            accBatOut[b.id] = (accBatOut[b.id] ?: 0.0) + b.totalOutputW
            accBatN[b.id] = (accBatN[b.id] ?: 0) + 1
            accSoc[b.id] = b.socPercent
        }
    }

    /** Writes the averaged point and starts the next minute. */
    private fun flushHistory(nowMs: Long) {
        val ids = accSoc.keys.toList()
        if (ids.isEmpty() && accGridN == 0) return
        fun mean(m: Map<String, Double>, id: String) = (m[id] ?: 0.0) / (accBatN[id] ?: 1)
        val point = HistoryPoint(
            t = nowMs,
            gridW = if (accGridN > 0) (accGrid / accGridN).roundToInt() else null,
            gridMinW = if (accGridN > 0) accGridMin else null,
            gridMaxW = if (accGridN > 0) accGridMax else null,
            ids = ids,
            pvW = ids.sumOf { mean(accBatPv, it) }.roundToInt(),
            outW = ids.sumOf { mean(accBatOut, it) }.roundToInt(),
            soc = ids.map { accSoc[it] ?: -1 },
            batPvW = ids.map { mean(accBatPv, it).roundToInt() },
            batOutW = ids.map { mean(accBatOut, it).roundToInt() },
            meterPhaseW = accMeterPhase,
        )
        history = (history + point).filter { nowMs - it.t <= HISTORY_MS }.takeLast(MAX_HISTORY)
        accGrid = 0.0; accGridN = 0; accMeterPhase = emptyList()
        accBatPv.clear(); accBatOut.clear(); accBatN.clear(); accSoc.clear()
    }

    /** Position of [id] in a history point: by id when the point carries them, else positional (old data). */
    private fun HistoryPoint.slotOf(id: String, fallback: Int): Int? =
        if (ids.isEmpty()) fallback.takeIf { it < soc.size } else ids.indexOf(id).takeIf { it >= 0 }

    private fun recordMeterFresh(fresh: Boolean) {
        meterFreshWindow.addLast(fresh)
        while (meterFreshWindow.size > METER_QUALITY_WINDOW) meterFreshWindow.removeFirst()
    }

    /** Share of recent CT002 polls that returned fresh data, or null until we have a few samples. */
    private fun meterFreshPercent(): Int? {
        if (meterFreshWindow.size < 5) return null
        return (meterFreshWindow.count { it } * 100 / meterFreshWindow.size)
    }

    /** Estimated idle self-discharge power today (W), or null without enough idle time to judge. */
    private fun selfDischargePowerW(id: String): Int? {
        val h = idleHoursToday[id] ?: return null
        if (h < 0.25) return null
        return Math.round((idleSelfWhToday[id] ?: 0.0) / h).toInt()
    }

    /** Accumulates a clean (PV-free) discharge run; finalizes a capacity sample when PV returns or SOC rises. */
    private fun trackCapacityWindow(b: BatteryState, a: BatteryState, outEnergy: Double, cap: Int?, nowMs: Long) {
        val id = b.id
        if (a.totalPvW > IDLE_W || b.totalPvW > IDLE_W) { finalizeCapWindow(id, cap, nowMs); return }
        val sa = a.socPercent; val sb = b.socPercent
        if (capWinActive[id] != true || sb > (capWinLast[id] ?: sb)) {
            if (capWinActive[id] == true) finalizeCapWindow(id, cap, nowMs)
            capWinActive[id] = true; capWinPeak[id] = sa; capWinTrough[id] = sb; capWinLast[id] = sb; capWinEnergy[id] = outEnergy
            return
        }
        capWinEnergy[id] = (capWinEnergy[id] ?: 0.0) + outEnergy
        if (sb < (capWinTrough[id] ?: sb)) capWinTrough[id] = sb
        capWinLast[id] = sb
    }

    private fun finalizeCapWindow(id: String, deviceCapWh: Int?, nowMs: Long) {
        if (capWinActive[id] != true) return
        capWinActive[id] = false
        val drop = (capWinPeak[id] ?: return) - (capWinTrough[id] ?: return)
        val energy = capWinEnergy[id] ?: 0.0
        if (drop < CAP_MIN_DROP_PCT || energy <= 0) return
        val measured = (energy / drop * 100).roundToInt()
        if (measured <= 0) return
        capacitySamples = (capacitySamples + CapacitySample(id, nowMs, measured, drop, deviceCapWh ?: 0)).takeLast(CAP_MAX_SAMPLES)
        runCatching { capacitySamplesFile.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CapacitySample.serializer()), capacitySamples)) }
            .onFailure { log("Kapazitäts-Messungen speichern fehlgeschlagen: ${it.message}") }
        addEvents(listOf("${batteryName(id)}: Kapazität gemessen $measured Wh (SOC-Hub $drop %)"), nowMs)
    }

    private data class CalibInfo(val measured: Int?, val deviationPct: Int?, val recommended: Boolean)

    /** Compares the measured capacity (median of recent clean-discharge samples) to the device's claim. */
    private fun calibrationInfo(id: String, deviceCapWh: Int?): CalibInfo {
        val samples = capacitySamples.filter { it.batteryId == id }.takeLast(CAP_SAMPLES_USED)
        if (samples.size < CAP_MIN_SAMPLES) return CalibInfo(null, null, false)
        val measured = median(samples.map { it.measuredCapacityWh })
        if (deviceCapWh == null || deviceCapWh <= 0) return CalibInfo(measured, null, false)
        val dev = Math.round((deviceCapWh - measured) * 100.0 / deviceCapWh).toInt()
        return CalibInfo(measured, dev, Math.abs(dev) >= config.calibrationDeviationPercent)
    }

    private fun median(xs: List<Int>): Int {
        val s = xs.sorted(); val n = s.size
        return if (n == 0) 0 else if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun recordDeviceDays(snapshot: SystemSnapshot) {
        val todayStr = LocalDate.now(zone).toString()
        if (todayStr != selfDisDay) { selfDisDay = todayStr; idleHoursToday.clear(); idleSelfWhToday.clear() }
        var days = deviceDays
        for (b in snapshot.batteries) {
            days = MeterEvaluation.updateDeviceDays(days, b.id, b.timestampMs, b.dailyPvWh, b.dailyOutputWh, zone, b.dailyChargeWh, b.dailyInverterWh)
        }
        // Integrate per-battery charge/discharge (net PV-minus-output) since the previous snapshot.
        // Sub-Wh increments are carried in [chargeRemainder]/[dischargeRemainder] so fast polling adds up.
        val prev = lastBatterySnapshot
        lastBatterySnapshot = snapshot
        if (prev != null) {
            val dt = snapshot.timestampMs - prev.timestampMs
            if (dt in 1..config.system.maxGapMs) {
                val hours = dt / 3_600_000.0
                val prevById = prev.batteries.associateBy { it.id }
                for (b in snapshot.batteries) {
                    val a = prevById[b.id] ?: continue
                    val netA = a.totalPvW - a.totalOutputW
                    val netB = b.totalPvW - b.totalOutputW
                    val chargeW = (maxOf(netA, 0) + maxOf(netB, 0)) / 2.0
                    val dischargeW = (maxOf(-netA, 0) + maxOf(-netB, 0)) / 2.0
                    // Real delivered energy = integral of the live output (g1/g2), NOT the device
                    // counter `bs` which also counts self-discharge.
                    val deliveredW = (a.totalOutputW + b.totalOutputW) / 2.0
                    // The server's own PV measurement, kept next to the device counter `pt` so the
                    // long-standing gap between the two stays visible per battery and per day.
                    val pvW = (a.totalPvW + b.totalPvW) / 2.0
                    val cTot = (chargeRemainder[b.id] ?: 0.0) + chargeW * hours
                    val dTot = (dischargeRemainder[b.id] ?: 0.0) + dischargeW * hours
                    val vTot = (deliveredRemainder[b.id] ?: 0.0) + deliveredW * hours
                    val pTot = (pvRemainder[b.id] ?: 0.0) + pvW * hours
                    val cInt = cTot.toInt(); val dInt = dTot.toInt(); val vInt = vTot.toInt(); val pInt = pTot.toInt()
                    chargeRemainder[b.id] = cTot - cInt
                    dischargeRemainder[b.id] = dTot - dInt
                    deliveredRemainder[b.id] = vTot - vInt
                    pvRemainder[b.id] = pTot - pInt
                    // Self-discharge: SOC fell while the battery was idle (no output, no PV in either
                    // sample). That energy left the cells without being delivered — a loss, not output.
                    val idle = a.totalOutputW <= IDLE_W && b.totalOutputW <= IDLE_W && a.totalPvW <= IDLE_W && b.totalPvW <= IDLE_W
                    val cap = b.capacityWh ?: a.capacityWh
                    val selfWhExact = if (idle && cap != null && b.socPercent < a.socPercent)
                        (a.socPercent - b.socPercent) / 100.0 * cap else 0.0
                    val selfWh = Math.round(selfWhExact).toInt()
                    if (idle) {
                        idleHoursToday[b.id] = (idleHoursToday[b.id] ?: 0.0) + hours
                        idleSelfWhToday[b.id] = (idleSelfWhToday[b.id] ?: 0.0) + selfWhExact
                    }
                    days = MeterEvaluation.addBatteryEnergy(days, b.id, snapshot.timestampMs, cInt, dInt, vInt, selfWh, zone, pInt, dt)
                    // Measure usable capacity over clean (PV-free) discharge runs: energy out of the
                    // cells (delivered + self-discharge) divided by the SOC drop.
                    trackCapacityWindow(b, a, deliveredW * hours + selfWhExact, cap, snapshot.timestampMs)
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
        // A running calibration is a deliberate, bounded sequence. The daily write limit must not drop
        // one of its commands half way through and leave the battery in a transient state.
        val calibrating = autoState.calibration?.finished == false
        val newSuggestions = mutableListOf<BatteryCommand>()
        for (c in commands) {
            if (automatic && !config.automationsMayWrite) {
                newSuggestions += c
                addEvents(listOf("Vorschlag (Schreiben aus): ${describe(c)}"), nowMs)
                continue
            }
            if (automatic && !calibrating && !writeGuard.allow(c.batteryId, nowMs, zone)) {
                addEvents(listOf("Schreiblimit erreicht, übersprungen: ${describe(c)}"), nowMs)
                continue
            }
            if (automatic) writeGuard.record(c.batteryId, nowMs, zone)
            val ok = runCatching { link.send(c) }.getOrDefault(false)
            addEvents(listOf((if (ok) "Automatik: " else "Automatik fehlgeschlagen: ") + describe(c)), nowMs)
            if (ok) trackPending(c, nowMs)
            if (ok && c is BatteryCommand.SetAdaptiveMode) pauseCtForSwitch(nowMs)
        }
        if (automatic) suggestions = newSuggestions
    }

    /** Suppresses CT002 polling briefly so a mode switch is not drowned out by meter poll requests. */
    private fun pauseCtForSwitch(nowMs: Long) {
        if (ctPauseUntilMs >= nowMs + CT_PAUSE_MS) return // already paused far enough
        ctPauseUntilMs = nowMs + CT_PAUSE_MS
        addEvents(listOf("CT002-Abfrage ${CT_PAUSE_MS / 1000} s pausiert (Moduswechsel)"), nowMs)
    }

    /** Pushes a notification for each newly finished calibration result (per battery, and on stop). */
    private fun notifyCalibration(prev: CalibrationRun?, next: CalibrationRun?, nowMs: Long) {
        next ?: return
        val prevCount = prev?.results?.size ?: 0
        for (r in next.results.drop(prevCount)) {
            val name = lastBatteries[r.batteryId]?.name ?: autoState.names[r.batteryId] ?: r.batteryId
            if (r.success) pushNtfy("Kalibrierung fertig", "$name: ${r.message}", "default", "battery,white_check_mark")
            else pushNtfy("Kalibrierung gestoppt", "$name: ${r.message}", "high", "warning")
        }
        // Whole batch done (more than one battery): a short summary on top of the per-battery messages.
        if (next.phase == CalibrationPhase.DONE && prev?.finished != true && next.queue.size > 1) {
            val okCount = next.results.count { it.success }
            pushNtfy("Kalibrierung abgeschlossen", "$okCount/${next.queue.size} Speicher kalibriert.", "default", "white_check_mark")
        }
    }

    /** Fire-and-forget push to ntfy.sh (no-op when no topic is configured). Never blocks the poll loop. */
    private fun pushNtfy(title: String, message: String, priority: String, tags: String) {
        val topic = config.ntfyTopic.ifBlank { return }
        val base = config.ntfyBaseUrl.trimEnd('/')
        pushScope.launch {
            runCatching {
                val conn = java.net.URI("$base/$topic").toURL().openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.doOutput = true
                    // HTTP headers are ASCII; the body below carries the full text including umlauts.
                    conn.setRequestProperty("Title", title.filter { c -> c.code in 32..126 }.ifBlank { "Hinweis" })
                    conn.setRequestProperty("Priority", priority)
                    conn.setRequestProperty("Tags", tags)
                    conn.outputStream.use { it.write(message.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    // For a non-2xx status getInputStream() throws; the body (if any) is on errorStream.
                    (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
                    if (code !in 200..299) log("ntfy-Push HTTP $code")
                } finally {
                    conn.disconnect()
                }
            }.onFailure { log("ntfy-Push fehlgeschlagen: ${it.message}") }
        }
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
        store.saveDeviceDays(deviceDays)
        // The history file is by far the biggest; rewriting it every minute is a lot of disk traffic
        // for a chart. A few minutes of history is an acceptable loss after a hard kill.
        if (nowMs - lastHistorySaveMs >= HISTORY_SAVE_MS) {
            lastHistorySaveMs = nowMs
            store.saveHistory(history)
        }
    }

    /**
     * Writes the automation state when something that matters changed, and otherwise at most once per
     * [SAVE_INTERVAL_MS]. The bookkeeping inside it (last seen, names) moves on every single poll, so
     * comparing the whole object would still mean writing the file all day long.
     */
    private fun saveStateIfChanged(nowMs: Long) {
        val prev = savedAutoState
        val changed = prev == null ||
            autoState.calibration != prev.calibration ||
            autoState.lastCalibrationFinishedMs != prev.lastCalibrationFinishedMs ||
            autoState.balancer != prev.balancer
        if (changed || nowMs - lastAutoSaveMs >= SAVE_INTERVAL_MS) {
            store.saveAutomation(autoState)
            savedAutoState = autoState
            lastAutoSaveMs = nowMs
        }
        if (alerts != savedAlerts) { store.saveAlerts(alerts); savedAlerts = alerts }
    }

    /** Sends one push per newly raised warning, and allows it again once the condition cleared. */
    private fun pushNewAlerts(list: List<Alert>) {
        pushedAlertKeys.retainAll(list.map { it.key }.toSet())
        for (a in list) {
            if (a.severity == Alert.Severity.INFO) continue
            if (!pushedAlertKeys.add(a.key)) continue
            pushNtfy(a.title, a.message, "high", "warning")
        }
    }

    /**
     * Flushes everything to disk. The service is restarted nightly by the OS and by updates, and the
     * periodic save only runs once a minute, so without this the last minute is lost every time.
     */
    suspend fun shutdown() = mutex.withLock {
        val now = System.currentTimeMillis()
        flushHistory(now)
        lastSaveMs = 0L
        lastHistorySaveMs = 0L
        lastAutoSaveMs = 0L
        persist(now)
        saveStateIfChanged(now)
    }

    private fun describe(c: BatteryCommand): String {
        val name = lastBatteries[c.batteryId]?.name ?: c.batteryId
        return when (c) {
            is BatteryCommand.SetDod -> "$name: Entladetiefe ${c.percent} %"
            is BatteryCommand.SetChargeMode -> "$name: Lademodus ${c.mode}"
            is BatteryCommand.SetAdaptiveMode -> "$name: adaptiver Modus ${if (c.enabled) "an" else "aus"}"
            is BatteryCommand.Reboot -> "$name: Neustart"
        }
    }

    private fun today(): DailyEnergy? {
        val today = LocalDate.now(zone).toString()
        return integrator.totals[today]
    }

    /** One warning per battery whose idle self-discharge is unusually high, for the dashboard. */
    private fun selfDischargeAlerts(batteries: Collection<BatteryState>, nowMs: Long): List<AlertDto> =
        batteries.mapNotNull { b ->
            val w = selfDischargePowerW(b.id) ?: return@mapNotNull null
            if (w <= config.selfDischargeAlertW) return@mapNotNull null
            AlertDto("selfdis-${b.id}", "WARN", "Hohe Eigenentladung",
                "${b.name}: ~$w W Eigenentladung im Leerlauf (heute ${(idleSelfWhToday[b.id] ?: 0.0).roundToInt()} Wh) – bitte beobachten.", nowMs)
        }

    /** A battery whose depth of discharge keeps it from delivering anything is easy to overlook. */
    private fun dodAlerts(batteries: Collection<BatteryState>, nowMs: Long): List<AlertDto> =
        batteries.mapNotNull { b ->
            val dod = b.dodPercent ?: return@mapNotNull null
            if (dod >= MIN_CALIBRATION_DOD) return@mapNotNull null
            AlertDto("dod-${b.id}", "WARN", "${b.name} gibt nichts ab",
                "Die Entladetiefe steht auf $dod %: der Speicher behält seine Ladung und versorgt das Haus nicht. " +
                    "Unter \"Speicher\" wieder höher stellen.", nowMs)
        }

    /** One info alert per battery whose measured capacity deviates enough to suggest a calibration. */
    private fun calibrationAlerts(batteries: Collection<BatteryState>, nowMs: Long): List<AlertDto> =
        batteries.mapNotNull { b ->
            val cal = calibrationInfo(b.id, b.capacityWh)
            if (!cal.recommended) return@mapNotNull null
            AlertDto("calib-${b.id}", "INFO", "Kalibrierung empfohlen",
                "${b.name}: gemessene Kapazität ${cal.measured} Wh weicht ${cal.deviationPct} % von der Anzeige (${b.capacityWh} Wh) ab.", nowMs)
        }

    private fun rebuild(nowMs: Long) {
        val batteries = lastBatteries.values.sortedBy { it.name }
        val nowDay = LocalDate.now(zone).toString()
        val group = GroupState(batteries)
        // A reading that stopped coming must not keep sitting in the dashboard as if it were current.
        val meter = lastMeter?.takeIf { nowMs - it.timestampMs < METER_FRESH_MS }
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
                val dd = deviceDays.firstOrNull { d -> d.batteryId == it.id && d.date == nowDay }
                val delivered = dd?.deliveredWh
                val selfW = selfDischargePowerW(it.id)
                val cal = calibrationInfo(it.id, it.capacityWh)
                BatteryDto(
                    id = it.id, name = it.name, socPercent = it.socPercent, capacityWh = it.capacityWh,
                    pvInputW = it.pvInputW, outputW = it.outputW, outputEnabled = it.outputEnabled,
                    dodPercent = it.dodPercent, chargeMode = it.chargeMode.name, adaptiveMode = it.adaptiveMode,
                    dailyPvWh = it.dailyPvWh, measuredPvTodayWh = dd?.measuredPvWh,
                    measuredCapacityWh = cal.measured, capacityDeviationPercent = cal.deviationPct, calibrationRecommended = cal.recommended,
                    capacitySamples = capacitySamples.count { s -> s.batteryId == it.id }, capacitySamplesNeeded = CAP_MIN_SAMPLES,
                    dailyOutputWh = it.dailyOutputWh,
                    dailyChargeWh = it.dailyChargeWh, dailyInverterWh = it.dailyInverterWh,
                    deviceLossTodayWh = it.dailyOutputWh?.let { bs -> it.dailyInverterWh?.let { inv -> (bs - inv).coerceAtLeast(0) } },
                    deliveredTodayWh = delivered, selfDischargeTodayWh = dd?.selfDischargeWh, selfDischargeW = selfW,
                    selfDischargeHigh = selfW != null && selfW > config.selfDischargeAlertW,
                    temperatureC = it.temperatureC,
                    firmware = it.firmware, ageSeconds = ((nowMs - it.timestampMs) / 1000).coerceAtLeast(0),
                )
            },
            today = today()?.let { EnergyDto.from(it, config.tariff, nowMs, zone) },
            alerts = (alerts + writeAlerts).map { AlertDto.from(it) } + linkAlerts(nowMs) + meterAlerts(nowMs) +
                dodAlerts(batteries, nowMs) + selfDischargeAlerts(batteries, nowMs) + calibrationAlerts(batteries, nowMs),
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
        is BatteryCommand.Reboot -> CommandDto(c.batteryId, "reboot", 0)
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
        /**
         * Percent of a day our own measurement must cover before it is preferred over the device counter.
         * 95 would let a missing midday hour through, which can be a third of a November day's solar
         * yield; normal operation reaches ~100 % and a restart for an update costs about a minute.
         */
        const val MIN_MEASURED_COVERAGE = 99.0
        const val MAX_EVENTS = 500
        const val SAVE_INTERVAL_MS = 60_000L
        const val DAY_MS = 86_400_000L
        const val METER_QUALITY_WINDOW = 120
        const val CT_STALE_RECONNECT_MS = 180_000L   // reconnect if the CT002 is silent this long while batteries report
        const val RECONNECT_COOLDOWN_MS = 300_000L   // minimum gap between self-heal reconnects
        const val IDLE_W = 5                          // output/PV at or below this counts as "idle" for self-discharge
        const val REACH_STALE_MS = 30_000L            // no battery frame for this long = "not reachable" (reboot visible)
        const val CT_PAUSE_MS = 20_000L               // suppress CT002 polling this long around a mode switch
        const val FAILURE_EVENT_MS = 60_000L          // at most one "poll failed" entry per minute
        const val RELINK_AFTER_FAILURES = 5           // failed polls before the link is rebuilt
        const val RELINK_RETRY_MS = 300_000L          // gap between two rebuild attempts
        const val CONFIRM_GRACE_MS = 20_000L          // time a device gets to report a new setting
        const val MAX_SEND_ATTEMPTS = 4               // re-sends before a setting is reported as failed
        const val METER_FRESH_MS = 120_000L           // older CT002 readings are not shown as current
        const val METER_ALERT_MS = 600_000L           // warn after this long without meter data
        const val LINK_STALE_MS = 300_000L            // no device data for this long = link unhealthy
        const val LINK_ALERT_MS = 600_000L            // warn after this long without any device data
        const val LINK_RELINK_MS = 180_000L           // rebuild the link after this long without device data
        const val MIN_CALIBRATION_DOD = 10            // below this a released battery could not discharge
        const val METER_RECOVERY_TRIES = 3            // reconnect attempts per silent spell of the CT002
        const val HISTORY_SAVE_MS = 300_000L          // the chart history is written at most this often
        const val CAP_MIN_DROP_PCT = 25               // minimum SOC drop for a usable capacity measurement
        const val CAP_MIN_SAMPLES = 2                 // measurements needed before judging
        const val CAP_SAMPLES_USED = 5                // recent measurements used for the median
        const val CAP_MAX_SAMPLES = 100               // persisted capacity samples kept
    }
}
