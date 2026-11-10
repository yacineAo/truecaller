package com.saascom.caller.util

/**
 * CRM vocabulary shown to the user. Labels match the CRM constants
 * (CallLog::STATUSES, LeadStatus::STAGES, UserNote::PRIORITIES) so both sides use the same words.
 */
object Labels {
    const val STAGE_NEW = "new"

    private val callResults = mapOf(
        "available" to "متوفر",
        "no_answer" to "لا يجيب",
        "busy" to "مشغول",
        "closed" to "مغلق",
    )

    private val stages = mapOf(
        "new" to "جديد",
        "follow_up" to "قيد المتابعة",
        "interested" to "مهتم",
        "in_process" to "قيد الإجراء",
        "won" to "مشترك",
        "closed" to "مغلق",
    )

    private val priorities = mapOf(
        "normal" to "عادية",
        "high" to "مهمة",
        "urgent" to "عاجلة",
    )

    /** ARGB colors per stage (suggested in api-contract.md). */
    private val stageColors = mapOf(
        "new" to 0xFFF59E0B,
        "follow_up" to 0xFF6B7280,
        "interested" to 0xFF3B82F6,
        "in_process" to 0xFF6366F1,
        "won" to 0xFF10B981,
        "closed" to 0xFFEF4444,
    )

    fun callResult(value: String?): String = callResults[value] ?: value.orEmpty()

    fun stage(value: String?): String = stages[value] ?: value.orEmpty()

    fun priority(value: String?): String = priorities[value] ?: value.orEmpty()

    fun stageColor(value: String?): Long = stageColors[value] ?: 0xFF6B7280
}
