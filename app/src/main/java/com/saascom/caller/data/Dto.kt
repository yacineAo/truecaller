package com.saascom.caller.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON shapes of /api/mobile/… — see phases/api-contract.md. */

val ApiJson = Json {
    ignoreUnknownKeys = true // the CRM may add fields without breaking older app versions
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class LoginResponse(val token: String, val user: UserDto)

@Serializable
data class UserDto(val id: Long, val name: String, val role: String)

@Serializable
data class DirectoryResponse(
    @SerialName("generated_at") val generatedAt: String? = null,
    val statuses: List<StatusDto> = emptyList(),
    val entries: List<DirectoryEntryDto> = emptyList(),
)

@Serializable
data class StatusDto(val id: Long, val name: String, val stage: String)

@Serializable
data class DirectoryEntryDto(
    @SerialName("lead_id") val leadId: Long,
    val phone: String,
    val name: String? = null,
    val company: String? = null,
    val stage: String = "new",
    val status: String = "",
    val owner: String? = null,
)

@Serializable
data class LookupResponse(
    val found: Boolean,
    @SerialName("full_access") val fullAccess: Boolean = false,
    val phone: String? = null,
    val lead: LeadCardDto? = null,
)

@Serializable
data class LeadCardDto(
    val id: Long,
    val name: String? = null,
    val company: String? = null,
    val status: LeadStatusDto = LeadStatusDto(),
    @SerialName("assigned_to") val assignedTo: PersonDto? = null,
    val phone: String? = null,
    @SerialName("secondary_phone") val secondaryPhone: String? = null,
    val wilaya: String? = null,
    @SerialName("business_type") val businessType: String? = null,
    @SerialName("last_call") val lastCall: LastCallDto? = null,
    @SerialName("next_reminder") val nextReminder: String? = null,
    val subscription: SubscriptionDto? = null,
    @SerialName("open_requests_count") val openRequestsCount: Int = 0,
    @SerialName("open_requests") val openRequests: List<RequestDto> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("follow_up_if_unreachable") val followUp: FollowUpDto? = null,
    @SerialName("crm_url") val crmUrl: String? = null,
    @SerialName("whatsapp_url") val whatsappUrl: String? = null,
)

@Serializable
data class LeadStatusDto(val id: Long? = null, val label: String = "جديد", val stage: String = "new")

@Serializable
data class PersonDto(val id: Long, val name: String)

@Serializable
data class LastCallDto(val at: String, val result: String, val by: String? = null, val note: String? = null)

@Serializable
data class SubscriptionDto(
    val type: String,
    @SerialName("type_label") val typeLabel: String? = null,
    val required: Double = 0.0,
    val paid: Double = 0.0,
    val remaining: Double = 0.0,
    @SerialName("payment_status") val paymentStatus: String? = null,
    @SerialName("payment_status_label") val paymentStatusLabel: String? = null,
    @SerialName("should_pay_on") val shouldPayOn: String? = null,
)

@Serializable
data class RequestDto(
    val id: Long,
    val title: String,
    val priority: String? = null,
    val status: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class FollowUpDto(
    val action: String,
    val date: String? = null,
    @SerialName("archive_status_id") val archiveStatusId: Long? = null,
)

@Serializable
data class AppVersionDto(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    @SerialName("min_version_code") val minVersionCode: Int = 1,
    @SerialName("apk_url") val apkUrl: String? = null,
    val sha256: String? = null,
)

@Serializable
data class ErrorDto(val message: String? = null)

// --- Phase 2: writes ---------------------------------------------------------------------

@Serializable
data class LogCallResponse(@SerialName("call_id") val callId: Long, val lead: LeadCardDto? = null)

@Serializable
data class CreateLeadRequest(
    val phone: String,
    val name: String? = null,
    val company: String? = null,
    val notes: String,
)

@Serializable
data class CreateLeadResponse(@SerialName("lead_id") val leadId: Long? = null, val message: String? = null)

@Serializable
data class CreateRequestRequest(val title: String, val content: String, val priority: String)

@Serializable
data class CreateRequestResponse(@SerialName("request_id") val requestId: Long)
