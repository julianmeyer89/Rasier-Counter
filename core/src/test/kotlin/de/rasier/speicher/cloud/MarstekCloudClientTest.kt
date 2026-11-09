package de.rasier.speicher.cloud

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarstekCloudClientTest {
    private val server = MockWebServer()

    @BeforeTest
    fun setUp() = server.start()

    @AfterTest
    fun tearDown() = server.close()

    private fun client() = MarstekCloudClient(server.url("/").toString())

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body(body).build()

    @Test
    fun `md5 is lowercase hex`() {
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", MarstekCloudClient.md5Hex("password"))
    }

    @Test
    fun `login parses devices and sends hashed password`() {
        server.enqueue(json("""{"code":"2","msg":"ok","token":"abc123","data":[{"devid":"0123456789abcdef01234567","name":"B2500 Garage","mac":"AA:BB:CC:DD:EE:01","type":"hmj-2","version":"110"}]}"""))
        val r = client().login("user@example.com", MarstekCloudClient.md5Hex("pw"))
        val success = assertIs<LoginResult.Success>(r)
        val dev = success.devices.single()
        assertEquals("aabbccddee01", dev.mac)
        assertEquals("HMJ-2", dev.type)
        assertTrue(dev.isB2500)
        val req = server.takeRequest()
        assertEquals("/app/Solar/v2_get_device.php", req.url.encodedPath)
        assertEquals(MarstekCloudClient.md5Hex("pw"), req.url.queryParameter("pwd"))
    }

    @Test
    fun `wrong password is recognised with numeric code`() {
        server.enqueue(json("""{"code":4,"msg":"wrong"}"""))
        assertEquals(LoginResult.WrongPassword, client().login("a@b.c", "x"))
    }

    @Test
    fun `device list re-logs in once on expired token`() {
        val c = client()
        server.enqueue(json("""{"code":"2","token":"t1","data":[]}"""))
        server.enqueue(json("""{"code":8,"msg":"Unauthorized","data":""}"""))
        server.enqueue(json("""{"code":"2","token":"t2","data":[]}"""))
        server.enqueue(json("""{"code":1,"data":[{"devid":"d1","type":"HME-4","grid":"240","report_time":1767225600,"status":1}]}"""))
        val devices = c.deviceList("a@b.c", "x")
        val ct = devices.single()
        assertTrue(ct.isMeter)
        assertEquals(240, ct.gridW)
        assertEquals(1767225600000L, ct.reportTimeMs)
        assertEquals(true, ct.online)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `secrets are redacted`() {
        assertEquals("x?token=***&pwd=***&mailbox=***", MarstekCloudClient.redact("x?token=abc&pwd=def&mailbox=a@b.c"))
    }
}
