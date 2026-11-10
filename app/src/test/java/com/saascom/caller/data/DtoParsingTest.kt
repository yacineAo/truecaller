package com.saascom.caller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads shaped like phases/api-contract.md (made-up data only: never paste real clients here). */
class DtoParsingTest {

    @Test
    fun fullLookup() {
        val json = """
        {"found":true,"full_access":true,"lead":{"id":1234,"name":"محمد بن علي","company":"صيدلية النور",
         "phone":"0550123456","secondary_phone":"0661987654","wilaya":"وهران","business_type":"صيدلية",
         "status":{"id":2,"label":"مهتم","stage":"interested"},"assigned_to":{"id":3,"name":"كريم"},
         "last_call":{"at":"2026-11-06T10:15:00Z","result":"available","by":"سارة","note":"طلب عرض سعر"},
         "next_reminder":"2026-11-15T08:00:00Z",
         "subscription":{"type":"subscription","type_label":"اشتراك","required":30000,"paid":20000,"remaining":10000,
           "payment_status":"partial","payment_status_label":"دفع جزئي","should_pay_on":"2026-11-20"},
         "open_requests_count":1,"open_requests":[{"id":9,"title":"مشكل في الطباعة","priority":"urgent","status":"in_progress","created_at":"2026-11-08T09:00:00Z"}],
         "tags":["VIP"],"follow_up_if_unreachable":{"action":"remind","date":"2026-11-12T09:00:00Z","archive_status_id":null},
         "crm_url":"https://crm.example.dz/admin/leads/1234","whatsapp_url":"https://wa.me/213550123456",
         "a_field_added_later":{"x":1}}}
        """
        val response = ApiJson.decodeFromString(LookupResponse.serializer(), json)

        assertTrue(response.found && response.fullAccess)
        val lead = response.lead!!
        assertEquals("محمد بن علي", lead.name)
        assertEquals("interested", lead.status.stage)
        assertEquals("كريم", lead.assignedTo?.name)
        assertEquals("available", lead.lastCall?.result)
        assertEquals(10000.0, lead.subscription!!.remaining, 0.0)
        assertEquals("urgent", lead.openRequests.single().priority)
        assertEquals("remind", lead.followUp?.action)
        assertNull(lead.followUp?.archiveStatusId)
    }

    @Test
    fun limitedLookupHasOnlyTheBasics() {
        val json = """{"found":true,"full_access":false,"lead":{"id":1234,"name":"محمد","company":null,
            "status":{"id":2,"label":"مهتم","stage":"interested"},"assigned_to":{"id":5,"name":"أمين"}}}"""
        val response = ApiJson.decodeFromString(LookupResponse.serializer(), json)

        assertFalse(response.fullAccess)
        assertEquals("أمين", response.lead?.assignedTo?.name)
        assertNull(response.lead?.lastCall)
        assertEquals(0, response.lead?.openRequestsCount)
    }

    @Test
    fun newLeadHasNullStatusId() {
        val json = """{"found":true,"full_access":true,"lead":{"id":7,"name":null,"company":null,
            "status":{"id":null,"label":"جديد","stage":"new"},"assigned_to":null,"last_call":null,"subscription":null}}"""
        val lead = ApiJson.decodeFromString(LookupResponse.serializer(), json).lead!!

        assertNull(lead.status.id)
        assertEquals("new", lead.status.stage)
        assertNull(lead.assignedTo)
    }

    @Test
    fun notFound() {
        val response = ApiJson.decodeFromString(LookupResponse.serializer(), """{"found":false,"phone":"0661987654"}""")
        assertFalse(response.found)
        assertEquals("0661987654", response.phone)
        assertNull(response.lead)
    }

    @Test
    fun directory() {
        val json = """{"generated_at":"2026-11-09T18:00:00Z",
          "statuses":[{"id":1,"name":"مشترك","stage":"won"},{"id":2,"name":"مهتم","stage":"interested"}],
          "entries":[{"lead_id":6,"phone":"0550000006","name":"Client Test","company":null,"stage":"closed","status":"أرشيف","owner":"Agent Test"},
                     {"lead_id":7,"phone":"0661987654","name":null,"company":"مكتبة","stage":"new","status":"جديد","owner":null}]}"""
        val directory = ApiJson.decodeFromString(DirectoryResponse.serializer(), json)

        assertEquals(2, directory.statuses.size)
        assertEquals(2, directory.entries.size)
        assertEquals("Agent Test", directory.entries[0].owner)
        assertNull(directory.entries[1].name)
    }
}
