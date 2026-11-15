package app.ferry

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionRuleTest {
    @Test fun mainBuildsAre20000PlusRunNumber() {
        assertEquals(20057, VersionRule.code(release = true, runNumber = 57))
        assertEquals("2.0.57", VersionRule.name(release = true, runNumber = 57))
    }

    @Test fun otherBuildsAreDev() {
        assertEquals(1, VersionRule.code(release = false, runNumber = 57))
        assertEquals("2.0.0-dev", VersionRule.name(release = false, runNumber = 57))
        assertEquals(1, VersionRule.code(release = true, runNumber = null))
    }

    /** app/build.gradle.kts applies the same rule: BuildConfig must match it for this build's environment. */
    @Test fun buildConfigFollowsTheRule() {
        val release = System.getenv("FERRY_RELEASE") == "1"
        val run = System.getenv("FERRY_RUN_NUMBER")?.toIntOrNull()
        assertEquals(VersionRule.code(release, run), BuildConfig.VERSION_CODE)
        assertEquals(VersionRule.name(release, run), BuildConfig.VERSION_NAME)
    }
}
