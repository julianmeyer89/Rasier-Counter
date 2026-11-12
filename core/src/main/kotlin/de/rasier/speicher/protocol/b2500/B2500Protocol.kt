package de.rasier.speicher.protocol.b2500

import de.rasier.speicher.control.BatteryCommand
import de.rasier.speicher.model.ChargeMode

/**
 * Bluetooth LE frame protocol of the Marstek B2500 (V2, e.g. HMJ-2).
 *
 * Implemented from the protocol description in docs/PROTOKOLLE.md (community reverse engineering).
 * Frame: 0x73 | LEN (total length) | 0x23 | CMD | payload | XOR of all previous bytes.
 * Commands are written to characteristic FF01, replies arrive as notifications on FF02.
 */
object B2500Protocol {
    const val SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    private const val START = 0x73
    private const val MARKER = 0x23

    /** Read commands. They never change the device. */
    enum class Read(val code: Int, val payload: Int?) {
        RUNTIME(0x03, 0x01),
        DEVICE_INFO(0x04, 0x01),
        CELLS(0x0F, 0x01),
        TIMERS(0x13, 0x00),
    }

    // Write commands (each one is stored in the device's flash memory).
    const val CMD_SET_DOD = 0x0B
    const val CMD_SET_CHARGE_MODE = 0x0D
    const val CMD_ENABLE_ADAPTIVE = 0x11
    const val CMD_SET_TIMERS = 0x12

    /**
     * Commands the app must never send: Wi-Fi/MQTT/region configuration, reboot, factory reset,
     * CT diagnosis and certificate upload. Checked in [frame] as a last line of defence.
     */
    val FORBIDDEN: Set<Int> = setOf(0x02, 0x05, 0x20, 0x21, 0x25, 0x26, 0x2A, 0x60, 0x61, 0x62)

    fun frame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(cmd !in FORBIDDEN) { "Befehl 0x%02X ist gesperrt".format(cmd) }
        val len = 5 + payload.size
        require(len <= 0xFF)
        val out = ByteArray(len)
        out[0] = START.toByte()
        out[1] = len.toByte()
        out[2] = MARKER.toByte()
        out[3] = cmd.toByte()
        payload.copyInto(out, 4)
        out[len - 1] = xor(out, 0, len - 1)
        return out
    }

    fun readFrame(read: Read): ByteArray =
        frame(read.code, read.payload?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0))

    /** Translates a protocol-independent command into the frame(s) to send. */
    fun encode(command: BatteryCommand): ByteArray = when (command) {
        is BatteryCommand.SetDod -> frame(CMD_SET_DOD, byteArrayOf(command.percent.coerceIn(0, 100).toByte()))
        is BatteryCommand.SetChargeMode -> {
            val v = when (command.mode) {
                ChargeMode.SIMULTANEOUS -> 0
                ChargeMode.CHARGE_THEN_DISCHARGE -> 1
                ChargeMode.UNKNOWN -> throw IllegalArgumentException("Unbekannter Lademodus")
            }
            frame(CMD_SET_CHARGE_MODE, byteArrayOf(v.toByte()))
        }
        is BatteryCommand.SetAdaptiveMode -> {
            // There is no "disable" command: adaptive mode ends when timers are written. The app
            // never does that automatically, so only enabling is supported.
            require(command.enabled) { "Adaptiver Modus kann nur über die Zeitpläne beendet werden" }
            frame(CMD_ENABLE_ADAPTIVE, byteArrayOf(0x00))
        }
        // Local reboot (0x25) is in FORBIDDEN by design; a restart is only issued over the cloud (cd=10).
        is BatteryCommand.Reboot -> throw IllegalArgumentException("Neustart ist nur über die Cloud möglich")
    }

    fun xor(data: ByteArray, from: Int, to: Int): Byte {
        var x = 0
        for (i in from until to) x = x xor (data[i].toInt() and 0xFF)
        return x.toByte()
    }

    /** Which command a notification answers, or null if it is not a 0x73 frame (cell info is plain ASCII). */
    fun commandOf(data: ByteArray): Int? =
        if (data.size >= 4 && (data[0].toInt() and 0xFF) == START && (data[2].toInt() and 0xFF) == MARKER) data[3].toInt() and 0xFF else null

    fun isCellInfo(data: ByteArray): Boolean {
        if (commandOf(data) != null) return false
        val text = String(data, Charsets.US_ASCII)
        return text.count { it == '_' } >= 3 && text.take(12).count { it == '_' } >= 2
    }
}

/** Decoded runtime information (command 0x03). */
data class B2500Runtime(
    val pvInputW: List<Int>,
    val pvInputActive: List<Boolean>,
    val socPercent: Double,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val chargeMode: ChargeMode,
    val outputEnabled: List<Boolean>,
    val wifiConnected: Boolean,
    val cloudConnected: Boolean,
    val outputActive: List<Boolean>,
    val dodPercent: Int,
    val dischargeThresholdW: Int,
    val remainingWh: Int,
    val outputW: List<Int>,
    val extensionPacks: Int,
    val clockHour: Int,
    val clockMinute: Int,
    val temperatureLowC: Int,
    val temperatureHighC: Int,
    val dailyBatteryChargeWh: Long?,
    val dailyBatteryDischargeWh: Long?,
    val dailyPvWh: Long?,
    val dailyOutputWh: Long?,
    /** Raw frame length, logged to verify the layout on firmware V110.9. */
    val frameLength: Int,
) {
    val firmware: String get() = "$firmwareMajor.$firmwareMinor"
}

