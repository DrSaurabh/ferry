package app.ferry

import app.ferry.ui.common.MediaAccess
import app.ferry.ui.common.PermissionPlanner
import app.ferry.ui.common.PermissionPlanner.ACCESS_MEDIA_LOCATION
import app.ferry.ui.common.PermissionPlanner.READ_EXTERNAL_STORAGE
import app.ferry.ui.common.PermissionPlanner.READ_MEDIA_IMAGES
import app.ferry.ui.common.PermissionPlanner.READ_MEDIA_VIDEO
import app.ferry.ui.common.PermissionPlanner.READ_MEDIA_VISUAL_USER_SELECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §4.6 / §4.13: permission lists per API level and the Full/Partial/None decision. */
class PermissionPlannerTest {
    @Test
    fun locationChoiceAllowAsksForThePhotoSetNotLocationAlone() { // audit O1
        assertEquals(emptyList<String>(), PermissionPlanner.locationRequest(25))
        assertEquals(emptyList<String>(), PermissionPlanner.locationRequest(28))
        for (sdk in listOf(29, 32, 33, 34, 35)) {
            val asked = PermissionPlanner.locationRequest(sdk)
            assertEquals(PermissionPlanner.mediaPermissions(sdk), asked)
            assertTrue("sdk $sdk asks for photo location", ACCESS_MEDIA_LOCATION in asked)
            assertTrue("sdk $sdk never asks for photo location alone", asked.size > 1)
        }
    }

    @Test
    fun listsPerSdk() {
        assertEquals(listOf(READ_EXTERNAL_STORAGE), PermissionPlanner.mediaPermissions(25))
        assertEquals(listOf(READ_EXTERNAL_STORAGE), PermissionPlanner.mediaPermissions(28))
        assertEquals(listOf(READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION), PermissionPlanner.mediaPermissions(29))
        assertEquals(listOf(READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION), PermissionPlanner.mediaPermissions(32))
        assertEquals(listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION), PermissionPlanner.mediaPermissions(33))
        val all = listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION)
        assertEquals(all, PermissionPlanner.mediaPermissions(34))
        assertEquals(all, PermissionPlanner.mediaPermissions(35))
    }

    @Test
    fun accessFullPartialNone() {
        assertEquals(MediaAccess.Full, PermissionPlanner.access(setOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO), 34))
        assertEquals(MediaAccess.Partial, PermissionPlanner.access(setOf(READ_MEDIA_VISUAL_USER_SELECTED), 34))
        assertEquals(MediaAccess.Partial, PermissionPlanner.access(setOf(READ_MEDIA_IMAGES), 35))
        assertEquals(MediaAccess.None, PermissionPlanner.access(emptySet(), 34))
        assertEquals(MediaAccess.Full, PermissionPlanner.access(setOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO), 33))
        assertEquals(MediaAccess.None, PermissionPlanner.access(setOf(READ_MEDIA_VISUAL_USER_SELECTED), 33))
        assertEquals(MediaAccess.Full, PermissionPlanner.access(setOf(READ_EXTERNAL_STORAGE), 29))
        assertEquals(MediaAccess.Full, PermissionPlanner.access(setOf(READ_EXTERNAL_STORAGE), 25))
        assertEquals(MediaAccess.None, PermissionPlanner.access(setOf(READ_MEDIA_IMAGES), 28))
    }

    @Test
    fun locationChoice() {
        assertNull(PermissionPlanner.locationPermission(28))
        assertEquals(ACCESS_MEDIA_LOCATION, PermissionPlanner.locationPermission(29))
        assertFalse("nothing is redacted below Android 10", PermissionPlanner.needsLocationChoice(28, false, false))
        assertTrue(PermissionPlanner.needsLocationChoice(34, hasLocationPermission = false, originalOpened = false))
        assertTrue("granted but the original did not open", PermissionPlanner.needsLocationChoice(34, true, false))
        assertFalse(PermissionPlanner.needsLocationChoice(34, true, true))
    }

    @Test
    fun notificationsAskedOnceOn33Plus() {
        assertFalse(PermissionPlanner.needsNotificationRequest(32, false, false))
        assertTrue(PermissionPlanner.needsNotificationRequest(33, false, false))
        assertFalse(PermissionPlanner.needsNotificationRequest(34, true, false))
        assertFalse(PermissionPlanner.needsNotificationRequest(34, false, true))
    }
}
