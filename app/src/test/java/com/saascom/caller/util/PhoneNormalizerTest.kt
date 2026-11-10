package com.saascom.caller.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared table from phases/api-contract.md: the CRM's PhoneTest checks the same rows. */
class PhoneNormalizerTest {

    private val contractTable = listOf(
        "0555123456" to "0555123456",
        "+213555123456" to "0555123456",
        "213555123456" to "0555123456",
        "00213555123456" to "0555123456",
        "+213 555-12.34(56)" to "0555123456",
        "p:+213555123456" to "0555123456",
        "0033612345678" to "+33612345678",
        "+33612345678" to "+33612345678",
        "+21321234567" to "021234567",
        "0021321234567" to "021234567",
        "021234567" to "021234567",
        "021 23 45 67" to "021234567",
        "21321234567" to "21321234567",
        "abc" to null,
        "12345" to null,
        "" to null,
    )

    @Test
    fun matchesTheContractTable() {
        for ((input, expected) in contractTable) {
            assertEquals("normalize(\"$input\")", expected, PhoneNormalizer.normalize(input))
        }
    }

    @Test
    fun hiddenNumberIsNull() {
        assertEquals(null, PhoneNormalizer.normalize(null))
        assertEquals(null, PhoneNormalizer.normalize("   "))
    }

    @Test
    fun prettyGroupsAlgerianMobiles() {
        assertEquals("0550 12 34 56", PhoneNormalizer.pretty("0550123456"))
        assertEquals("+33612345678", PhoneNormalizer.pretty("+33612345678"))
        assertEquals("021234567", PhoneNormalizer.pretty("021234567"))
    }
}
