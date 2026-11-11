package de.rasier.speicher.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Entry point for the VM. Reads config, connects the device link (cloud or demo), starts the web
 * server, and runs the poll loop until the process is stopped.
 */
fun main(args: Array<String>) {
    val configFile = args.firstOrNull()?.let { File(it) } ?: File("config.properties")
    val config = ServerConfig.load(configFile)
    val log: (String) -> Unit = { println("[${java.time.LocalTime.now().withNano(0)}] $it") }

    // Diagnostic probe mode: `server <config> probe [ct002|battery] [intervalSec] [durationSec]`.
    // Measures how cleanly a given poll interval is received. Changes nothing; exits when done.
    if (args.any { it == "probe" }) {
        val rest = args.dropWhile { it != "probe" }.drop(1)
        val onlyMeter = rest.getOrNull(0)?.lowercase() != "battery"
        val intervalMs = ((rest.getOrNull(1)?.toDoubleOrNull() ?: 2.0) * 1000).toLong()
        val durationS = rest.getOrNull(2)?.toLongOrNull() ?: 300
        val link = CloudDeviceLink(config, log)
        runBlocking {
            log("PROBE-Modus (${if (onlyMeter) "CT002" else "Speicher"}). Verbinde…")
            link.connect()
            link.probe(onlyMeter, intervalMs, durationS * 1000)
            link.close()
        }
        return
    }

    log("Rasier Speicher Server startet (${if (config.demo) "Demo" else "Live"})")

    val link: DeviceLink = if (config.demo) SimDeviceLink() else CloudDeviceLink(config, log)
    val hub = ServerHub(config, link, File(config.dataDir), log = log)
    val web = WebServer(hub, config.webPort, config.authToken)

    Runtime.getRuntime().addShutdownHook(Thread {
        log("Beende…")
        runCatching { web.stop() }
        runCatching { link.close() }
    })

    web.start()
    log("Weboberfläche: http://0.0.0.0:${config.webPort}" + if (config.authToken.isNotBlank()) " (Token nötig)" else "")

    runBlocking {
        var connected = false
        while (!connected) {
            try {
                link.connect()
                connected = true
            } catch (e: Exception) {
                log("Verbindung fehlgeschlagen: ${e.message}. Neuer Versuch in 30 s.")
                delay(30_000)
            }
        }
        while (true) {
            val started = System.currentTimeMillis()
            runCatching { hub.poll() }.onFailure { log("Abfragefehler: ${it.message}") }
            // Read the interval from the hub each round so a change in the settings takes effect live.
            val interval = hub.pollIntervalSeconds.coerceIn(5, 3600) * 1000L
            delay((interval - (System.currentTimeMillis() - started)).coerceAtLeast(1000))
        }
    }
}
