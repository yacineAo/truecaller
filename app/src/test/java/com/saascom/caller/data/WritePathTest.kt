package com.saascom.caller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** Log-call body (omit vs null), outbox retry rules and number search. */
class WritePathTest {

    @Test
    fun untouchedReminderIsOmittedSoTheCrmKeepsIt() {
        val body = logCallBody("available", "سيرسل الوثائق", newStatusId = null, reminder = ReminderChoice.Keep)

        assertEquals("""{"result":"available","notes":"سيرسل الوثائق"}""", body.toString())
    }

    @Test
    fun clearedReminderIsSentAsNull() {
        val body = logCallBody("busy", "مشغول", newStatusId = 2, reminder = ReminderChoice.Clear)

        assertEquals("""{"result":"busy","notes":"مشغول","new_status_id":2,"reminder_date":null}""", body.toString())
    }

    @Test
    fun quickChipsLetTheCrmComputeTheDate() {
        val body = logCallBody("no_answer", "لا يجيب", null, ReminderChoice.InBusinessDays(3))

        assertEquals("""{"result":"no_answer","notes":"لا يجيب","reminder_in_business_days":3}""", body.toString())
    }

    @Test
    fun pickedDateIsSentAsIso() {
        val body = logCallBody("available", ".", null, ReminderChoice.At("2026-11-15T08:00:00Z"))

        assertEquals("""{"result":"available","notes":".","reminder_date":"2026-11-15T08:00:00Z"}""", body.toString())
    }

    @Test
    fun outboxRetryRules() {
        assertEquals(SendOutcome.RetryLater, Outbox.decide(ApiException.Network(IOException("no route"))))
        assertEquals(SendOutcome.Stop, Outbox.decide(ApiException.Http(401, "الحساب معطّل")))
        assertEquals(SendOutcome.RetryLater, Outbox.decide(ApiException.Http(429, null)))
        assertEquals(SendOutcome.Refused, Outbox.decide(ApiException.Http(422, "اكتب ملاحظة")))
        assertEquals(SendOutcome.Refused, Outbox.decide(ApiException.Http(403, null)))
        assertEquals(SendOutcome.RetryLater, Outbox.decide(ApiException.Http(500, null)))
    }

    @Test
    fun aRefusedWriteGivesTheCallBackToTheAgent() {
        // A call is marked logged when queued; only a refusal undoes it (the CRM never got it).
        assertEquals(true, Outbox.loggedAfter(SendOutcome.Sent))
        assertEquals(false, Outbox.loggedAfter(SendOutcome.Refused))
        assertNull(Outbox.loggedAfter(SendOutcome.RetryLater))
        assertNull(Outbox.loggedAfter(SendOutcome.Stop))
    }

    @Test
    fun aPolicyDenialReadsAsNotYourLead() {
        val notYours = "هذا العميل ليس من عملائك."
        val generic = "حدث خطأ"

        // Laravel's untranslated answer, or no message at all.
        assertEquals(notYours, ApiException.Http(403, "This action is unauthorized.").userMessage(notYours, generic))
        assertEquals(notYours, ApiException.Http(403, null).userMessage(notYours, generic))
        // The CRM's own Arabic 403 is kept, and other codes are never rewritten.
        assertEquals("الحساب معطّل", ApiException.Http(403, "الحساب معطّل").userMessage(notYours, generic))
        assertEquals("اكتب ملاحظة", ApiException.Http(422, "اكتب ملاحظة").userMessage(notYours, generic))
        assertEquals(generic, ApiException.Http(500, null).userMessage(notYours, generic))
    }

    @Test
    fun numberSearchUsesTheLocalFormat() {
        assertEquals("0550123456", searchDigits("0550 12 34 56"))
        assertEquals("0550123456", searchDigits("+213 550 12 34 56"))
        assertEquals("0550123456", searchDigits("00213550123456"))
        assertEquals("1234", searchDigits("1234"))
        assertEquals("2135", searchDigits("2135"), )
        assertNull(searchDigits("محمد"))
        assertNull(searchDigits("Pharmacie 24"))
        assertNull(searchDigits("12"))
    }
}
