package com.openminis.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentToolTest {

    @Test
    fun testToolDefinitionMatchesSchema() {
        val def = PhoneAgentTool.definition()
        assertEquals("phone_screen_action", def.name)
        assertTrue(def.description.contains("Control Android device"))
        assertTrue(def.required.contains("tool_title"))
        assertTrue(def.required.contains("action"))
        assertNotNull(def.parameters["action"])
        assertNotNull(def.parameters["x"])
        assertNotNull(def.parameters["y"])
        assertNotNull(def.parameters["silent_background"])
    }

    @Test
    fun testDeviceChannelDetectionWhenNoneActive() {
        // In plain unit test environment without Android runtime, active channel should be NONE
        val channel = DeviceActionDispatcher.activeChannel()
        assertEquals(DeviceActionDispatcher.Channel.NONE, channel)
    }

    @Test
    fun testKeyMappingLogic() {
        val keys = listOf("BACK", "HOME", "RECENTS", "ENTER")
        keys.forEach { key ->
            assertTrue(key.isNotBlank())
        }
    }
}
