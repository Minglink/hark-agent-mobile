package com.openminis.app.automation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TargetDisplayRoutingTest {
    @Test fun `losing Shizuku after mapping never enables accessibility fallback on a secondary display`() {
        assertNull(DeviceActionDispatcher.targetDisplayRoutingError(7, true, DeviceActionDispatcher.Channel.SHIZUKU, 35))
        // The dispatcher reselects the channel after its IO suspension. A
        // referenced secondary-screen action must stop if Shizuku disappeared.
        assertNotNull(DeviceActionDispatcher.targetDisplayRoutingError(7, true, DeviceActionDispatcher.Channel.ACCESSIBILITY, 35))
        assertNotNull(DeviceActionDispatcher.targetDisplayRoutingError(7, true, DeviceActionDispatcher.Channel.NONE, 35))
    }

    @Test fun `secondary references require a platform supporting explicit display input`() {
        assertNotNull(DeviceActionDispatcher.targetDisplayRoutingError(7, true, DeviceActionDispatcher.Channel.SHIZUKU, 28))
        assertNull(DeviceActionDispatcher.targetDisplayRoutingError(7, true, DeviceActionDispatcher.Channel.SHIZUKU, 29))
    }

    @Test fun `legacy device calls keep previous routing and default screen references allow accessibility`() {
        assertNull(DeviceActionDispatcher.targetDisplayRoutingError(7, false, DeviceActionDispatcher.Channel.ACCESSIBILITY, 28))
        assertNull(DeviceActionDispatcher.targetDisplayRoutingError(7, false, DeviceActionDispatcher.Channel.SHIZUKU, 28))
        assertNull(DeviceActionDispatcher.targetDisplayRoutingError(0, true, DeviceActionDispatcher.Channel.ACCESSIBILITY, 35))
    }
}
