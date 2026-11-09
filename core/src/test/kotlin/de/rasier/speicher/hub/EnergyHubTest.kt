package de.rasier.speicher.hub

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.protocol.b2500.B2500Client
import de.rasier.speicher.protocol.b2500.B2500Protocol
import de.rasier.speicher.protocol.b2500.BleSession
import de.rasier.speicher.protocol.hex
import de.rasier.speicher.settings.AppSettings
import de.rasier.speicher.settings.BatteryConfig
import de.rasier.speicher.settings.DataMode
import de.rasier.speicher.settings.FileStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnergyHubTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `demo mode produces snapshots, statistics and survives a restart`() = runTest {
        val dir = Files.createTempDirectory("hub").toFile()
        val hub = EnergyHub(AppSettings(mode = DataMode.DEMO), FileStore(dir), HubDevices(), zone)
        val start = System.currentTimeMillis()
        for (i in 0..30) hub.poll(start + i * 60_000L)
        val s = hub.state.value
        assertEquals(2, s.snapshot!!.batteries.size)
        assertNotNull(s.snapshot.meter)
        assertTrue(s.history.size >= 30)
        assertTrue(s.days.isNotEmpty())
        hub.close()

        val again = EnergyHub(AppSettings(mode = DataMode.DEMO), FileStore(dir), HubDevices(), zone)
        assertTrue(again.state.value.days.isNotEmpty(), "Statistik nach Neustart vorhanden")
        assertTrue(again.state.value.history.size >= 30)
    }

    @Test
    fun `live mode reads batteries over bluetooth`() = runTest {
        val runtime = hex(
            "73 10 23 03 01 01 78 00 5f 00 8d 02 6e 00 03 01 01 01 5a 00 00 00 b7 05 64 00 62 00 00 00 00 0e 05 15 00 17 00 00 00 09 " +
                "2c 03 00 00 80 02 00 00 fa 05 00 00 ba 04 00 00 00 00 00 00 85",
        )
        val written = mutableListOf<String>()
        val session = object : BleSession {
            override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
                if (B2500Protocol.commandOf(frame) != 0x03) written += frame.joinToString("") { "%02x".format(it) }
                return if (B2500Protocol.commandOf(frame) == 0x03) runtime else null
            }
            override fun close() {}
        }
        val settings = AppSettings(
            mode = DataMode.LIVE,
            batteries = listOf(BatteryConfig("AA:00", "Speicher 1"), BatteryConfig("AA:01", "Speicher 2")),
            ct002Ip = "",
        )
        val dir = Files.createTempDirectory("hub").toFile()
        val hub = EnergyHub(settings, FileStore(dir), HubDevices(ble = B2500Client({ session }, gapMs = 0)), zone)
        hub.poll()
        val s = hub.state.value
        assertEquals(listOf(65, 65), s.snapshot!!.batteries.map { it.socPercent })
        assertTrue(s.sources.all { it.ok }, s.sources.toString())

        // Manual write goes out over Bluetooth (DoD 80 % = 73 06 23 0b 50 0d).
        hub.sendManual(BatteryCommand.SetDod("AA:00", 80))
        assertTrue("7306230b500d" in written)
    }

    @Test
    fun `write guard limits automatic writes per day`() {
        val g = WriteGuard(maxPerDay = 2)
        val t = 1_767_225_600_000L
        repeat(2) { assertTrue(g.allow("a", t, zone)); g.record("a", t, zone) }
        assertTrue(!g.allow("a", t, zone))
        assertTrue(g.allow("a", t + 86_400_000L, zone))
    }
}
