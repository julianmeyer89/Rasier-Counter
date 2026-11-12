package de.rasier.speicher.protocol.cloud

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.ChargeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudPayloadsTest {
    private val runtime =
        "p1=0,p2=0,w1=120,w2=95,pe=65,vv=110,sv=9,cs=0,cd=0,am=0,o1=1,o2=1,do=90,lv=200,cj=1,kn=1456," +
            "g1=96,g2=99,b1=0,b2=0,md=0,d1=1,e1=0:0,f1=24:0,h1=200,sg=1,sp=100,st=0,tl=26,th=28,tc=0,tf=0," +
            "pt=1530,bs=640,tc_dis=0,lmo=800,lmi=800,lmf=0,ws=-62"

    @Test
    fun `battery runtime is parsed`() {
        val f = CloudPayloads.parseFields(runtime)
        assertTrue(CloudPayloads.isBatteryRuntime(f))
        val b = assertNotNull(CloudPayloads.parseBattery(f, "dev1", "Speicher 1", "HMJ-2", 1000))
        assertEquals(65, b.socPercent)
        assertEquals(2240, b.capacityWh)
        assertEquals(listOf(120, 95), b.pvInputW)
        assertEquals(listOf(96, 99), b.outputW)
        assertEquals(listOf(true, true), b.outputEnabled)
        assertEquals(90, b.dodPercent)
        assertEquals(ChargeMode.SIMULTANEOUS, b.chargeMode)
        assertEquals(false, b.adaptiveMode)
        assertEquals(true, b.surplusFeedIn)
        assertEquals("110.9", b.firmware)
        assertEquals("HMJ-2", b.deviceType)
        assertEquals(1530, b.dailyPvWh)
        assertEquals(640, b.dailyOutputWh)
        assertEquals(28, b.temperatureC)
    }

    @Test
    fun `meter runtime keeps the sign`() {
        val f = CloudPayloads.parseFields("pwr_a=100,pwr_b=-200,pwr_c=50,pwr_t=-50,slv_n=2,wif_r=-55,ver_v=124")
        assertTrue(CloudPayloads.isMeterRuntime(f))
        val m = assertNotNull(CloudPayloads.parseMeter(f, 2000))
        assertEquals(listOf(100, -200, 50), m.phaseW)
        assertEquals(-50, m.totalW)
    }

    @Test
    fun `battery and meter frames are told apart`() {
        assertNull(CloudPayloads.parseMeter(CloudPayloads.parseFields(runtime), 0))
        assertNull(CloudPayloads.parseBattery(CloudPayloads.parseFields("pwr_t=10"), "x", "y", null, 0))
    }

    @Test
    fun `commands are encoded as flash or no-flash`() {
        assertEquals("cd=5,md=80", CloudPayloads.encode(BatteryCommand.SetDod("x", 80)))
        assertEquals("cd=19,md=80", CloudPayloads.encode(BatteryCommand.SetDod("x", 80), noFlash = true))
        assertEquals("cd=3,md=1", CloudPayloads.encode(BatteryCommand.SetChargeMode("x", ChargeMode.CHARGE_THEN_DISCHARGE)))
        assertEquals("cd=3,md=0", CloudPayloads.encode(BatteryCommand.SetChargeMode("x", ChargeMode.SIMULTANEOUS)))
        assertEquals("cd=4,md=1", CloudPayloads.encode(BatteryCommand.SetAdaptiveMode("x", true)))
        assertEquals("cd=4,md=0", CloudPayloads.encode(BatteryCommand.SetAdaptiveMode("x", false)))
    }

    @Test
    fun `reboot is cd 10 and never the factory-reset cd 11`() {
        val payload = CloudPayloads.encode(BatteryCommand.Reboot("x"))
        assertEquals("cd=10", payload)
        assertTrue("cd=11" !in payload) // factory reset must never be produced
    }
}
