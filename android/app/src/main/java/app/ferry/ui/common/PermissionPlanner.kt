package app.ferry.ui.common

/** Full / Partial (Android 14 "Select photos") / None (design §4.2). */
enum class MediaAccess { Full, Partial, None }

/**
 * Which runtime permissions Ferry asks for, by API level (plan §4.6, design §4.2). Pure Kotlin (PermissionPlannerTest);
 * permission names are plain strings so the JVM test needs no Android runtime. Written in Ph5a because the location
 * choice needs it [PA10]; Ph6 adds the picker's request flow on top.
 */
object PermissionPlanner {
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val ACCESS_MEDIA_LOCATION = "android.permission.ACCESS_MEDIA_LOCATION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** One request for "Photos and videos + location in photos" at the first "Send photos & videos". */
    fun mediaPermissions(sdk: Int): List<String> = when {
        sdk >= 34 -> listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION)
        sdk == 33 -> listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION)
        sdk >= 29 -> listOf(READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION)
        else -> listOf(READ_EXTERNAL_STORAGE)
    }

    fun access(granted: Set<String>, sdk: Int): MediaAccess {
        if (sdk >= 33) {
            val images = READ_MEDIA_IMAGES in granted
            val video = READ_MEDIA_VIDEO in granted
            return when {
                images && video -> MediaAccess.Full
                images || video -> MediaAccess.Partial
                sdk >= 34 && READ_MEDIA_VISUAL_USER_SELECTED in granted -> MediaAccess.Partial
                else -> MediaAccess.None
            }
        }
        return if (READ_EXTERNAL_STORAGE in granted) MediaAccess.Full else MediaAccess.None
    }

    /** "Photo location" access exists from Android 10 (API 29); below that nothing is redacted. */
    fun locationPermission(sdk: Int): String? = if (sdk >= 29) ACCESS_MEDIA_LOCATION else null

    /**
     * What **Allow** in the location choice asks for (audit O1): the whole photo set, as the picker does. Asked for
     * alone, "photo location" is refused without any dialog on recent Android when Ferry has no photo access yet
     * (the first Share → Ferry). Empty below Android 10: nothing is redacted there.
     */
    fun locationRequest(sdk: Int): List<String> = if (sdk >= 29) mediaPermissions(sdk) else emptyList()

    /**
     * Does a photo/video need the "Allow / Send without location" choice [H19] [A2]? On 29+ when the permission is
     * missing, or when opening the original (setRequireOriginal) failed although it is granted.
     */
    fun needsLocationChoice(sdk: Int, hasLocationPermission: Boolean, originalOpened: Boolean): Boolean =
        sdk >= 29 && (!hasLocationPermission || !originalOpened)

    /** POST_NOTIFICATIONS is a runtime permission from Android 13 (API 33); asked when the first transfer starts. */
    fun needsNotificationRequest(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean =
        sdk >= 33 && !granted && !askedBefore
}
