package de.rasier.speicher.energy

import de.rasier.speicher.B1
import de.rasier.speicher.HOUR
import de.rasier.speicher.MIN
import de.rasier.speicher.battery
import de.rasier.speicher.snapshot
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnergyIntegratorTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 6, 1)
    private val t0 = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val config = SystemConfig(inverterEfficiency = 1.0)

    @Test
    fun `constant import for one hour gives 1 kWh`() {
        val i = EnergyIntegrator(config, zone)
        for (m in 0..60) i.add(snapshot(t0 + m * MIN, battery(t = t0), gridW = 1000))
        val d = i.totals.getValue(day.toString())
        assertEquals(1000.0, d.gridImportWh, 1e-6)
        assertEquals(0.0, d.gridExportWh, 1e-6)
        assertEquals(HOUR, d.meterCoverageMs)
    }

    @Test
    fun `import and export are not netted within an interval`() {
        val i = EnergyIntegrator(config, zone)
        i.add(snapshot(t0, gridW = 600))
        i.add(snapshot(t0 + HOUR / 60, gridW = -600))
        val d = i.totals.getValue(day.toString())
        // Trapezoid on the split parts: half of the minute at 300 W import, half at 300 W export.
        assertEquals(300.0 / 60, d.gridImportWh, 1e-6)
        assertEquals(300.0 / 60, d.gridExportWh, 1e-6)
    }

    @Test
    fun `house consumption and self consumption from battery output`() {
        val i = EnergyIntegrator(config, zone)
        // House uses 500 W: 300 W from the batteries via the inverter, 200 W from the grid.
        for (m in 0..60) i.add(snapshot(t0 + m * MIN, battery(t = t0, out = 300, pv = 400), gridW = 200))
        val d = i.totals.getValue(day.toString())
        assertEquals(500.0, d.houseWh, 1e-6)
        assertEquals(300.0, d.selfConsumedWh, 1e-6)
        assertEquals(400.0, d.pvWh, 1e-6)
        assertEquals(0.6, d.autarky!!, 1e-6)
        // 300 Wh at 30 ct/kWh = 0.09 EUR
        assertEquals(0.09, d.savingsEuro(Tariff(importCentPerKwh = 30.0)), 1e-9)
    }

    @Test
    fun `export reduces self consumption and earns feed-in`() {
        val i = EnergyIntegrator(config, zone)
        for (m in 0..60) i.add(snapshot(t0 + m * MIN, battery(t = t0, out = 800), gridW = -300))
        val d = i.totals.getValue(day.toString())
        assertEquals(500.0, d.selfConsumedWh, 1e-6)
        assertEquals(500.0, d.houseWh, 1e-6)
        assertEquals(300.0, d.gridExportWh, 1e-6)
        val savings = d.savingsEuro(Tariff(importCentPerKwh = 30.0, feedInCentPerKwh = 8.0))
        assertEquals(0.5 * 0.30 + 0.3 * 0.08, savings, 1e-9)
    }

    @Test
    fun `gaps longer than the limit are skipped`() {
        val i = EnergyIntegrator(config.copy(maxGapMs = 5 * MIN), zone)
        i.add(snapshot(t0, gridW = 1000))
        i.add(snapshot(t0 + 2 * HOUR, gridW = 1000))
        assertNull(i.totals[day.toString()])
    }

    @Test
    fun `interval across midnight is split between days`() {
        val i = EnergyIntegrator(config, zone)
        val beforeMidnight = day.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        i.add(snapshot(beforeMidnight, gridW = 600))
        i.add(snapshot(beforeMidnight + 2 * MIN, gridW = 600))
        assertEquals(10.0, i.totals.getValue(day.toString()).gridImportWh, 1e-6)
        assertEquals(10.0, i.totals.getValue(day.plusDays(1).toString()).gridImportWh, 1e-6)
    }

    @Test
    fun `inverter output is capped and uses efficiency`() {
        val i = EnergyIntegrator(SystemConfig(inverterEfficiency = 0.9, inverterMaxW = 1000), zone)
        val b1 = battery(id = B1, t = t0, out = 800)
        val b2 = battery(id = "X", t = t0, out = 800)
        for (m in 0..60) i.add(snapshot(t0 + m * MIN, b1, b2))
        assertEquals(1000.0, i.totals.getValue(day.toString()).inverterAcWh, 1e-6)
    }
}
