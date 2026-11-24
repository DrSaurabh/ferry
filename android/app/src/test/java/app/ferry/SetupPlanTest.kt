package app.ferry

import app.ferry.ui.phone.SetupGranted
import app.ferry.ui.phone.SetupPlan
import app.ferry.ui.phone.SetupStep
import app.ferry.ui.phone.SetupStep.Battery
import app.ferry.ui.phone.SetupStep.Media
import app.ferry.ui.phone.SetupStep.Name
import app.ferry.ui.phone.SetupStep.Notifications
import app.ferry.ui.phone.SetupStep.Updates
import org.junit.Assert.assertEquals
import org.junit.Test

/** U2 (design §5.5, M16, M23): which setup steps show per API level; granted steps are skipped; resume (H94). */
class SetupPlanTest {
    private val none = SetupGranted()

    @Test fun api34FreshInstallHasFourStepsAndTheName() {
        assertEquals(listOf(Media, Notifications, Battery, Updates, Name), SetupPlan.steps(34, none))
        assertEquals(4, SetupPlan.numbered(SetupPlan.steps(34, none)))
    }

    @Test fun api33AlsoAsksForNotifications() {
        assertEquals(listOf(Media, Notifications, Battery, Updates, Name), SetupPlan.steps(33, none))
    }

    @Test fun api29HasNoNotificationStep() {
        assertEquals(listOf(Media, Battery, Updates, Name), SetupPlan.steps(29, none))
    }

    @Test fun api25HasNoUnknownSourcesPage() {
        // ACTION_MANAGE_UNKNOWN_APP_SOURCES is Android 8+ (API 26).
        assertEquals(listOf(Media, Battery, Name), SetupPlan.steps(25, none))
    }

    @Test fun grantedStepsAreSkipped() {
        val g = SetupGranted(media = true, notifications = true, battery = false, installs = true)
        assertEquals(listOf(Battery, Name), SetupPlan.steps(34, g))
        val all = SetupGranted(true, true, true, true)
        assertEquals(listOf(Name), SetupPlan.steps(34, all))
        assertEquals(0, SetupPlan.numbered(SetupPlan.steps(34, all)))
    }

    @Test fun resumesWhereSetupStopped() {
        val steps = SetupPlan.steps(34, none)
        assertEquals(0, SetupPlan.resumeAt(steps, null))
        assertEquals(0, SetupPlan.resumeAt(steps, "nonsense"))
        assertEquals(2, SetupPlan.resumeAt(steps, Battery.logName))
        // battery granted meanwhile: resume at the next step that is still planned
        val later = SetupPlan.steps(34, SetupGranted(media = true, notifications = true, battery = true))
        assertEquals(listOf(Updates, Name), later)
        assertEquals(0, SetupPlan.resumeAt(later, Battery.logName))
        assertEquals(1, SetupPlan.resumeAt(later, SetupStep.Name.logName))
    }
}
