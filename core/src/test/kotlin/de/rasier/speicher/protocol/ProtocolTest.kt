package de.rasier.speicher.protocol

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.ChargeMode
import de.rasier.speicher.protocol.b2500.B2500Client
import de.rasier.speicher.protocol.b2500.B2500Parser
import de.rasier.speicher.protocol.b2500.B2500Protocol
import de.rasier.speicher.protocol.b2500.BleSession
import de.rasier.speicher.protocol.b2500.toBatteryState
import de.rasier.speicher.protocol.ct002.Ct002Protocol
import de.rasier.speicher.protocol.ct002.Ct002Reader
import de.rasier.speicher.protocol.ct002.Ct002Reply
import kotlinx.coroutines.test.runTest
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Test vectors from docs/PROTOKOLLE.md (checksums verified independently). */
class B2500ProtocolTest {
    @Test
    fun `read frames`() {
        assertContentEquals(hex("73 06 23 03 01 54"), B2500Protocol.readFrame(B2500Protocol.Read.RUNTIME))
        assertContentEquals(hex("73 06 23 13 00 45"), B2500Protocol.readFrame(B2500Protocol.Read.TIMERS))
        assertContentEquals(hex("73 06 23 04 01 53"), B2500Protocol.readFrame(B2500Protocol.Read.DEVICE_INFO))
        assertContentEquals(hex("73 06 23 0F 01 58"), B2500Protocol.readFrame(B2500Protocol.Read.CELLS))
    }

    @Test
    fun `setting frames`() {
        assertContentEquals(hex("73 06 23 0b 50 0d"), B2500Protocol.encode(BatteryCommand.SetDod("x", 80)))
        assertContentEquals(hex("73 06 23 0b 5a 07"), B2500Protocol.encode(BatteryCommand.SetDod("x", 90)))
        assertContentEquals(hex("73 06 23 0d 00 5b"), B2500Protocol.encode(BatteryCommand.SetChargeMode("x", ChargeMode.SIMULTANEOUS)))
        assertContentEquals(hex("73 06 23 0d 01 5a"), B2500Protocol.encode(BatteryCommand.SetChargeMode("x", ChargeMode.CHARGE_THEN_DISCHARGE)))
        assertContentEquals(hex("73 06 23 11 00 47"), B2500Protocol.encode(BatteryCommand.SetAdaptiveMode("x", true)))
    }

    @Test
    fun `dangerous commands are blocked`() {
        for (cmd in listOf(0x05, 0x20, 0x21, 0x25, 0x26, 0x2A)) {
            assertFailsWith<IllegalArgumentException> { B2500Protocol.frame(cmd) }
        }
    }

    private val runtimeFrame = hex(
        "73 10 23 03 01 01 78 00 5f 00 8d 02 6e 00 03 01 01 01 5a 00 00 00 b7 05 64 00 62 00 00 00 00 0e 05 15 00 17 00 00 00 09 " +
            "2c 03 00 00 80 02 00 00 fa 05 00 00 ba 04 00 00 00 00 00 00 85",
    )

    @Test
    fun `runtime reply is decoded`() {
        val r = assertNotNull(B2500Parser.runtime(runtimeFrame))
        assertEquals(listOf(120, 95), r.pvInputW)
        assertEquals(65.3, r.socPercent, 1e-9)
        assertEquals("110.9", r.firmware)
        assertEquals(ChargeMode.SIMULTANEOUS, r.chargeMode)
        assertEquals(listOf(true, true), r.outputEnabled)
        assertEquals(90, r.dodPercent)
        assertEquals(1463, r.remainingWh)
        assertEquals(listOf(100, 98), r.outputW)
        assertEquals(14, r.clockHour)
        assertEquals(5, r.clockMinute)
        assertEquals(21, r.temperatureLowC)
        assertEquals(23, r.temperatureHighC)
        assertEquals(812L, r.dailyBatteryChargeWh)
        assertEquals(640L, r.dailyBatteryDischargeWh)
        assertEquals(1530L, r.dailyPvWh)
        assertEquals(1210L, r.dailyOutputWh)
    }

    @Test
    fun `timers reply with five slots`() {
        val t = assertNotNull(
            B2500Parser.timers(
                hex(
                    "73 3b 23 13 00 01 13 00 16 1b bc 02 01 16 1c 17 3b f4 01 01 00 00 02 00 bc 02 00 28 00 00 00 78 00 ff 00 5e 01 00 00 00 00 00 00 " +
                        "01 08 00 0d 3b f4 01 00 00 00 17 3b 50 00 07 f7",
                ),
            ),
        )
        assertEquals(false, t.adaptiveMode)
        assertEquals(5, t.slots.size)
        assertEquals(19, t.slots[0].startHour)
        assertEquals(22, t.slots[0].endHour)
        assertEquals(27, t.slots[0].endMinute)
        assertEquals(700, t.slots[0].powerW)
        assertEquals(500, t.slots[3].powerW)
        assertEquals(8, t.slots[3].startHour)
        assertEquals(false, t.slots[4].enabled)
    }

