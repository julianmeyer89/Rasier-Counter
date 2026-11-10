package de.rasier.speicher.protocol.cloud

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the Marstek cloud MQTT topic id for a device from its MAC, type and (for newer firmware)
 * the salt from the device list. Implemented from the protocol description in docs/PROTOKOLLE.md.
 *
 * Two schemes exist on the hame-2025 broker:
 *  - AES: MAC encrypted with a fixed, publicly known key. Used by HMJ firmware 108..115.
 *  - cq:  salt-based 24-char id. Used by HMJ firmware >= 116 and by the CT002 (HME-4) from fw 122.
 */
object CloudTopics {
    const val PREFIX = "marstek_energy/"

    /** The topic-encryption key shipped with the hame-2025 certificate bundle (a public constant). */
    private val AES_KEY = "!@#\$%^&*()_+{}[]".toByteArray(Charsets.US_ASCII)
    private val CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    data class Topics(val command: String, val status: String)

    /** Builds the publish (command) and subscribe (status) topics for a device. */
    fun topics(type: String, topicId: String): Topics = Topics(
        command = "$PREFIX$type/App/$topicId/ctrl",
        status = "$PREFIX$type/device/$topicId/ctrl",
    )

    /** AES-128-CBC(mac) with a zero IV, hex-encoded. For HMJ firmware below 116. */
    fun aesTopicId(mac: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(mac.lowercase().toByteArray(Charsets.US_ASCII)).toHex()
    }

    /**
     * Chooses the topic id from the firmware generation and salt.
     *
     * @param type device type string, e.g. "HMJ-2" or "HME-4"
     * @param mac  12 hex chars, lower case, no separators
     * @param salt the "a,b" salt string from the device list (may be null/blank)
     * @param useCq whether this firmware uses the cq scheme (HMJ >= 116, HME-4 >= 122)
     */
    fun topicId(type: String, mac: String, salt: String?, useCq: Boolean): String {
        if (!useCq) return aesTopicId(mac)
        val resolved = resolveSalt(salt)?.let { cq(it, mac, type).ifBlank { null } }
        return resolved ?: aesTopicId(mac)
    }

    /** Picks the usable salt from the "a,b" pair, or null when it is pending/unusable (fall back to AES). */
    private fun resolveSalt(salt: String?): String? {
        val parts = salt?.split(",") ?: return null
        if (parts.size < 2) return null
        val a = parts[0]
        val b = parts[1]
        return when {
            a == "0" -> null // pending
            a.isEmpty() || b.isEmpty() || b == "0" -> null // unusable
            a == b -> b // confirmed
            else -> a // rotating
        }
    }

    /** The salt-based 24-char id. Ported from the reference implementation. */
    fun cq(salt: String, mac: String, type: String): String {
        val var1 = type + "_" + mac.dropLast(4)
        val var2 = mac.substring(1, mac.length - 2) + "_" + type
        val h1 = permute(salt, var1)
        val h2 = lcgXor(type + mac, var2)
        return sha256Encode(h1 + h2)
    }

    /** RC4-KSA permutation of the UTF-8 bytes of [content], keyed by [key], applied N times; hex out. */
    private fun permute(content: String, key: String): String {
        var data = content.toByteArray(Charsets.UTF_8)
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val n = data.size
        if (n == 0) return ""
        var reps = (data[n - 1].toInt() and 0xFF) % 5
        if (reps == 0) reps = 1
        repeat(reps) {
            val p = IntArray(n) { it }
            var j = 0
            for (i in 0 until n) {
                j = (j + p[i] + (keyBytes[i % keyBytes.size].toInt() and 0xFF)) % n
                val t = p[i]; p[i] = p[j]; p[j] = t
            }
            data = ByteArray(n) { data[p[it]] }
        }
        return data.toHex()
    }

    /** LCG keystream XOR of [data], keyed by [key]. */
    private fun lcgXor(data: String, key: String): String {
        var seed = 0L
        for (b in key.toByteArray(Charsets.UTF_8)) seed = (seed * 31 + (b.toInt() and 0xFF)) % 2147483647L
        val bytes = data.toByteArray(Charsets.UTF_8)
        var state = seed
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            state = (state * 1664525L + 1013904223L) % 4294967296L
            val k = ((state xor (state ushr 16)) and 0xFF).toInt()
            out[i] = ((bytes[i].toInt() and 0xFF) xor k).toByte()
        }
        return out.toHex()
    }

    private fun sha256Encode(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val words = IntArray(8) { w ->
            ((digest[w * 4].toInt() and 0xFF) shl 24) or
                ((digest[w * 4 + 1].toInt() and 0xFF) shl 16) or
                ((digest[w * 4 + 2].toInt() and 0xFF) shl 8) or
                (digest[w * 4 + 3].toInt() and 0xFF)
        }
        val sb = StringBuilder(24)
        for (i in 0 until 12) {
            val b = (words[i % 8] ushr ((i / 8) * 8)) and 0xFF
            sb.append(CHARSET[b % 62])
            sb.append(CHARSET[(b * 31) % 62])
        }
        return sb.toString()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
