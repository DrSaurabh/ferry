package app.ferry.core.upload

/**
 * Moving average of a byte counter over the last [windowMs] (design §4.4: "speed is a 3 s moving average").
 * Feed it the cumulative number of bytes moved ([add]); [bytesPerSecond] compares the newest count with the
 * count at (or just before) `now - windowMs`. A stall makes the speed fall to 0 within one window.
 * Not thread-safe: the engine calls it under its lock.
 */
class SpeedMeter(private val windowMs: Long = 3_000) {
    private val times = ArrayDeque<Long>()
    private val counts = ArrayDeque<Long>()

    fun add(nowMs: Long, totalBytes: Long) {
        if (times.isNotEmpty() && times.last() == nowMs) {
            counts.removeLast()
            times.removeLast()
        }
        times.addLast(nowMs)
        counts.addLast(totalBytes)
        trim(nowMs)
    }

    fun bytesPerSecond(nowMs: Long): Long {
        trim(nowMs)
        if (times.isEmpty()) return 0
        val baseT = times.first()
        val elapsed = nowMs - baseT
        if (elapsed <= 0) return 0
        val moved = counts.last() - counts.first()
        return if (moved <= 0) 0 else moved * 1000 / elapsed
    }

    fun reset() {
        times.clear()
        counts.clear()
    }

    /** Keeps exactly one sample at or before the window start as the baseline. */
    private fun trim(nowMs: Long) {
        val start = nowMs - windowMs
        while (times.size >= 2 && times[1] <= start) {
            times.removeFirst()
            counts.removeFirst()
        }
    }
}
