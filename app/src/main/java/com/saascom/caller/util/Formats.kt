package com.saascom.caller.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Dates and money as the CRM shows them: Algiers time, Arabic wording, "10 000 DA". */
object Formats {
    val ALGIERS: ZoneId = ZoneId.of("Africa/Algiers")

    /** Unicode "first strong isolate" marks: keep numbers left-to-right inside Arabic text. */
    fun ltr(text: String): String = "⁨$text⁩"

    fun parseInstant(iso: String?): Instant? =
        try {
            iso?.let(Instant::parse)
        } catch (e: DateTimeParseException) {
            null
        }

    /** "منذ 3 أيام", "أمس", "منذ ساعتين"… for a past date. */
    fun relative(iso: String?, now: Instant = Instant.now()): String {
        val then = parseInstant(iso) ?: return ""
        val elapsed = Duration.between(then, now)
        if (elapsed.isNegative) return dateTime(iso)
        val minutes = elapsed.toMinutes()
        val hours = elapsed.toHours()
        val days = elapsed.toDays()
        return when {
            minutes < 2 -> "الآن"
            minutes < 60 -> "منذ ${count(minutes, "دقيقة", "دقيقتين", "دقائق", "دقيقة")}"
            hours < 24 -> "منذ ${count(hours, "ساعة", "ساعتين", "ساعات", "ساعة")}"
            days == 1L -> "أمس"
            else -> "منذ ${count(days, "يوم", "يومين", "أيام", "يوماً")}"
        }
    }

    /** Arabic counting: 1 -> singular, 2 -> dual, 3..10 -> plural, 11+ -> accusative singular. */
    private fun count(n: Long, one: String, two: String, few: String, many: String): String = when (n) {
        1L -> one
        2L -> two
        in 3L..10L -> "$n $few"
        else -> "$n $many"
    }

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ALGIERS)

    /** "2026/11/15 09:00" in Algiers time. */
    fun dateTime(iso: String?): String = parseInstant(iso)?.let { ltr(dateTimeFormat.format(it)) }.orEmpty()

    /**
     * 0550123456 -> "0550 12 34 56", isolated left-to-right: inside Arabic text Android would
     * otherwise show the digit groups in reverse order ("56 34 12 0550").
     */
    fun phone(phone: String): String = ltr(PhoneNormalizer.pretty(phone))

    /** 10000.0 -> "10 000 DA" (isolated so RTL text does not flip it). */
    fun money(amount: Double): String {
        val whole = Math.round(amount)
        val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
        return ltr("$grouped DA")
    }
}
