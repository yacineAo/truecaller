package com.saascom.caller.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FormatsTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")

    private fun ago(seconds: Long) = now.minusSeconds(seconds).toString()

    @Test
    fun relativeUsesArabicCounting() {
        assertEquals("الآن", Formats.relative(ago(30), now))
        assertEquals("منذ 5 دقائق", Formats.relative(ago(5 * 60), now))
        assertEquals("منذ 45 دقيقة", Formats.relative(ago(45 * 60), now))
        assertEquals("منذ ساعة", Formats.relative(ago(3600), now))
        assertEquals("منذ ساعتين", Formats.relative(ago(2 * 3600), now))
        assertEquals("منذ 3 ساعات", Formats.relative(ago(3 * 3600), now))
        assertEquals("أمس", Formats.relative(ago(30 * 3600), now))
        assertEquals("منذ يومين", Formats.relative(ago(2 * 86400), now))
        assertEquals("منذ 3 أيام", Formats.relative(ago(3 * 86400), now))
        assertEquals("منذ 14 يوماً", Formats.relative(ago(14 * 86400), now))
    }

    @Test
    fun relativeOfGarbageIsEmpty() {
        assertEquals("", Formats.relative(null, now))
        assertEquals("", Formats.relative("yesterday", now))
    }

    @Test
    fun dateTimeIsInAlgiersTime() {
        // 08:00 UTC is 09:00 in Algiers (UTC+1, no daylight saving).
        assertEquals(Formats.ltr("2026/11/15 09:00"), Formats.dateTime("2026-11-15T08:00:00Z"))
    }

    @Test
    fun phoneIsGroupedAndIsolated() {
        assertEquals("\u20680550 12 34 56\u2069", Formats.phone("0550123456"))
    }

    @Test
    fun moneyIsGroupedAndIsolated() {
        assertEquals(Formats.ltr("10 000 DA"), Formats.money(10000.0))
        assertEquals(Formats.ltr("1 250 000 DA"), Formats.money(1250000.0))
        assertEquals(Formats.ltr("500 DA"), Formats.money(499.6))
    }
}
