package dev.sadonsergey.dumpie.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpieSettingsTest {
    @Test
    fun defaults() {
        val state = DumpieSettings.State()
        assertTrue(state.rayEnabled)
        assertEquals(23517, state.rayPort)
        assertTrue(state.dumpEnabled)
        assertEquals(9912, state.dumpPort)
        assertTrue(state.showToolWindowOnMessage)
        assertTrue(state.pinBars)
    }

    @Test
    fun changedValuesSurviveSaving() {
        val state = DumpieSettings.State().apply {
            pinBars = false
            rayPort = 23518
        }
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), DumpieSettings.State::class.java)
        assertFalse(restored.pinBars)
        assertEquals(23518, restored.rayPort)
        assertTrue(restored.dumpEnabled)
    }
}
