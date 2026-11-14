package de.rasier.speicher.server

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient
import de.rasier.speicher.cloud.CloudDevice
import de.rasier.speicher.cloud.LoginResult
import de.rasier.speicher.cloud.MarstekCloudClient
import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.MeterReading
import de.rasier.speicher.model.SystemSnapshot
import de.rasier.speicher.protocol.cloud.CloudPayloads
import de.rasier.speicher.protocol.cloud.CloudTopics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Live link to the Marstek cloud over MQTT with mutual TLS. Logs in over HTTP to learn the device
 * list and salts, then connects to the cloud broker and polls each device with cd=01.
 *
 * The broker URL, CA, client certificate and key come from the hame-relay Docker image and are
 * supplied at runtime via [ServerConfig]; nothing secret is bundled with this code.
 */
class CloudDeviceLink(
    private val config: ServerConfig,
    private val log: (String) -> Unit = { println(it) },
) : DeviceLink {

    private data class Device(
        val devid: String,
        val mac: String,
        val type: String,
        val name: String,
        val isBattery: Boolean,
        val topics: CloudTopics.Topics,
    )

    private val http = MarstekCloudClient(config.cloudBaseUrl)
    private var client: Mqtt3BlockingClient? = null
    private var devices: List<Device> = emptyList()
    private val latest = ConcurrentHashMap<String, Pair<String, Long>>() // statusTopic -> (payload, ms)
    @Volatile private var connected = false
    /** True between connect() and close(); the receiver thread lives as long as this holds. */
    @Volatile private var running = false
    @Volatile private var lastDataMs: Long? = null
    @Volatile private var statusDetail = "nicht verbunden"
    @Volatile private var lastOutlierLogMs = 0L
    @Volatile private var lastContradictLogMs = 0L
    @Volatile private var receiver: Thread? = null
    @Volatile private var everConnected = false

    /**
     * Re-subscribing after a reconnect must not happen on the client's own event loop. HiveMQ invokes
     * the connected listener from MqttConnectHandler.readConnAck BEFORE it releases the connect call
     * (bytecode offsets 256 and 286), on the thread that decodes incoming packets - and the blocking
     * subscribe waits for a SUBACK only that same thread could decode. One daemon thread, so repeated
     * reconnects cannot pile up.
     */
    private val subscribeExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mqtt-subscribe").apply { isDaemon = true }
    }
    @Volatile private var lastDisconnectLogMs = 0L

    override val batteryIds: List<String> get() = devices.filter { it.isBattery }.map { it.devid }
    override val status: LinkStatus get() = LinkStatus(connected, statusDetail, lastDataMs)

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val cloudDevices = login()
        devices = cloudDevices.mapNotNull { toDevice(it) }
        require(devices.isNotEmpty()) { "Keine passenden Geräte im Marstek-Konto gefunden" }
        log("Geräte: " + devices.joinToString { "${it.name} (${it.type})" })

        val material = TlsFactory.load(
            File(config.certDir, "ca.crt"),
            File(config.certDir, "client.crt"),
            File(config.certDir, "client.key"),
        )
        val (host, port) = brokerHostPort()
        // Automatic reconnect: without it a single dropped connection would silence the server until
        // someone restarts it by hand. The session is clean, so every reconnect re-subscribes.
        val c = Mqtt3Client.builder()
            .identifier("mst_" + UUID.randomUUID().toString().replace("-", "").take(24))
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                connected = true
                statusDetail = "verbunden mit $host"
                // Never inline: see subscribeExec. Calling subscribeAll() here froze the event loop
                // inside the CONNACK handler, so the connect never completed and no timeout could fire
                // - the thread that would have run them was the one blocked. The first subscribe is
                // done by connect() itself on the main thread; this is only for reconnects.
                if (everConnected) subscribeExec.execute { subscribeAll() }
            }
            .addDisconnectedListener { ctx ->
                connected = false
                // Retry inside the client only for a connection that stood at least once. Letting it
                // retry a failed FIRST connect is what made connectWith().send() block indefinitely:
                // the client kept trying in the background while the call never came back, so the
                // caller's retry loop - which logs every failure - never got its turn.
                ctx.reconnector.reconnect(everConnected && running)
                statusDetail = if (everConnected) "Verbindung unterbrochen, verbinde neu…" else "nicht verbunden"
                val now = System.currentTimeMillis()
                if (now - lastDisconnectLogMs > DISCONNECT_LOG_MS) {
                    lastDisconnectLogMs = now
                    log("MQTT-Verbindung unterbrochen (${ctx.cause.message}) – automatischer Neuaufbau läuft.")
                }
            }
            .sslConfig()
            .keyManagerFactory(material.keyManagerFactory)
            .trustManagerFactory(material.trustManagerFactory)
            .applySslConfig()
            .buildBlocking()
        // Set before connecting so the connected listener can already subscribe through it.
        client = c
        running = true
        // Logged on both sides: without this a hang here is invisible, which is why the outage showed
        // up as a log that simply stopped after the device list.
        log("Verbinde mit $host:$port …")
        // The blocking connect does not reliably come back. HiveMQ bounds a single attempt (10 s
        // socket, 10 s handshake, 60 s CONNACK), but with automatic reconnect switched on a refused or
        // stalled attempt is retried inside the client instead of being thrown - and the call keeps
        // blocking across those retries. That is how the server sat here for five and a half hours on
        // 13.11.: no poll, no retry, no log line. Only bounding the whole call fixes that; the caller
        // retries every 30 s and writes a line each time, which is what makes an outage visible.
        val failure = AtomicReference<Throwable?>(null)
        val attempt = Thread({
            runCatching { c.connectWith().keepAlive(30).cleanSession(true).send() }.onFailure { failure.set(it) }
        }, "mqtt-connect").apply { isDaemon = true }
        attempt.start()
        attempt.join(CONNECT_BUDGET_MS)
        if (attempt.isAlive) {
            running = false
            client = null
            // Abandoned, not disconnected: disconnect() goes to the same stuck client and blocks just
            // as the connect does, which left the budget doing nothing but moving the hang 90 s later.
            Thread({ runCatching { c.disconnect() } }, "mqtt-abandon").apply { isDaemon = true }.start()
            throw IllegalStateException("Verbindungsaufbau zu $host:$port hängt seit ${CONNECT_BUDGET_MS / 1000} s")
        }
        failure.get()?.let {
            running = false
            client = null
            throw it
        }
        log("Verbunden mit $host:$port")
        connected = true
        everConnected = true
        statusDetail = "verbunden mit $host"

        // Receiver thread: store the latest payload per status topic. It must outlive single
        // disconnects, so it runs until close() and only stops on an error while shutting down.
        val publishes = c.publishes(MqttGlobalPublishFilter.ALL)
        Thread({
            while (running) {
                try {
                    val msg = publishes.receive()
                    val topic = msg.topic.toString()
                    val payload = String(msg.payloadAsBytes, Charsets.US_ASCII)
                    latest[topic] = payload to System.currentTimeMillis()
                    lastDataMs = System.currentTimeMillis()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    if (!running) break
                    log("MQTT-Empfang gestört: ${e.message}")
                    Thread.sleep(1000)
                }
            }
        }, "mqtt-receiver").apply { isDaemon = true }.also { receiver = it }.start()

        subscribeAll()
        log("Abonniert: ${devices.size} Geräte")
    }

    /** (Re-)subscribes every device status topic. Safe to call again after a reconnect. */
    private fun subscribeAll() {
        val c = client ?: return
        for (d in devices) {
            runCatching { c.subscribeWith().topicFilter(d.topics.status).qos(MqttQos.AT_MOST_ONCE).send() }
                .onFailure { log("Abonnieren von ${d.name} fehlgeschlagen: ${it.message}") }
        }
    }

    override suspend fun poll(nowMs: Long, pollMeter: Boolean, pollBattery: Boolean, replyWaitMs: Long): SystemSnapshot = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("Nicht verbunden")
        // Poll only the selected device types; unpolled devices keep their last stored value below.
        devices.filter { (it.isBattery && pollBattery) || (!it.isBattery && pollMeter) }.forEach {
            c.publishWith().topic(it.topics.command).qos(MqttQos.AT_MOST_ONCE)
                .payload(CloudPayloads.POLL_RUNTIME.toByteArray(Charsets.US_ASCII)).send()
        }
        // Give the devices a moment to answer on their status topics.
        delay(replyWaitMs.coerceIn(200, 2000))

        val batteries = mutableListOf<BatteryState>()
        var meter: MeterReading? = null
        for (d in devices) {
            val entry = latest[d.topics.status] ?: continue
            if (nowMs - entry.second > STALE_MS) continue
            if (config.debug) log("ROH ${d.name}: ${entry.first}")
            val fields = CloudPayloads.parseFields(entry.first)
            if (d.isBattery) {
                CloudPayloads.parseBattery(fields, d.devid, d.name, d.type, entry.second)?.let { batteries += it }
            } else {
                CloudPayloads.parseMeter(fields, entry.second)?.let {
                    meter = it
                    // Reported only: the value is still used, because which side is wrong is unknown
                    // and the phase convention under export has never been seen in practice.
                    if (it.totalContradictsPhases && nowMs - lastContradictLogMs > OUTLIER_LOG_MS) {
                        lastContradictLogMs = nowMs
                        log("CT002 unplausibel: Gesamtwert ${it.totalW} W passt nicht zur Phasensumme ${it.phaseW.sum()} W, roh='${entry.first}'")
                    }
                    // Capture the full raw frame + per-phase split for implausibly high totals, so a
                    // grid "needle" can be diagnosed (real inrush spread over phases vs. a sensor glitch
                    // on a single phase). Logged regardless of debug, but rare by nature.
                    // Throttled: one real 3 kW load used to write this same line every 1.5 s until it
                    // filled the log the outage should have been visible in. The threshold stays where
                    // it was, because 3-5 kW is the range the raw-frame capture exists for.
                    if (kotlin.math.abs(it.totalW) >= METER_OUTLIER_W &&
                        nowMs - lastOutlierLogMs > OUTLIER_LOG_MS) {
                        lastOutlierLogMs = nowMs
                        log("CT002-Ausreißer: pwr_t=${it.totalW} W, Phasen=${it.phaseW}, roh='${entry.first}'")
                    }
                }
            }
        }
        if (batteries.isNotEmpty() || meter != null) lastDataMs = nowMs
        statusDetail = if (!connected) "nicht verbunden"
        else "verbunden, ${batteries.size} Speicher" + (if (meter != null) " + CT002" else "")
        SystemSnapshot(nowMs, batteries, meter)
    }

    override suspend fun send(command: BatteryCommand): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext false
        val d = devices.firstOrNull { it.devid == command.batteryId } ?: return@withContext false
        val payload = CloudPayloads.encode(command, noFlash = config.useNoFlashCommands)
        c.publishWith().topic(d.topics.command).qos(MqttQos.AT_MOST_ONCE)
            .payload(payload.toByteArray(Charsets.US_ASCII)).send()
        true
    }

    /**
     * Diagnostic probe: polls only the chosen device type at [intervalMs] for [durationMs] and logs,
     * per cycle, whether a fresh reply arrived and the measured grid power. Prints a summary. Used to
     * check empirically how low the poll interval can go before replies start to drop. Read-only in
     * the sense that it changes nothing in the running system; it does publish cd=1 (a normal poll).
     */
    suspend fun probe(onlyMeter: Boolean, intervalMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        val sel = devices.filter { if (onlyMeter) !it.isBattery else it.isBattery }
        if (sel.isEmpty()) { log("Keine passenden Geräte gefunden."); return@withContext }
        log("PROBE: ${sel.joinToString { it.name }}, Intervall ${intervalMs} ms, Dauer ${durationMs / 1000} s")
        val c = client ?: run { log("Nicht verbunden."); return@withContext }

        val lastReplyMs = HashMap<String, Long>()
        var cycles = 0
        var fresh = 0
        val gaps = ArrayList<Long>()
        val end = System.currentTimeMillis() + durationMs

        while (System.currentTimeMillis() < end) {
            val prev = sel.associate { it.topics.status to (latest[it.topics.status]?.second ?: 0L) }
            sel.forEach {
                c.publishWith().topic(it.topics.command).qos(MqttQos.AT_MOST_ONCE)
                    .payload(CloudPayloads.POLL_RUNTIME.toByteArray(Charsets.US_ASCII)).send()
            }
            delay(intervalMs)
            cycles++
            for (d in sel) {
                val e = latest[d.topics.status]
                val got = e != null && e.second > (prev[d.topics.status] ?: 0L)
                if (got) {
                    fresh++
                    val f = CloudPayloads.parseFields(e.first)
                    val m = CloudPayloads.parseMeter(f, e.second)
                    val lr = lastReplyMs[d.topics.status]
                    val gap = if (lr != null) e.second - lr else -1
                    if (lr != null) gaps.add(gap)
                    lastReplyMs[d.topics.status] = e.second
                    val vals = if (m != null) "pwr_a=${m.phaseW.getOrElse(0){0}},pwr_b=${m.phaseW.getOrElse(1){0}},pwr_c=${m.phaseW.getOrElse(2){0}},pwr_t=${m.totalW}" else "(keine Messwerte)"
                    log("  [${d.name}] frisch ✓  Abstand ${if (gap < 0) "-" else "$gap ms"}  $vals")
                } else {
                    log("  [${d.name}] keine frische Antwort in dieser Runde")
                }
            }
        }
        val expected = cycles * sel.size
        val rate = if (expected > 0) fresh * 100.0 / expected else 0.0
        val gMin = gaps.minOrNull() ?: 0
        val gMax = gaps.maxOrNull() ?: 0
        val gAvg = if (gaps.isNotEmpty()) gaps.average().toLong() else 0
        log("")
        log("== PROBE-ZUSAMMENFASSUNG ==")
        log("Runden: $cycles   erwartete Antworten: $expected   frische: $fresh   Erfolgsquote: ${"%.1f".format(rate)} %")
        log("Abstand frische Antworten: min ${gMin} ms, Ø ${gAvg} ms, max ${gMax} ms")
        log(if (rate >= 95.0) "Ergebnis: sauber bei ${intervalMs} ms." else "Ergebnis: Antworten fallen aus – Intervall zu kurz oder Cloud träge.")
    }

    override fun close() {
        running = false
        connected = false
        everConnected = false
        statusDetail = "nicht verbunden"
        // Interrupt before disconnecting: the thread blocks in publishes.receive() and the flag alone
        // never reached it, so every rebuild left a thread behind that kept writing into the same
        // latest/lastDataMs - which made a dead link look alive.
        receiver?.interrupt()
        receiver = null
        runCatching { client?.disconnect() }
        client = null
    }

    private fun login(): List<CloudDevice> {
        return when (val r = http.login(config.email, config.passwordMd5)) {
            is LoginResult.Success -> http.deviceList(config.email, config.passwordMd5)
            LoginResult.WrongPassword -> throw IllegalStateException("Falsches Marstek-Passwort")
            LoginResult.UnknownAccount -> throw IllegalStateException("Marstek-Konto in dieser Region unbekannt")
            is LoginResult.Failed -> throw IllegalStateException("Anmeldung fehlgeschlagen: ${r.reason}")
        }
    }

    private fun toDevice(d: CloudDevice): Device? {
        val mac = d.mac ?: return null
        val type = d.type ?: return null
        if (!d.isB2500 && !d.isMeter) return null
        val salt = (d.raw["salt"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val useCq = usesCq(type, d.version)
        val topicId = CloudTopics.topicId(type, mac, salt, useCq)
        return Device(
            devid = d.devid,
            mac = mac,
            type = type,
            name = d.name.ifBlank { if (d.isB2500) "Speicher" else "CT002" },
            isBattery = d.isB2500,
            topics = CloudTopics.topics(type, topicId),
        )
    }

    /** cq scheme: HMJ from fw 116, HME-4 from fw 122; otherwise the AES scheme. */
    private fun usesCq(type: String, version: String?): Boolean {
        val fw = version?.substringBefore('.')?.toIntOrNull() ?: return false
        return when {
            type.startsWith("HMJ") -> fw >= 116
            type.startsWith("HME-4") || type.startsWith("HME-2") -> version.length == 3 && fw >= 122
            type.startsWith("TPM2") -> true
            else -> false
        }
    }

    private fun brokerHostPort(): Pair<String, Int> {
        val raw = config.brokerUrl ?: File(config.certDir, "broker-url").takeIf { it.exists() }?.readText()?.trim()
        ?: throw IllegalStateException("Broker-URL fehlt (broker_url setzen oder ${config.certDir}/broker-url anlegen)")
        val noScheme = raw.substringAfter("://", raw).trim().removeSuffix("/")
        val host = noScheme.substringBefore(":")
        val port = noScheme.substringAfter(":", "8883").toIntOrNull() ?: 8883
        return host to port
    }

    companion object {
        private const val STALE_MS = 120_000L
        private const val DISCONNECT_LOG_MS = 60_000L   // do not fill the log while reconnects retry
        private const val METER_OUTLIER_W = 3000   // log the raw CT002 frame above this |pwr_t| for diagnosis
        private const val OUTLIER_LOG_MS = 60_000L // at most one outlier frame per minute
        // Longer than one full HiveMQ attempt, so a slow but working connect still completes; short
        // enough that the caller gets to retry and log instead of the process going quiet.
        private const val CONNECT_BUDGET_MS = 90_000L
    }
}
