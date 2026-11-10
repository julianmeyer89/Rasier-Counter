package de.rasier.speicher.server

import de.rasier.speicher.control.AutomationSettings
import de.rasier.speicher.control.CalibrationSettings
import de.rasier.speicher.energy.SystemConfig
import de.rasier.speicher.energy.Tariff
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun demoConfig(dir: File, write: Boolean = true) = ServerConfig(
    demo = true, cloudBaseUrl = "", email = "", passwordMd5 = "", certDir = "certs", brokerUrl = null,
    dataDir = dir.path, webPort = 0, authToken = "", pollIntervalSeconds = 30, cloudHttpIntervalSeconds = 300,
    automationsMayWrite = write, useNoFlashCommands = false, debug = false,
    tariff = Tariff(30.0, 8.0), system = SystemConfig(),
    automation = AutomationSettings(calibration = CalibrationSettings(holdFullMinutes = 30, holdEmptyMinutes = 10, intervalDays = 0)),
)

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
        val changed = hub.settingsDto().copy(importCentPerKwh = 42.5, pollIntervalSeconds = 15, automationsMayWrite = true)
        hub.updateSettings(changed)

        // A fresh hub (as after an update/restart) must read the saved settings, not the config file.
        val again = ServerHub(demoConfig(dir), SimDeviceLink(zone), dir, zone)
        assertEquals(42.5, again.settingsDto().importCentPerKwh, 1e-9)
        assertEquals(15, again.settingsDto().pollIntervalSeconds)
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

    private fun get(url: String): String =
        (URI(url).toURL().openConnection() as HttpURLConnection).inputStream.bufferedReader().readText()

    private fun code(url: String, token: String?): Int {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        if (token != null) c.setRequestProperty("X-Auth-Token", token)
        return c.responseCode
    }
}
