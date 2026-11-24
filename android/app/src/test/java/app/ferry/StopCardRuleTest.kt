package app.ferry

import app.ferry.service.StopCard
import app.ferry.service.StopCardRule
import org.junit.Assert.assertEquals
import org.junit.Test

/** U9 (M23, H87): the stop card only after a cut-off send; vivo steps on vivo only; no steps after a crash. */
class StopCardRuleTest {
    @Test fun nothingWithoutAnInterruptedSend() {
        assertEquals(StopCard.None, StopCardRule.decide(0, "low_memory", "vivo"))
        assertEquals(StopCard.None, StopCardRule.decide(0, null, "Google"))
    }

    @Test fun vivoStepsOnlyOnVivo() {
        assertEquals(StopCard.Vivo, StopCardRule.decide(3, "low_memory", "vivo"))
        assertEquals(StopCard.Vivo, StopCardRule.decide(1, null, "VIVO "))
        assertEquals(StopCard.Generic, StopCardRule.decide(3, "low_memory", "samsung"))
        assertEquals(StopCard.Generic, StopCardRule.decide(3, "signaled", "Google"))
    }

    @Test fun unknownExitStillShowsTheSteps() {
        // below API 30 there is no exit reason; the interrupted count alone shows the system stopped the send
        assertEquals(StopCard.Generic, StopCardRule.decide(2, null, "Xiaomi"))
        assertEquals(StopCard.Generic, StopCardRule.decide(2, "user_requested", "Xiaomi"))
    }

    @Test fun aCrashIsNotThePhonesFault() {
        assertEquals(StopCard.Plain, StopCardRule.decide(2, "crash", "vivo"))
        assertEquals(StopCard.Plain, StopCardRule.decide(2, "anr", "samsung"))
        assertEquals(StopCard.Plain, StopCardRule.decide(2, "crash_native", "Google"))
    }
}
