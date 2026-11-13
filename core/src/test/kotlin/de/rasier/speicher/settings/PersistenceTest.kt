package de.rasier.speicher.settings

import de.rasier.speicher.control.AutomationState
import de.rasier.speicher.control.CalibrationPhase
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stored state must survive an update, and must never be dropped without saying so. */
class PersistenceTest {

    @Test
    fun `an automation file written before the adaptive field still loads`() {
        val dir = Files.createTempDirectory("fs").toFile()
        File(dir, "automation.json").writeText(
            """{"calibration":{"queue":["B1"],"index":0,"phase":"CHARGING","batteryStartedMs":1,""" +
                """"saved":{"dodPercent":80,"chargeMode":"SIMULTANEOUS"},"results":[]},"lastCalibrationFinishedMs":123}""",
        )
        val store = FileStore(dir)
        val state = store.loadAutomation()
        assertEquals(CalibrationPhase.CHARGING, state.calibration?.phase)
        assertEquals(80, state.calibration?.saved?.dodPercent)
        assertEquals(123L, state.lastCalibrationFinishedMs)
        assertTrue(store.unreadable.isEmpty())
    }

    @Test
    fun `an unreadable file is kept aside and reported instead of being dropped`() {
        val dir = Files.createTempDirectory("fs").toFile()
        File(dir, "automation.json").writeText("""{"calibration":{"phase":"GIBT_ES_NICHT"}}""")
        val logged = mutableListOf<String>()
        val store = FileStore(dir) { logged += it }

        assertEquals(AutomationState(), store.loadAutomation())
        assertTrue("automation.json" in store.unreadable)
        assertTrue(File(dir, "automation.json.unlesbar").exists(), "Kopie der unlesbaren Datei fehlt")
        assertTrue(logged.any { it.contains("unlesbar") }, logged.toString())
    }

    @Test
    fun `writing keeps one backup generation`() {
        val dir = Files.createTempDirectory("fs").toFile()
        val store = FileStore(dir)
        store.saveAutomation(AutomationState(lastCalibrationFinishedMs = 1))
        store.saveAutomation(AutomationState(lastCalibrationFinishedMs = 2))
        assertTrue(File(dir, "automation.json.bak").exists())
        assertEquals(2L, store.loadAutomation().lastCalibrationFinishedMs)
    }
}
