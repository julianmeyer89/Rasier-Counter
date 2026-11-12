package de.rasier.speicher.protocol.b2500

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.BatteryState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * One Bluetooth connection to a B2500. Implemented by the Android app with BluetoothGatt.
 * [exchange] writes one frame and returns the next notification, or null on timeout.
 */
interface BleSession : AutoCloseable {
    suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray?
}

/** Opens Bluetooth sessions by MAC address. */
fun interface BleConnector {
    suspend fun connect(address: String): BleSession
}

/**
 * Reads and controls a B2500 over Bluetooth with short connect-poll-disconnect cycles, which
 * community projects found much more stable than permanent connections. The battery accepts only
 * one Bluetooth connection at a time, so the official app cannot connect while a cycle runs.
 */
class B2500Client(
    private val connector: BleConnector,
    private val timeoutMs: Long = 4_000,
    private val gapMs: Long = 250,
    private val log: (String) -> Unit = {},
) {
    data class Reading(
        val runtime: B2500Runtime,
        val timers: B2500Timers?,
        val info: B2500DeviceInfo?,
    )

    /** Reads runtime data (and optionally timers/device info) in one short session. */
    suspend fun read(address: String, withDetails: Boolean): Reading = connector.connect(address).use { s ->
        // The first request after connecting is sometimes ignored, so reads are retried.
        val runtime = readWithRetry(s, B2500Protocol.Read.RUNTIME) { B2500Parser.runtime(it) }
            ?: throw BleException("Keine Laufzeitdaten von $address")
        log("Laufzeitdaten von $address: ${runtime.frameLength} Byte, FW ${runtime.firmware}")
        var timers: B2500Timers? = null
        var info: B2500DeviceInfo? = null
        if (withDetails) {
            delay(gapMs)
            timers = readWithRetry(s, B2500Protocol.Read.TIMERS) { B2500Parser.timers(it) }
            delay(gapMs)
            info = readWithRetry(s, B2500Protocol.Read.DEVICE_INFO) { B2500Parser.deviceInfo(it) }
        }
        Reading(runtime, timers, info)
    }

    suspend fun readCells(address: String): B2500Cells? = connector.connect(address).use { s ->
        readWithRetry(s, B2500Protocol.Read.CELLS) { if (B2500Protocol.isCellInfo(it)) B2500Parser.cells(it) else null }
    }

    /**
     * Sends a setting change once (each write goes to flash, so no blind retries) and verifies it
     * by reading the value back.
     */
    suspend fun write(address: String, command: BatteryCommand): Boolean = connector.connect(address).use { s ->
        val frame = B2500Protocol.encode(command)
        s.exchange(frame, timeoutMs)
        delay(gapMs * 2)
        when (command) {
            is BatteryCommand.SetDod ->
                readWithRetry(s, B2500Protocol.Read.RUNTIME) { B2500Parser.runtime(it) }?.dodPercent == command.percent
            is BatteryCommand.SetChargeMode ->
                readWithRetry(s, B2500Protocol.Read.RUNTIME) { B2500Parser.runtime(it) }?.chargeMode == command.mode
            is BatteryCommand.SetAdaptiveMode ->
                readWithRetry(s, B2500Protocol.Read.TIMERS) { B2500Parser.timers(it) }?.adaptiveMode == command.enabled
            is BatteryCommand.Reboot -> false // not supported locally; encode() already rejects it
        }
    }

    private suspend fun <T> readWithRetry(s: BleSession, read: B2500Protocol.Read, parse: (ByteArray) -> T?): T? {
        val frame = B2500Protocol.readFrame(read)
        repeat(3) { attempt ->
            val reply = s.exchange(frame, timeoutMs)
            if (reply != null) parse(reply)?.let { return it }
            if (attempt < 2) delay(gapMs)
        }
        return null
    }
}

class BleException(message: String) : Exception(message)

/** Converts a Bluetooth reading into the app's battery model. */
fun B2500Client.Reading.toBatteryState(id: String, name: String, timestampMs: Long, capacityWh: Int?): BatteryState {
    val r = runtime
    // Capacity: configured value, else estimated from remaining energy and SOC.
    val capacity = capacityWh ?: if (r.socPercent >= 5) (r.remainingWh / (r.socPercent / 100.0)).roundToInt() else null
    return BatteryState(
        id = id,
        name = name,
        timestampMs = timestampMs,
        socPercent = r.socPercent.roundToInt(),
        capacityWh = capacity,
        pvInputW = r.pvInputW,
        outputW = r.outputW,
        outputEnabled = r.outputEnabled,
        dodPercent = r.dodPercent,
        chargeMode = r.chargeMode,
        adaptiveMode = timers?.adaptiveMode,
        dailyPvWh = r.dailyPvWh?.toInt(),
        dailyOutputWh = r.dailyOutputWh?.toInt(),
        firmware = r.firmware,
        deviceType = info?.type,
        temperatureC = r.temperatureHighC,
    )
}
