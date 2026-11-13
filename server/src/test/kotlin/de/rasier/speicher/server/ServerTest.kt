package de.rasier.speicher.server

import de.rasier.speicher.control.AutomationSettings
import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.control.CalibrationSettings
import de.rasier.speicher.energy.SystemConfig
import de.rasier.speicher.energy.Tariff
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun demoConfig(dir: File, write: Boolean = true) = ServerConfig(
    demo = true, cloudBaseUrl = "", email = "", passwordMd5 = "", certDir = "certs", brokerUrl = null,
    dataDir = dir.path, webPort = 0, authToken = "", ct002PollIntervalSeconds = 2.0, batteryPollIntervalSeconds = 5, selfDischargeAlertW = 25, calibrationDeviationPercent = 15,
    automationsMayWrite = write, useNoFlashCommands = false, debug = false,
    tariff = Tariff(30.0, 8.0), system = SystemConfig(),
    automation = AutomationSettings(calibration = CalibrationSettings(holdMinutes = 30, intervalDays = 0)),
)

/** Accepts every command and reports success, but never applies it - like a lost cloud command. */
private class DeafLink(zone: ZoneId) : DeviceLink {
    private val sim = de.rasier.speicher.sim.SimulatedSystem(zone)
    var sent = 0
        private set

    override suspend fun connect() {}
    override suspend fun poll(nowMs: Long, pollMeter: Boolean, pollBattery: Boolean, replyWaitMs: Long) = sim.advance(nowMs)
    override suspend fun send(command: BatteryCommand): Boolean { sent++; return true }
    override val batteryIds: List<String> get() = sim.batteryIds
    override val status: LinkStatus get() = LinkStatus(true, "Test", null)
    override fun close() {}
}

class ServerHubTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `demo poll produces state and statistics`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        val start = System.currentTimeMillis()
        for (i in 0..20) hub.poll(start + i * 60_000L)
        val s = hub.state.value
        assertEquals(2, s.batteries.size)
        assertTrue(s.meter != null)
        assertTrue(s.group.socPercent != null)
        assertTrue(hub.history(24).points.size >= 20)
    }

    @Test
    fun `calibration can be started and shows in state`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        hub.poll()
        hub.startCalibration(emptyList())
        val c = hub.state.value.calibration
        assertTrue(c != null && c.running)
        hub.abortCalibration()
        assertTrue(hub.state.value.calibration?.running == false)
    }

    @Test
    fun `a mode switch briefly pauses CT002 polling`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val link = SimDeviceLink(zone)
        val hub = ServerHub(demoConfig(dir), link, dir, zone)
        hub.poll()
        val before = link.meterPolls
        hub.manualCommand(BatteryCommand.SetAdaptiveMode(link.batteryIds.first(), false))
        hub.poll() // within the pause window -> CT002 not polled
        assertEquals(before, link.meterPolls)
    }

    @Test
    fun `a setting the device ignores is re-sent and then reported`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val link = DeafLink(zone)
        // Without the balancer: the simulated batteries start 15 % apart, so it would legitimately
        // hold one of them and its command would be counted here as a re-send.
        val cfg = demoConfig(dir).let {
            it.copy(automation = it.automation.copy(balancer = de.rasier.speicher.control.BalancerSettings(enabled = false)))
        }
        val hub = ServerHub(cfg, link, dir, zone)
        // Real wall-clock times: manualCommand stamps the command with the system clock.
        val t0 = System.currentTimeMillis()
        hub.poll(t0)
        hub.manualCommand(BatteryCommand.SetDod(link.batteryIds.first(), 33))
        assertEquals(1, link.sent)

        var t = t0
        repeat(8) { t += ServerHub.CONFIRM_GRACE_MS + 1000; hub.poll(t) }

        assertEquals(ServerHub.MAX_SEND_ATTEMPTS, link.sent, "Befehl muss erneut gesendet, aber nicht endlos wiederholt werden")
        assertTrue(hub.state.value.alerts.any { it.title == "Einstellung kam nicht an" })
    }

    @Test
    fun `a setting the device applies is not re-sent`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val link = SimDeviceLink(zone)
        val hub = ServerHub(demoConfig(dir), link, dir, zone)
        val t0 = System.currentTimeMillis()
        hub.poll(t0)
        hub.manualCommand(BatteryCommand.SetDod(link.batteryIds.first(), 33))
        var t = t0
        repeat(4) { t += ServerHub.CONFIRM_GRACE_MS + 1000; hub.poll(t) }
        assertTrue(hub.state.value.alerts.none { it.title == "Einstellung kam nicht an" })
        assertEquals(33, hub.state.value.batteries.first { it.id == link.batteryIds.first() }.dodPercent)
    }

    @Test
    fun `meter entry feeds the statistics`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        hub.addMeterEntry(de.rasier.speicher.energy.MeterEntry(1_000_000_000_000L, 10000.0, 20.0))
        hub.addMeterEntry(de.rasier.speicher.energy.MeterEntry(1_000_000_000_000L + 7 * 86_400_000L, 10035.0, 22.0))
        val stats = hub.stats(java.time.LocalDate.of(2001, 9, 1), java.time.LocalDate.of(2001, 9, 30), ServerHub.Bucket.DAY, "Sep 2001")
        assertEquals(35.0, stats.meterPeriods.single().importKwh, 1e-9)
        assertEquals(35.0, stats.meterTotal!!.importKwh, 1e-9)
    }

    @Test
    fun `dashboard settings survive a restart`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        val changed = hub.settingsDto().copy(
            importCentPerKwh = 42.5, ct002PollIntervalSeconds = 1.5, batteryPollIntervalSeconds = 15, automationsMayWrite = true,
        )
        hub.updateSettings(changed)

        // A fresh hub (as after an update/restart) must read the saved settings, not the config file.
        val again = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        assertEquals(42.5, again.settingsDto().importCentPerKwh, 1e-9)
        assertEquals(1.5, again.settingsDto().ct002PollIntervalSeconds, 1e-9)
        assertEquals(15, again.settingsDto().batteryPollIntervalSeconds)
        assertTrue(again.settingsDto().automationsMayWrite)
    }
}

class WebServerTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private var web: WebServer? = null

    @AfterTest
    fun tearDown() { web?.stop() }

    @Test
    fun `web server serves state and the dashboard`() = runTest {
        val dir = Files.createTempDirectory("web").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        hub.poll()
        // Pick a free port.
        val port = java.net.ServerSocket(0).use { it.localPort }
        val w = WebServer(hub, port, authToken = "")
        web = w
        w.start()

        val state = get("http://127.0.0.1:$port/api/state")
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString(StateDto.serializer(), state)
        assertEquals(2, dto.batteries.size)

        val html = get("http://127.0.0.1:$port/")
        assertTrue(html.contains("Heim PV Anlage"))

        val js = get("http://127.0.0.1:$port/app.js")
        assertTrue(js.contains("renderOverview"))
    }

    @Test
    fun `auth token is enforced on the api`() = runTest {
        val dir = Files.createTempDirectory("web").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        hub.poll()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val w = WebServer(hub, port, authToken = "geheim")
        web = w
        w.start()

        assertEquals(401, code("http://127.0.0.1:$port/api/state", null))
        assertEquals(200, code("http://127.0.0.1:$port/api/state", "geheim"))
    }

    @Test
    fun `write requests are only accepted from the dashboard and only for known batteries`() = runTest {
        val dir = Files.createTempDirectory("web").toFile()
        val link = SimDeviceLink(zone)
        val hub = ServerHub(demoConfig(dir), link, dir, zone)
        hub.poll()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val w = WebServer(hub, port, authToken = "")
        web = w
        w.start()
        val base = "http://127.0.0.1:$port"
        val cmd = """{"batteryId":"${link.batteryIds.first()}","type":"dod","value":50}"""

        // A plain cross-site form POST cannot set this content type.
        assertEquals(403, post("$base/api/command", cmd, "text/plain"), "falscher Content-Type durchgelassen")
        assertEquals(403, post("$base/api/command", cmd, null), "ohne Content-Type durchgelassen")
        // A foreign origin is refused even with the right content type.
        assertEquals(403, post("$base/api/command", cmd, "application/json", origin = "http://evil.example"), "fremder Origin durchgelassen")
        // The dashboard itself still works.
        assertEquals(200, post("$base/api/command", cmd, "application/json"), "Dashboard selbst blockiert")
        // The meter and anything else is not a valid target.
        assertEquals(400, post("$base/api/command", """{"batteryId":"fremd","type":"dod","value":50}""", "application/json"), "fremde Geraete-ID akzeptiert")
        // Static files are served by name only, not by path.
        assertEquals(404, code("$base/unterordner/app.js", null), "Pfad statt Dateiname bedient")
    }

    // HttpURLConnection refuses to send an Origin header, so the modern client is used here.
    private fun post(url: String, body: String, contentType: String?, origin: String? = null): Int {
        val b = java.net.http.HttpRequest.newBuilder(URI(url))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        if (contentType != null) b.header("Content-Type", contentType)
        if (origin != null) b.header("Origin", origin)
        return java.net.http.HttpClient.newHttpClient()
            .send(b.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(url: String): String =
        (URI(url).toURL().openConnection() as HttpURLConnection).inputStream.bufferedReader().readText()

    private fun code(url: String, token: String?): Int {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        if (token != null) c.setRequestProperty("X-Auth-Token", token)
        return c.responseCode
    }
}

/** Reports batteries but never a meter reading, like a CT002 that went silent. */
private class SilentMeterLink(zone: ZoneId) : DeviceLink {
    private val sim = de.rasier.speicher.sim.SimulatedSystem(zone)
    override suspend fun connect() {}
    override suspend fun poll(nowMs: Long, pollMeter: Boolean, pollBattery: Boolean, replyWaitMs: Long) =
        sim.advance(nowMs).copy(meter = null)
    override suspend fun send(command: BatteryCommand) = sim.execute(command)
    override val batteryIds: List<String> get() = sim.batteryIds
    override val status: LinkStatus get() = LinkStatus(true, "Test", null)
    override fun close() {}
}

/** The fixes for M1 to M8, as far as they are visible from the server. */
class ServerFixesTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `the time zone defaults to german time, not the machine's`() {
        assertEquals("Europe/Berlin", ServerConfig.load(null).timeZone)
    }

    @Test
    fun `a silent meter raises a warning and is not shown as a current value`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir), SilentMeterLink(zone), dir, zone)
        val t0 = System.currentTimeMillis()
        hub.poll(t0)
        assertTrue(hub.state.value.alerts.none { it.key == "meter-down" }, "zu frueh gewarnt")
        hub.poll(t0 + ServerHub.METER_ALERT_MS + 60_000L)
        assertTrue(hub.state.value.alerts.any { it.key == "meter-down" }, "Warnung fehlt")
        assertNull(hub.state.value.meter, "veralteter Zaehlerwert wird weiter angezeigt")
    }

    @Test
    fun `a calibration is refused while the automation may not write`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir, write = false), SimDeviceLink(zone), dir, zone)
        hub.poll()
        val blockers = hub.startCalibration(emptyList())
        assertTrue(blockers.any { it.contains("Automatik") }, blockers.toString())
        assertTrue(hub.state.value.calibration == null, "trotz Hindernis gestartet")
        // With force the user can still start it.
        assertTrue(hub.startCalibration(emptyList(), force = true).isEmpty())
        assertTrue(hub.state.value.calibration?.running == true)
    }

    @Test
    fun `with writing off the calibration does not pretend to progress and suggestions stay`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir, write = false), SimDeviceLink(zone), dir, zone)
        val t0 = System.currentTimeMillis()
        hub.poll(t0)
        hub.startCalibration(emptyList(), force = true)
        hub.poll(t0 + 2000)
        val first = hub.state.value.calibration?.phase
        val suggestions = hub.state.value.suggestions.size
        assertTrue(suggestions > 0, "keine Vorschlaege")
        repeat(3) { hub.poll(t0 + 4000 + it * 2000L) }
        assertEquals(first, hub.state.value.calibration?.phase, "Lauf ist ohne gesendete Befehle weitergelaufen")
        assertEquals(suggestions, hub.state.value.suggestions.size, "Vorschlaege sind verschwunden")
    }

    @Test
    fun `the stored history is the average of the minute, not one instant`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        val t0 = System.currentTimeMillis()
        repeat(40) { hub.poll(t0 + it * 2000L) }   // more than a minute of polls
        val points = hub.history(24).points
        assertTrue(points.isNotEmpty(), "kein Verlaufspunkt")
        val p = points.last()
        assertTrue(p.ids.size == 2, "Verlaufspunkt nennt die Speicher nicht: ${p.ids}")
        assertTrue(p.gridMinW != null && p.gridMaxW != null, "Spitzen fehlen")
        val mean = p.gridW!!
        assertTrue(p.gridMinW!! <= mean && mean <= p.gridMaxW!!, "Mittelwert liegt nicht zwischen Min und Max")
    }
}

