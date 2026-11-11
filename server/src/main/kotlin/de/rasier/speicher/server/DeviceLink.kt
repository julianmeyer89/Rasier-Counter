package de.rasier.speicher.server

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.SystemSnapshot

/** Source of device data and sink for commands. Implemented by the cloud link and the simulator. */
interface DeviceLink : AutoCloseable {
    /** Establishes the connection (or does nothing for the simulator). */
    suspend fun connect()

    /**
     * Polls the selected device types and returns a snapshot of all known devices. Devices not polled
     * this cycle keep their last stored value (with its original timestamp). [replyWaitMs] is how long
     * to wait for answers before reading; the hub sizes it to the loop interval.
     */
    suspend fun poll(nowMs: Long, pollMeter: Boolean, pollBattery: Boolean, replyWaitMs: Long): SystemSnapshot

    /** Sends a setting change to a battery; returns true if it was accepted. */
    suspend fun send(command: BatteryCommand): Boolean

    /** Battery ids known to the link, in configured order. */
    val batteryIds: List<String>

    /** Human-readable status of the link, shown in the dashboard. */
    val status: LinkStatus
}

data class LinkStatus(val connected: Boolean, val detail: String, val lastDataMs: Long?)
