package de.rasier.speicher.server

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.SystemSnapshot

/** Source of device data and sink for commands. Implemented by the cloud link and the simulator. */
interface DeviceLink : AutoCloseable {
    /** Establishes the connection (or does nothing for the simulator). */
    suspend fun connect()

    /** Reads one fresh snapshot of all devices. */
    suspend fun poll(nowMs: Long): SystemSnapshot

    /** Sends a setting change to a battery; returns true if it was accepted. */
    suspend fun send(command: BatteryCommand): Boolean

    /** Battery ids known to the link, in configured order. */
    val batteryIds: List<String>

    /** Human-readable status of the link, shown in the dashboard. */
    val status: LinkStatus
}

data class LinkStatus(val connected: Boolean, val detail: String, val lastDataMs: Long?)
