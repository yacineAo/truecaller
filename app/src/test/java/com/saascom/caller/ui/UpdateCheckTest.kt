package com.saascom.caller.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    private val hour = 3_600_000L
    private val now = 100 * 24 * hour

    @Test
    fun checksOnlyOnceADay() {
        assertFalse(shouldCheckForUpdate(now, lastCheck = now - 2 * hour, force = false, currentVersion = 2, knownMinVersion = 1))
        assertTrue(shouldCheckForUpdate(now, lastCheck = now - 25 * hour, force = false, currentVersion = 2, knownMinVersion = 1))
        assertTrue(shouldCheckForUpdate(now, lastCheck = 0, force = false, currentVersion = 2, knownMinVersion = 0))
    }

    @Test
    fun theUpdateButtonAlwaysChecks() {
        assertTrue(shouldCheckForUpdate(now, lastCheck = now, force = true, currentVersion = 2, knownMinVersion = 1))
    }

    @Test
    fun aRequiredUpdateCannotBeDodgedByRestartingTheApp() {
        // Checked a minute ago, but this build is below the CRM's minimum: ask again so the blocking dialog returns.
        assertTrue(shouldCheckForUpdate(now, lastCheck = now - 60_000, force = false, currentVersion = 1, knownMinVersion = 2))
        assertFalse(shouldCheckForUpdate(now, lastCheck = now - 60_000, force = false, currentVersion = 2, knownMinVersion = 2))
    }
}
