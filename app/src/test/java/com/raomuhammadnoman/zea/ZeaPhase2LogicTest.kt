package com.raomuhammadnoman.zea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 (P1) hardening - pure policy tests.
 *
 * Covers the Android-free logic of the seven Phase 2 items:
 *  2.1 PIN brute-force lockout tiers
 *  2.4 Auto-lock option mapping + foreground-return policy
 *  2.5 Health report aggregation
 *  2.6 System Check report aggregation
 *  2.7 Recovery action metadata
 *  2.3 Developer-controls compile-time gate
 */
class ZeaPhase2LogicTest {

    // ---- 2.1 PIN brute-force protection ----

    @Test
    fun lockout_firstFourFailuresAreFree() {
        (0..4).forEach { failures ->
            assertEquals(
                "failure #$failures must not lock out",
                0L,
                ZeaPinLockout.cooldownMillisForFailures(failures)
            )
        }
    }

    @Test
    fun lockout_fifthFailureTriggersThirtySeconds() {
        assertEquals(
            30_000L,
            ZeaPinLockout.cooldownMillisForFailures(5)
        )
    }

    @Test
    fun lockout_tiersEscalateAtTenAndFifteen() {
        assertEquals(30_000L, ZeaPinLockout.cooldownMillisForFailures(9))
        assertEquals(120_000L, ZeaPinLockout.cooldownMillisForFailures(10))
        assertEquals(120_000L, ZeaPinLockout.cooldownMillisForFailures(14))
        assertEquals(300_000L, ZeaPinLockout.cooldownMillisForFailures(15))
        assertEquals(300_000L, ZeaPinLockout.cooldownMillisForFailures(100))
    }

    @Test
    fun lockout_tierConstantsMatchDocumentedPolicy() {
        assertEquals(5, ZeaPinLockout.FIRST_TIER_FAILURES)
        assertEquals(10, ZeaPinLockout.SECOND_TIER_FAILURES)
        assertEquals(15, ZeaPinLockout.THIRD_TIER_FAILURES)
        assertEquals(30_000L, ZeaPinLockout.FIRST_TIER_COOLDOWN_MILLIS)
        assertEquals(120_000L, ZeaPinLockout.SECOND_TIER_COOLDOWN_MILLIS)
        assertEquals(300_000L, ZeaPinLockout.THIRD_TIER_COOLDOWN_MILLIS)
    }

    // ---- 2.4 Auto-lock ----

    @Test
    fun autoLock_exactlySixOptionsExist() {
        assertEquals(6, ZeaAutoLockOption.entries.size)
    }

    @Test
    fun autoLock_storageKeysRoundTrip() {
        ZeaAutoLockOption.entries.forEach { option ->
            assertEquals(option, ZeaAutoLockOption.fromStorageKey(option.storageKey))
        }
    }

    @Test
    fun autoLock_unknownKeyFallsBackToDefault() {
        assertEquals(ZeaAutoLockOption.DEFAULT, ZeaAutoLockOption.fromStorageKey("bogus"))
        assertEquals(ZeaAutoLockOption.DEFAULT, ZeaAutoLockOption.fromStorageKey(null))
        assertEquals(ZeaAutoLockOption.LEAVES_FOREGROUND, ZeaAutoLockOption.DEFAULT)
    }

    @Test
    fun autoLock_timeoutsMatchLabels() {
        assertEquals(30_000L, zeaAutoLockTimeoutMillis(ZeaAutoLockOption.AFTER_30_SECONDS))
        assertEquals(60_000L, zeaAutoLockTimeoutMillis(ZeaAutoLockOption.AFTER_1_MINUTE))
        assertEquals(300_000L, zeaAutoLockTimeoutMillis(ZeaAutoLockOption.AFTER_5_MINUTES))
        assertNull(zeaAutoLockTimeoutMillis(ZeaAutoLockOption.IMMEDIATELY))
        assertNull(zeaAutoLockTimeoutMillis(ZeaAutoLockOption.SCREEN_OFF))
        assertNull(zeaAutoLockTimeoutMillis(ZeaAutoLockOption.LEAVES_FOREGROUND))
    }

    @Test
    fun autoLock_armedEventAlwaysRelocks() {
        ZeaAutoLockOption.entries.forEach { option ->
            assertTrue(
                "$option must relock when armed",
                zeaAutoLockShouldRelock(
                    option = option,
                    armed = true,
                    backgroundedAtEpochMillis = 1_000L,
                    nowEpochMillis = 2_000L
                )
            )
        }
    }

    @Test
    fun autoLock_timedOptionsRespectWindow() {
        val backgroundedAt = 100_000L
        assertFalse(
            zeaAutoLockShouldRelock(
                ZeaAutoLockOption.AFTER_30_SECONDS,
                armed = false,
                backgroundedAtEpochMillis = backgroundedAt,
                nowEpochMillis = backgroundedAt + 29_999L
            )
        )
        assertTrue(
            zeaAutoLockShouldRelock(
                ZeaAutoLockOption.AFTER_30_SECONDS,
                armed = false,
                backgroundedAtEpochMillis = backgroundedAt,
                nowEpochMillis = backgroundedAt + 30_000L
            )
        )
    }

