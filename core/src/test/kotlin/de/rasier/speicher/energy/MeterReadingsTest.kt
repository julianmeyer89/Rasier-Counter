package de.rasier.speicher.energy

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeterReadingsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(d: String, h: Int) = LocalDate.parse(d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `week between two readings with battery counters`() {
        val entries = listOf(
            MeterEntry(at("2026-11-01", 10), importKwh = 10376.0, exportKwh = 50.0),
            MeterEntry(at("2026-11-08", 10), importKwh = 10411.0, exportKwh = 52.0),
        )
        var days = emptyList<DeviceDay>()
        for (d in 1..7) {
            val date = "2026-11-0$d"
            // Two readings per day per battery: the higher one wins.
            days = MeterEvaluation.updateDeviceDays(days, "B1", at(date, 12), pvWh = 800, outputWh = 1000, zone = zone)
            days = MeterEvaluation.updateDeviceDays(days, "B1", at(date, 22), pvWh = 1500, outputWh = 2000, zone = zone)
            days = MeterEvaluation.updateDeviceDays(days, "B2", at(date, 22), pvWh = 1500, outputWh = 2000, zone = zone)
        }
        val p = MeterEvaluation.periods(entries, days, efficiency = 1.0, zone = zone).single()
        assertEquals(35.0, p.importKwh, 1e-9)
        assertEquals(2.0, p.exportKwh!!, 1e-9)
        assertEquals(7, p.batteryDaysCovered)
        assertEquals(28.0, p.batteryOutputKwh!!, 1e-9) // 7 days x 2 batteries x 2 kWh
        assertEquals(35.0 - 2.0 + 28.0, p.houseKwh!!, 1e-9)
        assertEquals(26.0, p.selfConsumedKwh!!, 1e-9)
        assertEquals(26.0 * 0.30 + 2.0 * 0.08, p.savingsEuro(Tariff(30.0, 8.0))!!, 1e-9)
        assertEquals(35.0, p.importPerWeekKwh, 1e-9)
    }

    @Test
    fun `without battery data only grid values are shown`() {
        val entries = listOf(MeterEntry(at("2026-11-01", 10), 100.0), MeterEntry(at("2026-11-04", 10), 115.5))
        val p = MeterEvaluation.periods(entries, emptyList(), 0.95, zone).single()
        assertEquals(15.5, p.importKwh, 1e-9)
        assertNull(p.exportKwh)
        assertNull(p.houseKwh)
        assertTrue(p.importPerWeekKwh > 36.0)
    }

    @Test
    fun `decreasing reading is ignored`() {
        val entries = listOf(MeterEntry(at("2026-11-01", 10), 100.0), MeterEntry(at("2026-11-02", 10), 90.0))
        assertTrue(MeterEvaluation.periods(entries, emptyList(), 0.95, zone).isEmpty())
    }
}
