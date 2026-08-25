package com.raomuhammadnoman.zea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeaPhase1InvariantsTest {
    @Test
    fun countInvariant_acceptsCleanVisibleOnlyState() {
        val issues = zeaPhase1CountIssues(
            ZeaPhase1CountSnapshot(visible = 204, hidden = 0, timed = 0, registryProtected = 0, timerRecords = 0)
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun countInvariant_acceptsMixedProtectedState() {
        val issues = zeaPhase1CountIssues(
            ZeaPhase1CountSnapshot(visible = 54, hidden = 146, timed = 4, registryProtected = 150, timerRecords = 4)
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun countInvariant_detectsRegistryDrift() {
        val issues = zeaPhase1CountIssues(
            ZeaPhase1CountSnapshot(visible = 194, hidden = 10, timed = 0, registryProtected = 9, timerRecords = 0)
        )
        assertTrue(issues.any { it.contains("registry count=9") })
    }

    @Test
    fun countInvariant_countsTimedAsProtected() {
        val issues = zeaPhase1CountIssues(
            ZeaPhase1CountSnapshot(visible = 199, hidden = 0, timed = 5, registryProtected = 5, timerRecords = 5)
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun countInvariant_detectsTimerDrift() {
        val issues = zeaPhase1CountIssues(
            ZeaPhase1CountSnapshot(visible = 199, hidden = 3, timed = 2, registryProtected = 5, timerRecords = 1)
        )
        assertTrue(issues.any { it.contains("timer count=1") })
    }

    @Test
    fun countInvariant_rejectsImpossibleNegativeValues() {
        val issues = zeaPhase1CountIssues(
            ZeaPhase1CountSnapshot(visible = -1, hidden = 0, timed = 0, registryProtected = 0, timerRecords = 0)
        )
        assertEquals(listOf("count values must never be negative"), issues)
    }

    @Test
    fun countInvariant_detectsMoreTimersThanProtectedApps() {
        val issues = zeaPhase1CountIssues(
            ZeaPhase1CountSnapshot(visible = 10, hidden = 0, timed = 2, registryProtected = 2, timerRecords = 3)
        )
        assertTrue(issues.any { it.contains("exceeds protected registry") })
    }

    @Test
    fun timedRequest_rejectsZero() {
        assertNull(zeaTimedHideRequest("0", ZeaTimeUnit.MINUTES, 1_000L))
    }

    @Test
    fun timedRequest_rejectsNegative() {
        assertNull(zeaTimedHideRequest("-1", ZeaTimeUnit.HOURS, 1_000L))
    }

    @Test
    fun timedRequest_rejectsNonnumericInput() {
        assertNull(zeaTimedHideRequest("abc", ZeaTimeUnit.SECONDS, 1_000L))
    }

    @Test
    fun timedRequest_buildsFutureDeadline() {
        val request = zeaTimedHideRequest("90", ZeaTimeUnit.SECONDS, 10_000L)
        assertNotNull(request)
        assertEquals("90 seconds", request!!.label)
        assertEquals(100_000L, request.endEpochMillis)
    }

    @Test
    fun durationLabel_isSingularForOne() {
        assertEquals("1 hour", zeaDurationLabel(1, ZeaTimeUnit.HOURS))
    }

    @Test
    fun durationLabel_isPluralForMany() {
        assertEquals("2 days", zeaDurationLabel(2, ZeaTimeUnit.DAYS))
    }
}
