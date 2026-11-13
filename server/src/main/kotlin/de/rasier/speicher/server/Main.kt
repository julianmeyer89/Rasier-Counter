package de.rasier.speicher.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Entry point for the VM. Reads config, connects the device link (cloud or demo), starts the web
 * server, and runs the poll loop until the process is stopped.
 */
/** Systemd kills the process after its stop timeout; stay well inside it so the save still lands. */
private const val SHUTDOWN_SAVE_MS = 15_000L

fun main(args: Array<String>) {
    val configFile = args.firstOrNull()?.let { File(it) } ?: File("config.properties")
    val config = ServerConfig.load(configFile)
    val zone = runCatching { java.time.ZoneId.of(config.timeZone) }.getOrElse { java.time.ZoneId.of("Europe/Berlin") }
    // Explicit pattern: LocalTime.toString() drops the seconds whenever they happen to be zero.
    val stamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    val log: (String) -> Unit = { println("[${java.time.LocalTime.now(zone).format(stamp)}] $it") }

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

    if (config.timeZone != zone.id) log("Zeitzone '${config.timeZone}' unbekannt, nutze $zone.")
    log("Zeitzone: $zone (Tagesgrenzen, Zeitpläne und Beschriftungen)")
    val link: DeviceLink = if (config.demo) SimDeviceLink(zone) else CloudDeviceLink(config, log)
    val hub = ServerHub(config, link, File(config.dataDir), zone, log)
    val web = WebServer(hub, config.webPort, config.authToken)

    Runtime.getRuntime().addShutdownHook(Thread {
        log("Beende…")
        // Save first: the periodic save only runs once a minute, and updates restart the service.
        // Bounded, because an unbounded save blocked on a poll that was itself stuck on the cloud: on
        // 13.11. systemd waited 90 s and then SIGKILLed the process, which lost the very minute this
        // hook exists to save and held up the update.
        val saved = runCatching { runBlocking { withTimeoutOrNull(SHUTDOWN_SAVE_MS) { hub.shutdown() } } }.getOrNull()
        if (saved == null) log("Speichern beim Beenden abgebrochen (dauerte länger als ${SHUTDOWN_SAVE_MS / 1000} s).")
        runCatching { web.stop() }
        runCatching { link.close() }
    })

    web.start()
    log("Weboberfläche: http://0.0.0.0:${config.webPort}" + if (config.authToken.isNotBlank()) " (Token nötig)" else "")
    if (config.authToken.isBlank())
        log("Hinweis: kein auth_token gesetzt – jeder, der den Port erreicht, kann die Speicher steuern.")
    // Once settings have been saved from the dashboard, user_settings.json is applied over config at
    // startup, so the matching entries in config.properties no longer do anything. Print the values
    // that actually apply, or editing the file looks like it worked when it did not.
    val eff = hub.settingsDto()
    log("Abfrage: CT002 alle ${hub.ct002PollIntervalSeconds} s, Speicher alle ${hub.batteryPollIntervalSeconds} s")
    log("Wirkungsgrad ${eff.inverterEfficiencyPercent} %, Wechselrichter max. ${eff.inverterMaxW} W, " +
        "Arbeitspreis ${eff.importCentPerKwh} ct/kWh")
    log("Diese Werte stammen aus den Einstellungen der Weboberfläche. Gleichnamige Einträge in " +
        "config.properties werden dadurch überschrieben und bleiben wirkungslos.")

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
            // The loop runs at the CT002 rate; the hub polls the batteries on their own longer interval.
            // Read it each round so a change in the settings takes effect live.
            val interval = (hub.ct002PollIntervalSeconds.coerceIn(0.5, 3600.0) * 1000).toLong()
            delay((interval - (System.currentTimeMillis() - started)).coerceAtLeast(200))
        }
    }
}
