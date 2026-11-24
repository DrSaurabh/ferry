package app.ferry.service

/** Which "stop card" Home and the Transfer screen show after a send was cut off (U9, M23, H87). */
enum class StopCard { None, Plain, Generic, Vivo }

/**
 * Pure rule (StopCardRuleTest). [interrupted] = files still unsent when the previous process died
 * (`Prefs.interruptedCount`, written by `TransferService` during a send); [exit] = the API 30+ exit reason of that
 * process (`ExitReason`, null below 30 or when unknown). A crash or ANR is Ferry's own fault: then only the plain
 * "stopped early" line shows (battery/lock steps would not help). Otherwise the phone stopped Ferry: vivo steps on
 * vivo phones, generic battery steps on every other phone.
 */
object StopCardRule {
    private val ownFault = setOf("crash", "crash_native", "anr", "init_failure")

    fun decide(interrupted: Int, exit: String?, manufacturer: String): StopCard = when {
        interrupted <= 0 -> StopCard.None
        exit in ownFault -> StopCard.Plain
        manufacturer.trim().equals("vivo", ignoreCase = true) -> StopCard.Vivo
        else -> StopCard.Generic
    }
}
