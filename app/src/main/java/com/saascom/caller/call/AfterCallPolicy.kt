package com.saascom.caller.call

/** What the app offers when a call it showed a card for has ended. */
enum class AfterCallAction {
    /** Answered client call: «سجّل المكالمة مع …». */
    LogPrompt,

    /** Answered call from a number the CRM does not know: «إضافة كعميل جديد؟». */
    AddLeadPrompt,

    /** A client called and nobody answered: «اتصل بك …» with «إعادة الاتصال». */
    MissedNotice,

    Nothing,
}

/** Decision table of phases/phase-2-after-call.md 2.2. */
object AfterCallPolicy {
    /** Shorter "answered" calls are wrong numbers or dropped calls: treated as missed. */
    const val MIN_ANSWERED_SECONDS = 5

    /**
     * @param known the CRM (or the local list) knows the number
     * @param canLog the agent may log calls for this lead (full card); false for another agent's lead
     */
    fun decide(known: Boolean, canLog: Boolean, answered: Boolean, durationSeconds: Int): AfterCallAction {
        val reallyAnswered = answered && durationSeconds >= MIN_ANSWERED_SECONDS
        return when {
            reallyAnswered && known && canLog -> AfterCallAction.LogPrompt
            reallyAnswered && known -> AfterCallAction.Nothing // another agent's client: theirs to log
            reallyAnswered -> AfterCallAction.AddLeadPrompt
            known -> AfterCallAction.MissedNotice
            else -> AfterCallAction.Nothing
        }
    }
}
