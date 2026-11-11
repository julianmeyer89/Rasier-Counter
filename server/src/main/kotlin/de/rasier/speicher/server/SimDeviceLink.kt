package de.rasier.speicher.server

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.SystemSnapshot
import de.rasier.speicher.sim.SimulatedSystem
import java.time.ZoneId

/** Demo/test link backed by the core simulator. Lets the server run without real devices. */
class SimDeviceLink(zone: ZoneId = ZoneId.systemDefault()) : DeviceLink {
    private val sim = SimulatedSystem(zone)
    private var lastMs: Long? = null

    override suspend fun connect() {}

    override suspend fun poll(nowMs: Long, pollMeter: Boolean, pollBattery: Boolean, replyWaitMs: Long): SystemSnapshot {
        lastMs = nowMs
        return sim.advance(nowMs)
    }

    override suspend fun send(command: BatteryCommand): Boolean = sim.execute(command)

    override val batteryIds: List<String> get() = sim.batteryIds

    override val status: LinkStatus get() = LinkStatus(true, "Demo (Simulation)", lastMs)

    override fun close() {}
}
