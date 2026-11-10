package com.saascom.caller.ui

import com.saascom.caller.data.FollowUpDto
import com.saascom.caller.data.ReminderChoice
import com.saascom.caller.util.Formats
import com.saascom.caller.util.Labels
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * What choosing an unreachable result pre-fills, like the CRM form (FollowUpStrategy).
 * [datePassed]: the CRM's suggested date is already in the past, so nothing is pre-filled and
 * the form only points the agent at the quick-reminder chips.
 */
data class FollowUpPrefill(
    val reminder: ReminderChoice,
    val newStatusId: Long?,
    val archive: Boolean,
    val datePassed: Boolean = false,
)

/** Rules of the after-call form, kept out of Compose so they can be unit-tested. */
object CallFormLogic {
    /** Results that mean "could not reach the client" (FollowUpStrategy::UNREACHABLE_STATUSES). */
    val UNREACHABLE = setOf("no_answer", "busy", "closed")

    val RESULTS = listOf("available", "no_answer", "busy", "closed")

    /** The CRM form writes the result as the note for unreachable calls («لا يجيب»…); empty otherwise. */
    fun autoNote(result: String): String = if (result in UNREACHABLE) Labels.callResult(result) else ""

    /**
     * Reminder suggested by the CRM for an unreachable call.
     * A suggested date in the past (old leads: it counts from the day the lead arrived) is not
     * used: the result keeps the reminder untouched and has [FollowUpPrefill.datePassed] set.
     */
    fun prefill(result: String, followUp: FollowUpDto?, now: Instant = Instant.now()): FollowUpPrefill? {
        if (result !in UNREACHABLE || followUp == null) return null
        return when (followUp.action) {
            "remind" -> Formats.parseInstant(followUp.date)?.let { date ->
                if (date.isAfter(now)) {
                    FollowUpPrefill(ReminderChoice.At(date.toString()), newStatusId = null, archive = false)
                } else {
                    FollowUpPrefill(ReminderChoice.Keep, newStatusId = null, archive = false, datePassed = true)
                }
            }
            "archive" -> FollowUpPrefill(ReminderChoice.Clear, newStatusId = followUp.archiveStatusId, archive = true)
            else -> null
        }
    }

    /**
     * Date picker (UTC midnight of the chosen day) + time picked on the Algiers clock
     * → UTC instant for the CRM.
     */
    fun reminderAt(pickedDateUtcMillis: Long, hour: Int, minute: Int): Instant {
        val day: LocalDate = Instant.ofEpochMilli(pickedDateUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return day.atTime(LocalTime.of(hour, minute)).atZone(Formats.ALGIERS).toInstant()
    }
}
