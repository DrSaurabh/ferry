package app.ferry.service

/**
 * The idle stop of `TransferService` [PB10] (plan §4.4), as a pure helper so the race can be unit-tested
 * (StopDecisionTest).
 *
 * `onStartCommand` records its start id and queues its work inside [started]; the idle check asks [stopId] under
 * the same lock. So a send that arrives at the last moment either was queued before the check (then the check
 * sees work and the service stays), or it comes with a newer start id than the one the check returns, and
 * `stopSelfResult(thatId)` returns false (Android compares with the newest start id it has delivered). Work is
 * never dropped and never reported as "Cancelled".
 */
class StopDecision {
    private val lock = Any()
    private var lastStartId = 0

    fun <T> started(startId: Int, work: () -> T): T = synchronized(lock) {
        lastStartId = startId
        work()
    }

    /** The start id to pass to `stopSelfResult`, or null while [isIdle] says there is work. */
    fun stopId(isIdle: () -> Boolean): Int? = synchronized(lock) { if (isIdle()) lastStartId else null }
}