    @Test
    fun `device info and cells`() {
        val info = "type=HMJ-2,id=0123456789abcdef01234567,mac=aabbccddeeff".toByteArray()
        val frame = B2500Protocol.frame(0x04, info)
        val d = assertNotNull(B2500Parser.deviceInfo(frame))
        assertEquals("HMJ-2", d.type)
        assertEquals("aabbccddeeff", d.mac)

        val cells = "10_24_25_3162_3161_3156_3156_3152_3162_3156_3156_3151_3153_3153_3153_3147_3155".toByteArray()
        assertTrue(B2500Protocol.isCellInfo(cells))
        val c = assertNotNull(B2500Parser.cells(cells))
        assertEquals(10, c.socPercent)
        assertEquals(14, c.cellMillivolts.size)
        assertEquals(3162, c.cellMillivolts.first())
    }

    @Test
    fun `client reads over a fake session and converts to battery state`() = runTest {
        val session = object : BleSession {
            var calls = 0
            override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
                calls++
                // First request after connect is ignored, as observed on real devices.
                return if (calls == 1) null else runtimeFrame
            }
            override fun close() {}
        }
        val client = B2500Client({ session }, gapMs = 0)
        val reading = client.read("AA:BB", withDetails = false)
        val state = reading.toBatteryState("AA:BB", "Speicher 1", 1000, capacityWh = null)
        assertEquals(65, state.socPercent)
        assertEquals(198, state.totalOutputW)
        assertEquals(215, state.totalPvW)
        assertEquals(2240, state.capacityWh!!, "1463 Wh / 65.3 %")
    }
}

class Ct002ProtocolTest {
    @Test
    fun `passive request matches the verified vector`() {
        val expected = hex("010235337c564e5345332d307c3032613537653132333435367c484d452d347c3030303030303030303030307c307c307c30033330")
        assertContentEquals(expected, Ct002Protocol.passiveRequest("02a57e123456"))
    }

    @Test
    fun `golden frames decode`() {
        val f = assertNotNull(Ct002Protocol.decode(hex("010235337c484d472d35307c4141424243434444454546467c484d452d337c3131323233333434353536367c427c2d313030033663")))
        assertEquals(listOf("HMG-50", "AABBCCDDEEFF", "HME-3", "112233445566", "B", "-100"), f)
        // Checksum with a space instead of the leading zero must be accepted.
        assertNotNull(Ct002Protocol.decode(hex("010235307c484d472d35307c4141424243434444454546467c484d452d347c3131323233333434353536367c307c30032036")))
        // Corrupted checksum is rejected.
        assertNull(Ct002Protocol.decode(hex("010235307c484d472d35307c4141424243434444454546467c484d452d347c3131323233333434353536367c307c30033337")))
    }

    @Test
    fun `hme4 reply is parsed`() {
        val fields = assertNotNull(
            Ct002Protocol.decode(
                hex(
                    "010239377c484d452d347c6161626263636464656566667c564e5345332d307c3032613537653132333435367c3130307c2d3230307c35307c2d35307c307c307c307c307c2d35307c377c307c307c307c307c307c307c307c307c307c30033638",
                ),
            ),
        )
        val r = assertNotNull(Ct002Reply.parse(fields))
        assertEquals(listOf(100, -200, 50), r.phaseW)
        assertEquals(-50, r.totalW)
        assertEquals("02a57e123456", r.requesterId)
        assertEquals(-50, r.wifiRssi)
        assertNull(r.importWh)
    }

    @Test
    fun `ct003 style reply carries energy counters`() {
        val fields = assertNotNull(
            Ct002Protocol.decode(
                hex(
                    "01023131357c484d452d337c6161626263636464656566667c564e5345332d307c3032613537653132333435367c3132307c2d3334307c357c2d3231357c307c307c307c307c2d35307c377c307c307c307c307c307c307c307c307c307c307c37383630307c307c3438323630307c30033631",
                ),
            ),
        )
        val r = assertNotNull(Ct002Reply.parse(fields))
        assertEquals(7860.0, r.importWh!!, 1e-9)
        assertEquals(48260.0, r.exportWh!!, 1e-9)
    }

    @Test
    fun `encode then decode round trip with three digit length`() {
        val fields = (1..24).map { "field$it" }
        assertEquals(fields, Ct002Protocol.decode(Ct002Protocol.encode(fields)))
    }

    @Test
    fun `reader talks to a local fake CT over UDP`() {
        val fake = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val worker = thread {
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            fake.receive(p)
            val req = Ct002Protocol.decode(p.data, p.length)!!
            // The reader must stay passive: phase 0, power 0, participate 0.
            check(req.subList(4, 7) == listOf("0", "0", "0")) { "not passive: $req" }
            val reply = Ct002Protocol.encode(
                listOf("HME-4", "aabbccddeeff", req[0], req[1], "300", "-20", "15", "295", "0", "0", "0", "0", "-55", "1") + List(10) { "0" },
            )
            fake.send(DatagramPacket(reply, reply.size, p.socketAddress))
        }
        Ct002Reader("127.0.0.1", "02a57e00abcd", port = fake.localPort).use { reader ->
            val r = assertNotNull(reader.read(2_000))
            assertEquals(295, r.totalW)
            assertEquals(listOf(300, -20, 15), r.phaseW)
        }
        worker.join()
        fake.close()
    }

    @Test
    fun `reader ids are locally administered and never look like a battery`() {
        repeat(20) {
            val id = Ct002Protocol.newReaderId()
            assertEquals(12, id.length)
            assertTrue(id.startsWith("02a57e"))
        }
    }
}
