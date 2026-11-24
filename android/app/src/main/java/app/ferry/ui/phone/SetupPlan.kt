package app.ferry.ui.phone

/** The steps of the first setup (U2, design §5.5, M23); [Name] is the last screen, not a numbered step. */
enum class SetupStep(val logName: String) {
    Media("media"),
    Notifications("notifications"),
    Battery("battery"),
    Updates("updates"),
    Name("name"),
}

/** What is already granted when setup starts (already-granted steps are skipped). */
data class SetupGranted(
    val media: Boolean = false,
    val notifications: Boolean = false,
    val battery: Boolean = false,
    val installs: Boolean = false,
)

/**
 * Which setup steps show (U2, M16, M23; pure for SetupPlanTest): (1) photos & videos + location, (2) notifications
 * (Android 13+), (3) battery exemption, (4) "Install unknown apps" for updates (Android 8+,
 * `ACTION_MANAGE_UNKNOWN_APP_SOURCES` does not exist on 7.1), then the name screen. The vivo lock step is gone (U9).
 */
object SetupPlan {
    fun steps(sdk: Int, granted: SetupGranted): List<SetupStep> = buildList {
        if (!granted.media) add(SetupStep.Media)
        if (sdk >= 33 && !granted.notifications) add(SetupStep.Notifications)
        if (!granted.battery) add(SetupStep.Battery)
        if (sdk >= 26 && !granted.installs) add(SetupStep.Updates)
        add(SetupStep.Name)
    }

    /**
     * Setup left with Back on step 1 (or the app closed) resumes where it stopped (H94): the first planned step at
     * or after the saved one; steps granted meanwhile are already left out of [steps].
     */
    fun resumeAt(steps: List<SetupStep>, saved: String?): Int {
        val s = SetupStep.entries.firstOrNull { it.logName == saved } ?: return 0
        val i = steps.indexOfFirst { it.ordinal >= s.ordinal }
        return if (i < 0) 0 else i
    }

    /** "Step 2 of 4": the name screen is not counted. */
    fun numbered(steps: List<SetupStep>): Int = steps.count { it != SetupStep.Name }
}
