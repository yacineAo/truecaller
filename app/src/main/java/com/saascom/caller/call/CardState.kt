package com.saascom.caller.call

import com.saascom.caller.data.DirectoryEntry
import com.saascom.caller.data.LeadCardDto
import com.saascom.caller.data.LookupResponse
import com.saascom.caller.util.Labels
import com.saascom.caller.util.Formats

/** What the client list on the phone knows about a number (shown instantly). */
data class LocalInfo(
    val leadId: Long,
    val name: String?,
    val company: String?,
    val stage: String,
    val status: String,
    val owner: String?,
)

fun DirectoryEntry.toLocal() = LocalInfo(leadId, name, company, stage, status, owner)

/** The card over the call screen goes through these shapes. */
sealed interface CardState {
    val phone: String

    /** Local list info right away; the CRM details are on their way. */
    data class Loading(override val phone: String, val local: LocalInfo) : CardState

    /** Full CRM card (the agent may see this lead). */
    data class Full(override val phone: String, val lead: LeadCardDto) : CardState

    /** Another agent's lead: name, status and owner only. */
    data class Limited(override val phone: String, val lead: LeadCardDto) : CardState

    /** The CRM does not know this number. */
    data class Unknown(override val phone: String) : CardState

    /** The CRM did not answer in time: local list info only. */
    data class Offline(override val phone: String, val local: LocalInfo) : CardState

    companion object {
        fun fromLookup(phone: String, response: LookupResponse): CardState {
            val lead = response.lead
            return when {
                !response.found || lead == null -> Unknown(phone)
                response.fullAccess -> Full(phone, lead)
                else -> Limited(phone, lead)
            }
        }
    }
}

/** Short text version of a card, for the notification fallback. */
data class CardSummary(val title: String, val text: String)

fun CardState.summary(): CardSummary {
    val prettyPhone = Formats.phone(phone)
    fun titleOf(name: String?, company: String?) = name?.takeIf { it.isNotBlank() }
        ?: company?.takeIf { it.isNotBlank() }
        ?: prettyPhone

    return when (this) {
        is CardState.Loading -> CardSummary(titleOf(local.name, local.company), listOfNotNull(local.status, local.owner).joinToString(" · "))
        is CardState.Offline -> CardSummary(titleOf(local.name, local.company), listOfNotNull(local.status, local.owner).joinToString(" · "))
        is CardState.Full -> CardSummary(
            titleOf(lead.name, lead.company),
            listOfNotNull(
                lead.status.label,
                lead.assignedTo?.name,
                lead.lastCall?.let { Labels.callResult(it.result) },
            ).joinToString(" · "),
        )
        is CardState.Limited -> CardSummary(titleOf(lead.name, lead.company), listOfNotNull(lead.status.label, lead.assignedTo?.name).joinToString(" · "))
        is CardState.Unknown -> CardSummary(prettyPhone, "رقم غير موجود في CRM")
    }
}
