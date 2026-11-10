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
    @Volatile private var lastDataMs: Long? = null
    @Volatile private var statusDetail = "nicht verbunden"

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
        val c = Mqtt3Client.builder()
            .identifier("mst_" + UUID.randomUUID().toString().replace("-", "").take(24))
            .serverHost(host)
            .serverPort(port)
            .sslConfig()
            .keyManagerFactory(material.keyManagerFactory)
            .trustManagerFactory(material.trustManagerFactory)
            .applySslConfig()
            .buildBlocking()
        c.connectWith().keepAlive(30).cleanSession(true).send()
        client = c
        connected = true
        statusDetail = "verbunden mit $host"

        // Receiver thread: store the latest payload per status topic.
        val publishes = c.publishes(MqttGlobalPublishFilter.ALL)
        Thread({
            try {
                while (connected) {
                    val msg = publishes.receive()
                    val topic = msg.topic.toString()
                    val payload = String(msg.payloadAsBytes, Charsets.US_ASCII)
                    latest[topic] = payload to System.currentTimeMillis()
                    lastDataMs = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                if (connected) log("MQTT-Empfang beendet: ${e.message}")
            }
        }, "mqtt-receiver").apply { isDaemon = true }.start()

        devices.forEach { c.subscribeWith().topicFilter(it.topics.status).qos(MqttQos.AT_MOST_ONCE).send() }
        log("Abonniert: ${devices.size} Geräte")
    }

    override suspend fun poll(nowMs: Long): SystemSnapshot = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("Nicht verbunden")
        devices.forEach {
            c.publishWith().topic(it.topics.command).qos(MqttQos.AT_MOST_ONCE)
                .payload(CloudPayloads.POLL_RUNTIME.toByteArray(Charsets.US_ASCII)).send()
        }
        // Give the devices a moment to answer on their status topics.
        delay(2500)

        val batteries = mutableListOf<BatteryState>()
        var meter: MeterReading? = null
        for (d in devices) {
            val entry = latest[d.topics.status] ?: continue
            if (nowMs - entry.second > STALE_MS) continue
            val fields = CloudPayloads.parseFields(entry.first)
            if (d.isBattery) {
                CloudPayloads.parseBattery(fields, d.devid, d.name, d.type, entry.second)?.let { batteries += it }
            } else {
                CloudPayloads.parseMeter(fields, entry.second)?.let { meter = it }
            }
        }
        if (batteries.isNotEmpty() || meter != null) lastDataMs = nowMs
        statusDetail = "verbunden, ${batteries.size} Speicher" + (if (meter != null) " + CT002" else "")
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

    override fun close() {
        connected = false
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
            type.startsWith("HME-4") || type.startsWith("HME-2") -> version!!.length == 3 && fw >= 122
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
    }
}
