package de.rasier.speicher.protocol.ct002

import de.rasier.speicher.model.MeterReading
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * UDP protocol of the Marstek CT002 smart meter (port 12345).
 *
 * Frame: SOH STX LEN(ASCII decimal, total length) '|' f1 '|' f2 ... ETX CHK(2 hex chars, XOR of SOH..ETX).
 * Implemented from the protocol description in docs/PROTOKOLLE.md.
 */
object Ct002Protocol {
    const val PORT = 12345
    private const val SOH = 0x01
    private const val STX = 0x02
    private const val ETX = 0x03
    const val WILDCARD_MAC = "000000000000"

    fun encode(fields: List<String>): ByteArray {
        val body = fields.joinToString(separator = "|", prefix = "|")
        val base = 2 + body.length + 1 + 2
        var digits = 1
        while ((base + digits).toString().length != digits) digits++
        val len = base + digits
        val head = byteArrayOf(SOH.toByte(), STX.toByte()) + len.toString().toByteArray(Charsets.US_ASCII) +
            body.toByteArray(Charsets.US_ASCII) + byteArrayOf(ETX.toByte())
        var x = 0
        head.forEach { x = x xor (it.toInt() and 0xFF) }
        val out = head + "%02x".format(x).toByteArray(Charsets.US_ASCII)
        check(out.size == len)
        return out
    }

    /** Returns the fields of a valid frame, or null if length, markers or checksum are wrong. */
    fun decode(data: ByteArray, length: Int = data.size): List<String>? {
        if (length < 10) return null
        if (data[0].toInt() != SOH || data[1].toInt() != STX) return null
        val firstBar = (2 until length).firstOrNull { data[it] == '|'.code.toByte() } ?: return null
        val declared = String(data, 2, firstBar - 2, Charsets.US_ASCII).toIntOrNull() ?: return null
        if (declared != length) return null
        if (data[length - 3].toInt() != ETX) return null
        var x = 0
        for (i in 0..length - 3) x = x xor (data[i].toInt() and 0xFF)
        // The CT may send a space instead of the high hex digit; then only the low digit is compared
        // (same leniency as other working implementations).
        val chkText = String(data, length - 2, 2, Charsets.US_ASCII)
        if (chkText[0] == ' ') {
            val low = chkText.substring(1).toIntOrNull(16) ?: return null
            if (low != (x and 0x0F)) return null
        } else {
            val chk = chkText.toIntOrNull(16) ?: return null
            if (chk != x) return null
        }
        return String(data, firstBar + 1, length - 3 - firstBar - 1, Charsets.US_ASCII).split('|')
    }

    /**
     * Passive read request: phase "0", power 0 and participate 0, so the reader is not counted in any
     * phase and does not influence the batteries' regulation. It does occupy one of the CT's slots.
     */
    fun passiveRequest(readerId: String, ctType: String = "HME-4", ctMac: String = WILDCARD_MAC): ByteArray =
        encode(listOf(READER_TYPE, readerId, ctType, ctMac, "0", "0", "0"))

    /** Device type sent by the reader. Deliberately not "HMJ-2" so it is not confused with a battery. */
    const val READER_TYPE = "VNSE3-0"

    /**
     * New random reader id: a locally administered MAC-style id with a prefix that emulators ignore.
     * Must never be the id of a real battery, otherwise that battery loses its meter data.
     */
    fun newReaderId(random: SecureRandom = SecureRandom()): String =
        "02a57e" + (1..6).joinToString("") { "%x".format(random.nextInt(16)) }
}

/** A decoded CT002/CT003 reply. Power in W, positive = import from grid. */
data class Ct002Reply(
    val ctType: String,
    val ctMac: String,
    val requesterId: String,
    val phaseW: List<Int>,
    val totalW: Int,
    val participants: List<Int>,
    val wifiRssi: Int?,
    /** Cumulative energy counters in Wh (only newer CT002 hardware and CT003). */
    val importWh: Double?,
    val exportWh: Double?,
) {
    fun toMeterReading(timestampMs: Long) = MeterReading(timestampMs, phaseW, totalW)

    companion object {
        fun parse(fields: List<String>): Ct002Reply? {
            if (fields.size < 24) return null
            // Phase powers must parse strictly; never default them to 0.
            val a = fields[4].trim().toIntOrNull() ?: return null
            val b = fields[5].trim().toIntOrNull() ?: return null
            val c = fields[6].trim().toIntOrNull() ?: return null
            val total = fields[7].trim().toIntOrNull() ?: (a + b + c)
            fun n(i: Int) = fields.getOrNull(i)?.trim()?.toLongOrNull()
            return Ct002Reply(
                ctType = fields[0],
                ctMac = fields[1],
                requesterId = fields[3],
                phaseW = listOf(a, b, c),
                totalW = total,
                participants = (8..11).map { n(it)?.toInt() ?: 0 },
                wifiRssi = n(12)?.toInt(),
                importWh = if (fields.size >= 28) ((n(24) ?: 0) + (n(25) ?: 0)) / 10.0 else null,
                exportWh = if (fields.size >= 28) ((n(26) ?: 0) + (n(27) ?: 0)) / 10.0 else null,
            )
        }
    }
}

/**
 * Passive CT002 reader over one long-lived UDP socket (a new socket per request would move the
 * CT's stored reply port). Poll every few seconds at most: the CT serves only about 2 replies per
 * second shared by all batteries. Stop polling when not needed; the CT frees the slot after ~60 s.
 */
class Ct002Reader(
    private val host: String,
    private val readerId: String,
    private val ctType: String = "HME-4",
    private val port: Int = Ct002Protocol.PORT,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : AutoCloseable {
    private var socket: DatagramSocket? = null

    @Synchronized
    fun read(timeoutMs: Int = 3_000): Ct002Reply? {
        val s = socket ?: socketFactory().also {
            it.connect(InetSocketAddress(host, port))
            socket = it
        }
        s.soTimeout = timeoutMs
        val request = Ct002Protocol.passiveRequest(readerId, ctType)
        s.send(DatagramPacket(request, request.size))
        val buf = ByteArray(512)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                return null
            }
            val fields = Ct002Protocol.decode(packet.data, packet.length) ?: continue
            val reply = Ct002Reply.parse(fields) ?: continue
            if (reply.requesterId == readerId) return reply
        }
        return null
    }

    @Synchronized
    override fun close() {
        socket?.close()
        socket = null
    }
}
