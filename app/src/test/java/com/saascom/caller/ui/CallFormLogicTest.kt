package com.saascom.caller.ui

import com.saascom.caller.data.FollowUpDto
import com.saascom.caller.data.ReminderChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CallFormLogicTest {
    private val now = Instant.parse("2026-11-10T08:00:00Z")

    @Test
    fun unreachableResultsWriteTheirLabelAsNoteLikeTheCrm() {
        assertEquals("لا يجيب", CallFormLogic.autoNote("no_answer"))
        assertEquals("مشغول", CallFormLogic.autoNote("busy"))
        assertEquals("مغلق", CallFormLogic.autoNote("closed"))
        assertEquals("", CallFormLogic.autoNote("available"))
    }

    @Test
    fun futureSuggestionIsPrefilledForUnreachableResults() {
        val followUp = FollowUpDto(action = "remind", date = "2026-11-12T09:00:00Z")

        val prefill = CallFormLogic.prefill("no_answer", followUp, now)!!

        assertEquals(ReminderChoice.At("2026-11-12T09:00:00Z"), prefill.reminder)
        assertNull(prefill.newStatusId)
        assertEquals(false, prefill.archive)
    }

    @Test
    fun pastSuggestionIsNotPrefilledButFlagged() {
        // Old leads: the CRM counts from the day the lead arrived, so the date can be in the past.
        val prefill = CallFormLogic.prefill("busy", FollowUpDto(action = "remind", date = "2026-08-12T09:00:00Z"), now)!!

        assertEquals(ReminderChoice.Keep, prefill.reminder)
        assertNull(prefill.newStatusId)
        assertEquals(false, prefill.archive)
        assertTrue(prefill.datePassed)
    }

    @Test
    fun futureSuggestionIsNotFlagged() {
        val prefill = CallFormLogic.prefill("busy", FollowUpDto(action = "remind", date = "2026-11-12T09:00:00Z"), now)!!

        assertEquals(false, prefill.datePassed)
    }

    @Test
    fun missingSuggestedDateGivesNoSuggestion() {
        assertNull(CallFormLogic.prefill("busy", FollowUpDto(action = "remind", date = null), now))
    }

    @Test
    fun lastAttemptSuggestsTheArchiveStatusAndClearsTheReminder() {
        val prefill = CallFormLogic.prefill("closed", FollowUpDto(action = "archive", archiveStatusId = 11), now)!!

        assertEquals(ReminderChoice.Clear, prefill.reminder)
        assertEquals(11L, prefill.newStatusId)
        assertTrue(prefill.archive)
    }

    @Test
    fun answeredCallsGetNoSuggestion() {
        assertNull(CallFormLogic.prefill("available", FollowUpDto(action = "remind", date = "2026-11-12T09:00:00Z"), now))
        assertNull(CallFormLogic.prefill("no_answer", null, now))
    }

    @Test
    fun pickedDateAndTimeAreAlgiersClock() {
        val day = LocalDate.of(2026, 11, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        // 09:00 in Algiers (UTC+1) is 08:00 UTC.
        assertEquals(Instant.parse("2026-11-15T08:00:00Z"), CallFormLogic.reminderAt(day, 9, 0))
    }

    @Test
    fun durationIsMinutesAndSeconds() {
        assertEquals("4:12", duration(252))
        assertEquals("0:05", duration(5))
    }
}