    @Test
    fun autoLock_eventDrivenOptionsIgnoreElapsedTime() {
        assertFalse(
            zeaAutoLockShouldRelock(
                ZeaAutoLockOption.LEAVES_FOREGROUND,
                armed = false,
                backgroundedAtEpochMillis = 1_000L,
                nowEpochMillis = 9_999_999L
            )
        )
    }

    @Test
    fun autoLock_missingBackgroundTimestampNeverRelocks() {
        assertFalse(
            zeaAutoLockShouldRelock(
                ZeaAutoLockOption.AFTER_1_MINUTE,
                armed = false,
                backgroundedAtEpochMillis = 0L,
                nowEpochMillis = Long.MAX_VALUE
            )
        )
    }

    // ---- 2.5 Protection Health aggregation ----

    @Test
    fun healthReport_healthyWhenNoWarnings() {
        val report = ZeaProtectionHealthReport(
            signals = listOf(
                ZeaHealthSignal("a", "A", ZeaHealthStatus.HEALTHY, "ok"),
                ZeaHealthSignal("b", "B", ZeaHealthStatus.NOT_APPLICABLE, "n/a")
            ),
            protectedCount = 3,
            timedCount = 1,
            deviceOwnerMode = true,
            protectionPaused = false
        )
        assertTrue(report.healthy)
        assertEquals(0, report.issueCount)
        assertNull(report.firstIssue)
    }

    @Test
    fun healthReport_countsWarningsAndExposesFirstIssue() {
        val report = ZeaProtectionHealthReport(
            signals = listOf(
                ZeaHealthSignal("a", "A", ZeaHealthStatus.HEALTHY, "ok"),
                ZeaHealthSignal("b", "B", ZeaHealthStatus.WARNING, "bad1"),
                ZeaHealthSignal("c", "C", ZeaHealthStatus.WARNING, "bad2")
            ),
            protectedCount = 0,
            timedCount = 0,
            deviceOwnerMode = false,
            protectionPaused = false
        )
        assertFalse(report.healthy)
        assertEquals(2, report.issueCount)
        assertEquals("b", report.firstIssue?.id)
    }

    // ---- 2.6 System Check aggregation ----

    @Test
    fun systemCheckReport_countsStatuses() {
        val report = ZeaSystemCheckReport(
            results = listOf(
                ZeaSystemCheckResult("1", "one", ZeaCheckStatus.PASS, "ok"),
                ZeaSystemCheckResult("2", "two", ZeaCheckStatus.PASS, "ok"),
                ZeaSystemCheckResult(
                    "3", "three", ZeaCheckStatus.FAIL, "bad",
                    ZeaRepairAction.REPAIR_CACHE
                ),
                ZeaSystemCheckResult("4", "four", ZeaCheckStatus.NOT_APPLICABLE, "n/a")
            )
        )
        assertEquals(2, report.passedCount)
        assertEquals(1, report.failedCount)
        assertEquals(3, report.checkedCount)
    }

    // ---- 2.7 Emergency Recovery metadata ----

    @Test
    fun recovery_exactlyNineActionsExist() {
        assertEquals(9, ZeaRecoveryAction.entries.size)
    }

    @Test
    fun recovery_destructiveFlagsMatchRoadmap() {
        val destructive = ZeaRecoveryAction.entries
            .filter { action -> action.destructive }
            .toSet()
        assertEquals(
            setOf(
                ZeaRecoveryAction.UNHIDE_ALL_APPS,
                ZeaRecoveryAction.CANCEL_ALL_TIMERS,
                ZeaRecoveryAction.REPAIR_REGISTRY,
                ZeaRecoveryAction.CLEAR_PENDING_REHIDE,
                ZeaRecoveryAction.PAUSE_PROTECTION
            ),
            destructive
        )
        assertFalse(ZeaRecoveryAction.RESUME_PROTECTION.destructive)
        assertFalse(ZeaRecoveryAction.RERUN_SYSTEM_CHECK.destructive)
        assertFalse(ZeaRecoveryAction.RECONCILE_HIDDEN_STATE.destructive)
        assertFalse(ZeaRecoveryAction.RESTORE_LAUNCHER_VISIBILITY.destructive)
    }

    // ---- 2.3 Developer controls gate ----

    @Test
    fun developerControls_gateReflectsBuildVariant() {
        // Unit tests run against the debug source set, where the developer
        // surface stays available. The release source set compiles a stub
        // whose gate is constant false, so production APKs contain no
        // developer key or UI at all.
        assertTrue(zeaDeveloperControlsEnabled)
    }
}
