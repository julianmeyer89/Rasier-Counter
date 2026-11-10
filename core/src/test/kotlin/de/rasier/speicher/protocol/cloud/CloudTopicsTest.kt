package de.rasier.speicher.protocol.cloud

import kotlin.test.Test
import kotlin.test.assertEquals

class CloudTopicsTest {
    @Test
    fun `aes topic id matches the known vector`() {
        assertEquals("8009eb5b0d30c3286fabbb5f3e705937", CloudTopics.aesTopicId("aabbccddeeff"))
    }

    @Test
    fun `cq matches the reference test vectors`() {
        assertEquals("LV9VDVC0S03VDVlVTVTVK0q0", CloudTopics.cq("abc123def456789a", "112233445566", "HMG-50"))
        assertEquals("HVe0ZVW0Y0jVBVRVC0DVC0pV", CloudTopics.cq("fedcba9876543210", "aabbccddeeff", "HMG-50"))
        assertEquals("C0q0a0w03VdVZVhVc0lVlVE0", CloudTopics.cq("1234567890abcdef", "001122334455", "HMG-50"))
        assertEquals("I0a0i03VRVO0w09Vk0BV80g0", CloudTopics.cq("sample123456782d", "aabbccdd1234", "HMG-50"))
    }

    @Test
    fun `hmj firmware below 116 uses aes even with a salt present`() {
        val id = CloudTopics.topicId("HMJ-2", "aabbccddeeff", "12,34", useCq = false)
        assertEquals("8009eb5b0d30c3286fabbb5f3e705937", id)
    }

    @Test
    fun `cq is used when supported and a usable salt is present`() {
        val id = CloudTopics.topicId("HMG-50", "aabbccddeeff", "fedcba9876543210,fedcba9876543210", useCq = true)
        assertEquals("HVe0ZVW0Y0jVBVRVC0DVC0pV", id)
    }

    @Test
    fun `cq falls back to aes when the salt is pending`() {
        val id = CloudTopics.topicId("HME-4", "aabbccddeeff", "0,abc", useCq = true)
        assertEquals(CloudTopics.aesTopicId("aabbccddeeff"), id)
    }

    @Test
    fun `topics are built with the right prefix`() {
        val t = CloudTopics.topics("HMJ-2", "deadbeef")
        assertEquals("marstek_energy/HMJ-2/App/deadbeef/ctrl", t.command)
        assertEquals("marstek_energy/HMJ-2/device/deadbeef/ctrl", t.status)
    }
}
