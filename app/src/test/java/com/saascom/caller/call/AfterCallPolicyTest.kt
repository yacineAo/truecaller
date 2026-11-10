package com.saascom.caller.call

import org.junit.Assert.assertEquals
import org.junit.Test

/** The table of phases/phase-2-after-call.md 2.2. */
class AfterCallPolicyTest {
    private fun decide(known: Boolean, canLog: Boolean = known, answered: Boolean, seconds: Int = 60) =
        AfterCallPolicy.decide(known, canLog, answered, seconds)

    @Test
    fun answeredClientIsOfferedToLogTheCall() {
        assertEquals(AfterCallAction.LogPrompt, decide(known = true, answered = true))
    }

    @Test
    fun answeredUnknownNumberIsOfferedToBecomeALead() {
        assertEquals(AfterCallAction.AddLeadPrompt, decide(known = false, answered = true))
    }

    @Test
    fun missedClientGetsACallBackNotice() {
        assertEquals(AfterCallAction.MissedNotice, decide(known = true, answered = false, seconds = 0))
    }

    @Test
    fun missedUnknownNumberDoesNothing() {
        assertEquals(AfterCallAction.Nothing, decide(known = false, answered = false, seconds = 0))
    }

    @Test
    fun aVeryShortAnsweredCallCountsAsMissed() {
        assertEquals(AfterCallAction.MissedNotice, decide(known = true, answered = true, seconds = 4))
        assertEquals(AfterCallAction.LogPrompt, decide(known = true, answered = true, seconds = 5))
        assertEquals(AfterCallAction.Nothing, decide(known = false, answered = true, seconds = 2))
    }

    @Test
    fun anotherAgentsClientIsNotLoggedFromHere() {
        assertEquals(AfterCallAction.Nothing, decide(known = true, canLog = false, answered = true))
        assertEquals(AfterCallAction.MissedNotice, decide(known = true, canLog = false, answered = false, seconds = 0))
    }
}
