package com.saascom.caller.util

/**
 * Kotlin port of the CRM's App\Support\Phone::normalize().
 *
 * Algerian numbers become 0XXXXXXXXX (mobile) or 0XXXXXXXX (landline), foreign ones +XXXXXXXX.
 * Returns null when the input is not a phone number (hidden caller, letters, too short).
 * Both sides must pass the shared table in phases/api-contract.md.
 */
object PhoneNormalizer {
    private val separators = Regex("""[\s\-.()]""")
    private val dzMobile = Regex("""^\+?213(\d{9})$""")
    private val dzLandline = Regex("""^\+213(\d{8})$""")
    private val valid = Regex("""^\+?\d{6,15}$""")

    fun normalize(raw: String?): String? {
        var phone = raw?.trim() ?: return null
        if (phone.startsWith("p:", ignoreCase = true)) phone = phone.substring(2)
        phone = phone.replace(separators, "")
        if (phone.startsWith("00")) phone = "+" + phone.substring(2)
        dzMobile.matchEntire(phone)?.let { phone = "0" + it.groupValues[1] }
        dzLandline.matchEntire(phone)?.let { phone = "0" + it.groupValues[1] }
        return if (valid.matches(phone)) phone else null
    }

    /** 0550123456 -> "0550 12 34 56" for display; other formats are returned unchanged. */
    fun pretty(phone: String): String {
        val m = Regex("""^(0\d{3})(\d{2})(\d{2})(\d{2})$""").matchEntire(phone) ?: return phone
        return m.groupValues.drop(1).joinToString(" ")
    }
}
