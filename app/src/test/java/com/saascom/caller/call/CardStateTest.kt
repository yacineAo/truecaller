package com.saascom.caller.call

import com.saascom.caller.data.LeadCardDto
import com.saascom.caller.data.LeadStatusDto
import com.saascom.caller.data.LookupResponse
import com.saascom.caller.data.PersonDto
import com.saascom.caller.util.Formats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStateTest {
    private val lead = LeadCardDto(
        id = 1,
        name = "محمد",
        company = "صيدلية النور",
        status = LeadStatusDto(2, "مهتم", "interested"),
        assignedTo = PersonDto(3, "كريم"),
    )

    @Test
    fun lookupMapsToTheRightShape() {
        assertTrue(CardState.fromLookup("0550123456", LookupResponse(found = true, fullAccess = true, lead = lead)) is CardState.Full)
        assertTrue(CardState.fromLookup("0550123456", LookupResponse(found = true, fullAccess = false, lead = lead)) is CardState.Limited)
        assertTrue(CardState.fromLookup("0550123456", LookupResponse(found = false, phone = "0550123456")) is CardState.Unknown)
        assertTrue(CardState.fromLookup("0550123456", LookupResponse(found = true, fullAccess = true, lead = null)) is CardState.Unknown)
    }

    @Test
    fun notificationSummary() {
        assertEquals(CardSummary("محمد", "مهتم · كريم"), CardState.Full("0550123456", lead).summary())
        assertEquals(CardSummary(Formats.phone("0550123456"), "رقم غير موجود في CRM"), CardState.Unknown("0550123456").summary())

        val noName = lead.copy(name = null)
        assertEquals("صيدلية النور", CardState.Limited("0550123456", noName).summary().title)

        val local = LocalInfo(1, null, null, "new", "جديد", null)
        assertEquals(CardSummary(Formats.phone("0550123456"), "جديد"), CardState.Offline("0550123456", local).summary())
    }
}
