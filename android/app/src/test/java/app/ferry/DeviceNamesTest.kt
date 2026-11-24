package app.ferry

import app.ferry.log.DeviceNames
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone/TV name chain (design §10, M23, M25-C15, H76). */
class DeviceNamesTest {
    @Test fun phoneOverrideWins() {
        assertEquals("Saurabh's phone" to "override", DeviceNames.choose(false, " Saurabh's phone ", "vivo X200", "vivo", "V2413", "3f2a"))
    }

    @Test fun phoneSystemNameNext() {
        assertEquals("vivo X200 Pro" to "system", DeviceNames.choose(false, null, "vivo X200 Pro", "vivo", "V2413", "3f2a"))
        assertEquals("vivo X200 Pro" to "system", DeviceNames.choose(false, "  ", "vivo X200 Pro", "vivo", "V2413", "3f2a"))
    }

    @Test fun phoneMakerAndModelLast() {
        assertEquals("vivo V2413" to "model", DeviceNames.choose(false, null, null, "vivo", "V2413", "3f2a"))
        assertEquals("Google Pixel 7" to "model", DeviceNames.choose(false, null, "", "Google", "Pixel 7", "3f2a"))
        // A model that already starts with the maker is not doubled.
        assertEquals("samsung SM-S911B" to "model", DeviceNames.choose(false, null, null, "samsung", "samsung SM-S911B", "3f2a"))
    }

    @Test fun tvIgnoresOverrideAndFallsBackToInstallId() {
        assertEquals("Living room" to "system", DeviceNames.choose(true, "My phone", "Living room", "Amazon", "AFTSSS", "3f2a"))
        assertEquals("Fire TV 3f2a" to "fallback", DeviceNames.choose(true, "My phone", null, "Amazon", "AFTSSS", "3f2a"))
    }

    @Test fun nothingKnown() {
        assertEquals("Android 3f2a" to "fallback", DeviceNames.choose(false, null, null, null, null, "3f2a"))
    }
}
