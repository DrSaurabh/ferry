package app.ferry

/** Pure TV-vs-phone decision (plan §4.2), unit-tested in DeviceModeLogicTest. */
object DeviceModeLogic {
    /**
     * TV when the UI mode is television or the device has the leanback feature. Debug builds only: the
     * `app.ferry.debug.UI` override "tv" / "phone" wins (emulator tests) [B27].
     */
    fun isTv(uiModeTelevision: Boolean, hasLeanback: Boolean, debugBuild: Boolean, override: String?): Boolean {
        if (debugBuild) {
            when (override) {
                "tv" -> return true
                "phone" -> return false
            }
        }
        return uiModeTelevision || hasLeanback
    }
}

/**
 * The version rule (design §3.3, plan §4.1) [H26] [CP13]: on `main` (`FERRY_RELEASE=1`, `FERRY_RUN_NUMBER=n`)
 * versionCode = 20000 + n and versionName = "2.1.n" (2.1, CP104; was 2.0.n); otherwise 1 / "2.1.0-dev". `app/build.gradle.kts` applies
 * the same rule (a build script cannot call app code); VersionRuleTest checks the rule and that BuildConfig
 * follows it for the environment the test runs in.
 */
object VersionRule {
    fun code(release: Boolean, runNumber: Int?): Int = if (release && runNumber != null) 20000 + runNumber else 1
    fun name(release: Boolean, runNumber: Int?): String = if (release && runNumber != null) "2.1.$runNumber" else "2.1.0-dev"
}
