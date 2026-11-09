package de.rasier.speicher

import de.rasier.speicher.model.BatteryState
import de.rasier.speicher.model.ChargeMode
import de.rasier.speicher.model.MeterReading
import de.rasier.speicher.model.SystemSnapshot

const val B1 = "AA:BB:CC:00:00:01"
const val B2 = "AA:BB:CC:00:00:02"

fun battery(
    id: String = B1,
    t: Long = 0,
    soc: Int = 50,
    pv: Int = 0,
    out: Int = 0,
    dod: Int? = 90,
    mode: ChargeMode = ChargeMode.SIMULTANEOUS,
    capacity: Int? = 2240,
) = BatteryState(
    id = id,
    name = if (id == B1) "Speicher 1" else "Speicher 2",
    timestampMs = t,
    socPercent = soc,
    capacityWh = capacity,
    pvInputW = listOf(pv / 2, pv - pv / 2),
    outputW = listOf(out / 2, out - out / 2),
    dodPercent = dod,
    chargeMode = mode,
)

fun snapshot(t: Long, vararg batteries: BatteryState, gridW: Int? = null) = SystemSnapshot(
    timestampMs = t,
    batteries = batteries.toList(),
    meter = gridW?.let { MeterReading(t, listOf(it, 0, 0)) },
)

const val MIN = 60_000L
const val HOUR = 3_600_000L
