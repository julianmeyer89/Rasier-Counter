package de.rasier.speicher.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Read-only client for the Marstek ("Hame") cloud HTTP API.
 *
 * The API is not officially documented. Endpoints and behaviour follow open-source clients
 * (hame-relay, marstek_cloud, loxberry-marstek-cloud, homey-marstek-connector). This client
 * only reads: it never calls OTA, device-registration or device-side endpoints.
 *
 * Only the MD5 hash of the password is needed, so the app never has to store the plain password.
 */
class MarstekCloudClient(
    baseUrl: String = EU_BASE_URL,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var token: String? = null

    /** Logs in and returns the devices bound to the account. */
    fun login(email: String, passwordMd5: String): LoginResult {
        val url = base.newBuilder()
            .addPathSegments("app/Solar/v2_get_device.php")
            .addQueryParameter("mailbox", email.trim())
            .addQueryParameter("pwd", passwordMd5)
            .build()
        val body = getJson(url)
        val code = body.codeString()
        val tok = (body["token"] as? JsonPrimitive)?.contentOrNullSafe()
        return when {
            (code == "2" || code == "1") && !tok.isNullOrBlank() -> {
                token = tok
                LoginResult.Success(tok, parseDevices(body["data"]))
            }
            code == "4" -> LoginResult.WrongPassword
            code == "3" -> LoginResult.UnknownAccount
            else -> LoginResult.Failed("code=$code msg=${(body["msg"] as? JsonPrimitive)?.contentOrNullSafe()}")
        }
    }

    /**
     * Returns the device list including whatever live fields the cloud provides (SOC, power...).
     * Re-logs in once when the token expired (code 8).
     */
    fun deviceList(email: String, passwordMd5: String): List<CloudDevice> {
        repeat(2) { attempt ->
            val tok = token ?: when (val r = login(email, passwordMd5)) {
                is LoginResult.Success -> r.token
                else -> throw CloudException("Login fehlgeschlagen: $r")
            }
            val url = base.newBuilder()
                .addPathSegments("ems/api/v1/getDeviceList")
                .addQueryParameter("token", tok)
                .addQueryParameter("mailbox", email.trim())
                .build()
            val body = getJson(url)
            val data = body["data"]
            if (body.codeString() == "1" && data is JsonArray) return parseDevices(data)
            // Expired token or unexpected answer: forget the token and try once more.
            token = null
            if (attempt == 1) throw CloudException("Geräteliste nicht abrufbar: code=${body.codeString()}")
        }
        error("unreachable")
    }


    private fun getJson(url: HttpUrl): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        var lastError: IOException? = null
        for (delayMs in RETRY_DELAYS_MS) {
            if (delayMs > 0) Thread.sleep(delayMs)
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code >= 500) {
                        lastError = IOException("HTTP ${response.code}")
                        return@use
                    }
                    if (!response.isSuccessful) throw CloudException("HTTP ${response.code}")
                    // The server sometimes answers JSON with a text/html content type.
                    val text = response.body.string()
                    return json.parseToJsonElement(text).jsonObject
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw CloudException("Marstek-Cloud nicht erreichbar: ${redact(lastError?.message)}", lastError)
    }

    private fun parseDevices(data: JsonElement?): List<CloudDevice> {
        val arr = data as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            CloudDevice(
                devid = o.str("devid") ?: return@mapNotNull null,
                name = o.str("name") ?: "",
                mac = o.str("mac")?.replace(":", "")?.lowercase(),
                type = o.str("type")?.trim()?.uppercase(),
                version = o.str("version"),
                soc = o.num("soc")?.toInt(),
                chargeW = o.num("charge")?.toInt(),
                dischargeW = o.num("discharge")?.toInt(),
                gridW = o.num("grid")?.toInt(),
                reportTimeMs = o.num("report_time")?.let { if (it > 1e11) it.toLong() else (it * 1000).toLong() },
                online = o.num("status")?.let { it.toInt() == 1 },
                raw = o,
            )
        }
    }

    companion object {
        const val EU_BASE_URL = "https://eu.hamedata.com"
        const val US_BASE_URL = "https://us.hamedata.com"
        private const val USER_AGENT = "Dart/2.19 (dart:io)"
        private val RETRY_DELAYS_MS = longArrayOf(0, 1000, 2000, 4000)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** Lower-case hex MD5 of the password, as the Marstek API expects it. */
        fun md5Hex(password: String): String =
            MessageDigest.getInstance("MD5").digest(password.trim().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Removes secrets (token, password hash, e-mail) from text before it is logged. */
        fun redact(text: String?): String? =
            text?.replace(Regex("(token|pwd|mailbox)=[^&\\s]+"), "$1=***")
    }
}

sealed interface LoginResult {
    data class Success(val token: String, val devices: List<CloudDevice>) : LoginResult {
        override fun toString() = "Success(devices=${devices.size})"
    }

    data object WrongPassword : LoginResult
    data object UnknownAccount : LoginResult
    data class Failed(val reason: String) : LoginResult
}

/** A device as listed by the cloud. Live fields are optional: the cloud does not fill them for every model. */
data class CloudDevice(
    val devid: String,
    val name: String,
    val mac: String?,
    val type: String?,
    val version: String?,
    val soc: Int?,
    val chargeW: Int?,
    val dischargeW: Int?,
    val gridW: Int?,
    val reportTimeMs: Long?,
    val online: Boolean?,
    val raw: JsonObject,
) {
    val isB2500: Boolean get() = type?.startsWith("HMJ") == true || type?.startsWith("HMA") == true ||
        type?.startsWith("HMB") == true || type?.startsWith("HMF") == true || type?.startsWith("HMK") == true
    val isMeter: Boolean get() = type?.startsWith("HME") == true || type?.startsWith("TPM") == true
}

class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun JsonObject.codeString(): String? = (this["code"] as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonObject.num(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.doubleOrNull ?: p.longOrNull?.toDouble() ?: p.contentOrNullSafe()?.toDoubleOrNull()
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
