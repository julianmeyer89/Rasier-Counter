package de.rasier.speicher.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.energy.MeterEntry
import de.rasier.speicher.model.ChargeMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Serves the dashboard (static files from resources) and the JSON API. Kept to the JDK's built-in
 * HTTP server so the server has no heavy web framework. Meant to run behind Tailscale; an optional
 * shared token adds a second layer.
 */
class WebServer(
    private val hub: ServerHub,
    private val port: Int,
    private val authToken: String,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var server: HttpServer

    fun start() {
        server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newFixedThreadPool(4)

        server.createContext("/api/") { ex -> handleApi(ex) }
        server.createContext("/") { ex -> handleStatic(ex) }

        server.start()
    }

    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun handleApi(ex: HttpExchange) {
        try {
            if (!authorized(ex)) {
                send(ex, 401, "application/json", """{"error":"unauthorized"}""")
                return
            }
            val path = ex.requestURI.path.removePrefix("/api/")
            val method = ex.requestMethod
            // A plain cross-site form POST cannot set these, so this blocks a foreign page in the
            // browser of a device on the tailnet from driving the batteries.
            if (method == "POST" && !writeAllowed(ex)) {
                // Drain first: answering without reading the body can break the client's connection.
                runCatching { ex.requestBody.readNBytes(MAX_BODY_BYTES) }
                send(ex, 403, "application/json", """{"error":"forbidden"}""")
                return
            }
            when {
                path == "state" && method == "GET" -> sendJson(ex, StateDto.serializer(), hub.state.value)
                path == "history" && method == "GET" ->
                    sendJson(ex, HistoryDto.serializer(), hub.history(queryInt(ex, "hours", 24)))
                path == "stats" && method == "GET" -> {
                    val today = java.time.LocalDate.now(hub.timeZone)
                    val from = query(ex, "from")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: today.minusDays(6)
                    val to = query(ex, "to")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: today
                    val bucket = when (query(ex, "bucket")) {
                        "5min" -> ServerHub.Bucket.FIVE_MIN
                        "hour" -> ServerHub.Bucket.HOUR
                        "month" -> ServerHub.Bucket.MONTH
                        else -> ServerHub.Bucket.DAY
                    }
                    val label = query(ex, "label") ?: "$from – $to"
                    sendJson(ex, StatsDto.serializer(), hub.stats(from, to, bucket, label))
                }
                path == "batterystats" && method == "GET" -> {
                    val today = java.time.LocalDate.now(hub.timeZone)
                    val from = query(ex, "from")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: today.minusDays(6)
                    val to = query(ex, "to")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: today
                    val bucket = when (query(ex, "bucket")) {
                        "5min" -> ServerHub.Bucket.FIVE_MIN
                        "month" -> ServerHub.Bucket.MONTH
                        else -> ServerHub.Bucket.DAY
                    }
                    sendJson(ex, BatteryStatsDto.serializer(), hub.batteryStats(from, to, bucket))
                }
                path == "events" && method == "GET" ->
                    sendJson(ex, kotlinx.serialization.builtins.ListSerializer(EventDto.serializer()), hub.events())
                path == "settings" && method == "GET" -> sendJson(ex, SettingsDto.serializer(), hub.settingsDto())
                path == "settings" && method == "POST" -> {
                    val req = json.decodeFromString(SettingsDto.serializer(), body(ex))
                    runBlocking { hub.updateSettings(req) }
                    sendJson(ex, SettingsDto.serializer(), hub.settingsDto())
                }
                path == "command" && method == "POST" -> {
                    val req = json.decodeFromString(CommandRequest.serializer(), body(ex))
                    // Commands belong to a battery; the meter shares the device list but takes none.
                    if (req.batteryId !in hub.batteryIds) {
                        send(ex, 400, "application/json", """{"error":"unknown battery"}"""); return
                    }
                    val cmd = toCommand(req) ?: run { send(ex, 400, "application/json", """{"error":"bad command"}"""); return }
                    val ok = runBlocking { hub.manualCommand(cmd) }
                    send(ex, 200, "application/json", """{"ok":$ok}""")
                }
                path == "calibration/start" && method == "POST" -> {
                    val req = json.decodeFromString(CalibrationRequest.serializer(), body(ex))
                    val blockers = runBlocking { hub.startCalibration(req.batteryIds, req.force) }
                    if (blockers.isEmpty()) send(ex, 200, "application/json", """{"ok":true}""")
                    else send(ex, 200, "application/json", """{"ok":false,"reason":${jsonString(blockers.joinToString("; "))}}""")
                }
                path == "calibration/abort" && method == "POST" -> {
                    runBlocking { hub.abortCalibration() }
                    send(ex, 200, "application/json", """{"ok":true}""")
                }
                path == "meter" && method == "POST" -> {
                    val req = json.decodeFromString(MeterRequest.serializer(), body(ex))
                    runBlocking { hub.addMeterEntry(MeterEntry(req.dateMs ?: System.currentTimeMillis(), req.importKwh, req.exportKwh)) }
                    send(ex, 200, "application/json", """{"ok":true}""")
                }
                else -> send(ex, 404, "application/json", """{"error":"not found"}""")
            }
        } catch (e: Exception) {
            send(ex, 500, "application/json", """{"error":${jsonString(e.message ?: "error")}}""")
        } finally {
            ex.close()
        }
    }

    private fun handleStatic(ex: HttpExchange) {
        try {
            var path = ex.requestURI.path
            if (path == "/" || path.isBlank()) path = "/index.html"
            // Only plain file names from the bundled folder: no paths, no traversal.
            if (!path.matches(Regex("/[A-Za-z0-9._-]+"))) {
                send(ex, 404, "text/plain", "Not found")
                return
            }
            val resource = "/web$path"
            val bytes = javaClass.getResourceAsStream(resource)?.readBytes()
            if (bytes == null) {
                send(ex, 404, "text/plain", "Not found")
                return
            }
            send(ex, 200, contentType(path), bytes)
        } catch (e: Exception) {
            send(ex, 500, "text/plain", "error")
        } finally {
            ex.close()
        }
    }

    private fun authorized(ex: HttpExchange): Boolean {
        if (authToken.isBlank()) return true
        val header = ex.requestHeaders.getFirst("X-Auth-Token")
        val q = query(ex, "token")
        return header == authToken || q == authToken
    }

    private fun toCommand(req: CommandRequest): BatteryCommand? = when (req.type) {
        "dod" -> BatteryCommand.SetDod(req.batteryId, req.value)
        "chargeMode" -> BatteryCommand.SetChargeMode(req.batteryId, if (req.value == 1) ChargeMode.CHARGE_THEN_DISCHARGE else ChargeMode.SIMULTANEOUS)
        "adaptive" -> BatteryCommand.SetAdaptiveMode(req.batteryId, req.value == 1)
        // No manual "reboot" route: the restart (cd=10) is only triggered internally by the
        // calibration's zero-export recovery, never from the dashboard/API.
        else -> null
    }

    private fun <T> sendJson(ex: HttpExchange, serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        send(ex, 200, "application/json", json.encodeToString(serializer, value))

    private fun send(ex: HttpExchange, code: Int, type: String, body: String) =
        send(ex, code, type, body.toByteArray(StandardCharsets.UTF_8))

    private fun send(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** Reads the request body, bounded so a bad client cannot pull the server into swapping. */
    private fun body(ex: HttpExchange): String {
        val bytes = ex.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        require(bytes.size <= MAX_BODY_BYTES) { "Anfrage zu groß" }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    /** POSTs must look like they came from the dashboard, not from a foreign page. */
    private fun writeAllowed(ex: HttpExchange): Boolean {
        val type = ex.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        if (type != "application/json") return false
        val origin = ex.requestHeaders.getFirst("Origin") ?: return true
        val host = ex.requestHeaders.getFirst("Host") ?: return false
        return origin.substringAfter("://").equals(host, ignoreCase = true)
    }

    private fun query(ex: HttpExchange, key: String): String? =
        ex.requestURI.query?.split("&")?.firstNotNullOfOrNull {
            val (k, v) = (it.split("=", limit = 2) + "").let { p -> p[0] to p.getOrElse(1) { "" } }
            if (k == key) v else null
        }

    private fun queryInt(ex: HttpExchange, key: String, default: Int) = query(ex, key)?.toIntOrNull() ?: default

    private fun contentType(path: String) = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private companion object {
        const val MAX_BODY_BYTES = 64 * 1024
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
