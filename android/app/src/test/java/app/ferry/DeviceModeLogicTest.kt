package app.ferry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModeLogicTest {
    @Test fun televisionUiModeOrLeanbackMeansTv() {
        assertTrue(DeviceModeLogic.isTv(uiModeTelevision = true, hasLeanback = false, debugBuild = false, override = null))
        assertTrue(DeviceModeLogic.isTv(uiModeTelevision = false, hasLeanback = true, debugBuild = false, override = null))
        assertFalse(DeviceModeLogic.isTv(uiModeTelevision = false, hasLeanback = false, debugBuild = false, override = null))
    }

    @Test fun overrideOnlyInDebugBuilds() {
        assertTrue(DeviceModeLogic.isTv(false, false, debugBuild = true, override = "tv"))
        assertFalse(DeviceModeLogic.isTv(true, true, debugBuild = true, override = "phone"))
        assertFalse(DeviceModeLogic.isTv(false, false, debugBuild = false, override = "tv"))
        assertTrue(DeviceModeLogic.isTv(true, false, debugBuild = false, override = "phone"))
        assertTrue(DeviceModeLogic.isTv(true, false, debugBuild = true, override = "junk"))
    }
}
