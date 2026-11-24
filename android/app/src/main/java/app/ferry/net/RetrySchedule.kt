package app.ferry.net

/**
 * Discovery back-off while a phone screen is visible and nothing was found (M19, P14): a quiet retry every
 * [fastMs] (10 s) during the first [fastForMs] (1 min), then every [slowMs] (60 s). [reset] starts the fast minute
 * again (screen resumed, a "Try again" tap, a network change). The TV keeps 10 s ([fixed], mains power).
 * Pure Kotlin (RetryScheduleTest); times are milliseconds from any monotonic clock.
 */
class RetrySchedule(
    now: Long,
    private val fixed: Boolean = false,
    private val fastMs: Long = 10_000,
    private val slowMs: Long = 60_000,
    private val fastForMs: Long = 60_000,
) {
    private var since = now
    private var next = now + fastMs

    /** Retries since the last [reset] (logged as `retry=n`). */
    var retries = 0
        private set

    @Synchronized
    fun reset(now: Long) {
        since = now
        next = now + fastMs
        retries = 0
    }

    @Synchronized
    fun due(now: Long): Boolean = now >= next

    /** A retry starts now: returns its number (1, 2, …) and plans the next one. */
    @Synchronized
    fun fired(now: Long): Int {
        retries++
        next = now + interval(now)
        return retries
    }

    /** The wait before the next retry when one fires at [now]. */
    @Synchronized
    fun interval(now: Long): Long = if (fixed || now - since < fastForMs) fastMs else slowMs
}