data class TimerSlot(
    val enabled: Boolean,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val powerW: Int,
)

data class B2500Timers(
    val adaptiveMode: Boolean,
    val slots: List<TimerSlot>,
    val meterConnected: Boolean,
    val meterReadingW: Int,
)

data class B2500DeviceInfo(val type: String?, val id: String?, val mac: String?, val version: String?)

data class B2500Cells(val socPercent: Int, val temperaturesC: List<Int>, val cellMillivolts: List<Int>)

/** Parsers for B2500 notifications. All multi-byte values are little-endian. */
object B2500Parser {

    /**
     * Runtime info. The LEN byte of real replies does not match the notification size and the
     * checksum is not reliable, so neither is validated; the notification size is used instead.
     */
    fun runtime(d: ByteArray): B2500Runtime? {
        if (B2500Protocol.commandOf(d) != B2500Protocol.Read.RUNTIME.code || d.size < 40) return null
        fun daily(off: Int) = if (d.size >= off + 4 + 1) u32(d, off) else null
        val in1 = u8(d, 4)
        val in2 = u8(d, 5)
        return B2500Runtime(
            pvInputW = listOf(u16(d, 6), u16(d, 8)),
            pvInputActive = listOf(in1 != 0, in2 != 0),
            socPercent = u16(d, 10) / 10.0,
            firmwareMajor = u8(d, 12),
            firmwareMinor = u8(d, 39),
            chargeMode = when (u8(d, 13)) {
                0 -> ChargeMode.SIMULTANEOUS
                1 -> ChargeMode.CHARGE_THEN_DISCHARGE
                else -> ChargeMode.UNKNOWN
            },
            outputEnabled = listOf(u8(d, 14) and 1 != 0, u8(d, 14) and 2 != 0),
            wifiConnected = u8(d, 15) and 1 != 0,
            cloudConnected = u8(d, 15) and 2 != 0,
            outputActive = listOf(u8(d, 16) != 0, u8(d, 17) != 0),
            dodPercent = u8(d, 18),
            dischargeThresholdW = u16(d, 19),
            remainingWh = u16(d, 22),
            outputW = listOf(u16(d, 24), u16(d, 26)),
            extensionPacks = u8(d, 28) + u8(d, 29),
            clockHour = u8(d, 31),
            clockMinute = u8(d, 32),
            temperatureLowC = s16(d, 33),
            temperatureHighC = s16(d, 35),
            dailyBatteryChargeWh = daily(40),
            dailyBatteryDischargeWh = daily(44),
            dailyPvWh = daily(48),
            dailyOutputWh = daily(52),
            frameLength = d.size,
        )
    }

    fun timers(d: ByteArray): B2500Timers? {
        if (B2500Protocol.commandOf(d) != B2500Protocol.Read.TIMERS.code || d.size < 4 + 29) return null
        val p = 4
        fun slot(off: Int) = TimerSlot(
            enabled = u8(d, p + off) != 0,
            startHour = u8(d, p + off + 1),
            startMinute = u8(d, p + off + 2),
            endHour = u8(d, p + off + 3),
            endMinute = u8(d, p + off + 4),
            powerW = u16(d, p + off + 5),
        )
        val slots = mutableListOf(slot(1), slot(8), slot(15))
        if (d.size >= p + 53) {
            slots += slot(39)
            slots += slot(46)
        }
        return B2500Timers(
            adaptiveMode = u8(d, p) != 0,
            slots = slots,
            meterConnected = u8(d, p + 22) != 0,
            meterReadingW = s16(d, p + 25),
        )
    }

    fun deviceInfo(d: ByteArray): B2500DeviceInfo? {
        if (B2500Protocol.commandOf(d) != B2500Protocol.Read.DEVICE_INFO.code || d.size < 6) return null
        // ASCII between header and checksum; tolerate a missing checksum byte.
        val hasChecksum = B2500Protocol.xor(d, 0, d.size - 1) == d[d.size - 1]
        val end = if (hasChecksum) d.size - 1 else d.size
        val text = String(d, 4, end - 4, Charsets.US_ASCII).filter { it.code in 0x20..0x7E }
        val map = text.split(',').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
        return B2500DeviceInfo(map["type"], map["id"], map["mac"], map["version"])
    }

    /** Cell info: plain ASCII "SOC_T1_T2_c1_c2_..." without frame header. */
    fun cells(d: ByteArray): B2500Cells? {
        val text = String(d, Charsets.US_ASCII).filter { it.isDigit() || it == '_' || it == '-' }
        val values = text.split('_').mapNotNull { it.toIntOrNull() }
        if (values.size < 4) return null
        return B2500Cells(values[0], values.subList(1, 3), values.drop(3))
    }

    private fun u8(d: ByteArray, i: Int) = if (i < d.size) d[i].toInt() and 0xFF else 0
    private fun u16(d: ByteArray, i: Int) = u8(d, i) or (u8(d, i + 1) shl 8)
    private fun s16(d: ByteArray, i: Int) = u16(d, i).toShort().toInt()
    private fun u32(d: ByteArray, i: Int): Long =
        (u8(d, i).toLong()) or (u8(d, i + 1).toLong() shl 8) or (u8(d, i + 2).toLong() shl 16) or (u8(d, i + 3).toLong() shl 24)
}