/** Reports batteries whose capacity the device stops sending (it is undefined at 0 %). */
private class CapacityDropLink(zone: ZoneId) : DeviceLink {
    private val sim = de.rasier.speicher.sim.SimulatedSystem(zone)
    var drop = false
    override suspend fun connect() {}
    override suspend fun poll(nowMs: Long, pollMeter: Boolean, pollBattery: Boolean, replyWaitMs: Long) =
        sim.advance(nowMs).let { s -> if (!drop) s else s.copy(batteries = s.batteries.map { it.copy(capacityWh = null) }) }
    override suspend fun send(command: BatteryCommand) = sim.execute(command)
    override val batteryIds: List<String> get() = sim.batteryIds
    override val status: LinkStatus get() = LinkStatus(true, "Test", null)
    override fun close() {}
}

/** The smaller findings N1 to N7. */
class ServerSmallFixesTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `shutdown writes the last minute to disk`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        val t0 = System.currentTimeMillis()
        // The first poll already writes a point; the following ones only accumulate, because both the
        // minute of the history and the save interval have not elapsed again.
        repeat(5) { hub.poll(t0 + it * 2000L) }
        val before = de.rasier.speicher.settings.FileStore(dir).loadHistory().size
        hub.shutdown()
        val after = de.rasier.speicher.settings.FileStore(dir).loadHistory().size
        assertTrue(after > before, "beim Beenden wurde der angefangene Verlauf nicht gespeichert ($before -> $after)")
    }

    @Test
    fun `statistics drop the same gaps the daily total drops`() = runTest {
        // The daily total skips an interval longer than maxGapMs (5 min). The statistics used to use
        // a hard-coded 10 min, so a 7-minute gap went into the hourly and 5-minute views but not into
        // the day's total - the server showed two different solar yields for the same day.
        val noon = java.time.LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        suspend fun yieldWithGap(gapMs: Long): Double {
            val dir = Files.createTempDirectory("gap").toFile()
            val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
            hub.poll(noon)
            hub.poll(noon + ServerHub.HISTORY_SAMPLE_MS)
            hub.poll(noon + ServerHub.HISTORY_SAMPLE_MS + gapMs)
            val day = java.time.LocalDate.now(zone)
            return hub.stats(day, day, ServerHub.Bucket.HOUR, "t").total.pvWh
        }
        val short = yieldWithGap(2 * 60_000L)
        val long = yieldWithGap(7 * 60_000L)
        assertTrue(short > 0.0, "ohne Luecke muss Ertrag gezaehlt werden (war $short)")
        // Both runs have the same first minute; the 7-minute interval on top must not be counted.
        assertTrue(long <= short, "die 7-Minuten-Luecke wurde in der Statistik mitgezaehlt ($long > $short)")
        assertTrue(long < short * 1.6, "die 7-Minuten-Luecke schlaegt in der Statistik durch ($long gegen $short)")
    }

    @Test
    fun `the automation state is not rewritten on every poll`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val hub = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        val t0 = System.currentTimeMillis()
        hub.poll(t0)
        val f = File(dir, "automation.json")
        val first = f.lastModified()
        repeat(5) { hub.poll(t0 + 1000L + it * 1000L) }
        assertEquals(first, f.lastModified(), "automation.json wird bei jeder Abfrage neu geschrieben")
    }

    @Test
    fun `a capacity the device stops reporting is kept`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val link = CapacityDropLink(zone)
        val hub = ServerHub(demoConfig(dir), link, dir, zone)
        val t0 = System.currentTimeMillis()
        hub.poll(t0)
        val known = hub.state.value.batteries.first().capacityWh
        assertTrue(known != null && known > 0)
        link.drop = true
        hub.poll(t0 + 6000)
        assertEquals(known, hub.state.value.batteries.first().capacityWh, "Kapazitaet geht bei 0 % verloren")
    }

    @Test
    fun `a battery that cannot discharge is pointed out`() = runTest {
        val dir = Files.createTempDirectory("srv").toFile()
        val link = SimDeviceLink(zone)
        val hub = ServerHub(demoConfig(dir), link, dir, zone)
        hub.poll()
        hub.manualCommand(BatteryCommand.SetDod(link.batteryIds.first(), 0))
        hub.poll()
        assertTrue(hub.state.value.alerts.any { it.key.startsWith("dod-") }, "kein Hinweis auf Entladetiefe 0 %")
    }
}

