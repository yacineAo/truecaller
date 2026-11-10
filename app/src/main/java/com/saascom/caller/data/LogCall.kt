package com.saascom.caller.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the agent chose for the next reminder in the after-call form. */
sealed interface ReminderChoice {
    /** Untouched: the CRM keeps the current reminder (field omitted). */
    data object Keep : ReminderChoice

    /** «حذف الموعد»: the CRM clears the reminder (field sent as null). */
    data object Clear : ReminderChoice

    /** Quick chips «بعد 3 / 5 / 7 / 14 / 30 يوم»: the CRM computes the date (skips Fridays and holidays). */
    data class InBusinessDays(val days: Int) : ReminderChoice

    /** A date picked by hand, as UTC ISO-8601. */
    data class At(val isoUtc: String) : ReminderChoice
}

/**
 * Body of POST /leads/{id}/calls (api-contract.md §5).
 *
 * Built by hand because "omit" and "null" mean different things for reminder_date,
 * and the shared [ApiJson] drops nulls.
 */
fun logCallBody(result: String, notes: String, newStatusId: Long?, reminder: ReminderChoice): JsonObject =
    buildJsonObject {
        put("result", result)
        put("notes", notes)
        newStatusId?.let { put("new_status_id", it) }
        when (reminder) {
            ReminderChoice.Keep -> Unit
            ReminderChoice.Clear -> put("reminder_date", JsonNull)
            is ReminderChoice.InBusinessDays -> put("reminder_in_business_days", reminder.days)
            is ReminderChoice.At -> put("reminder_date", reminder.isoUtc)
        }
    }
