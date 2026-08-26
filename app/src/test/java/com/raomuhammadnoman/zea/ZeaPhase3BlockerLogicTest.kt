package com.raomuhammadnoman.zea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Phase 3 FINAL blocker pass — deterministic tests for the remaining
 * implementation/state-management blockers:
 *
 *  - Profile ownership transitions (timed precedence, hidden<->timed restore,
 *    independent-change release)
 *  - Schedule END validation (early/stale/valid END, next-cycle transition)
 *  - Overlapping-schedule ownership transfer (earliest claim wins)
 *  - Safe Undo exact applied-state audit (re-time, permanent->timed)
 *  - Authenticated vs unauthenticated search privacy gate
 *  - AND-style filter combinations
 */
class ZeaPhase3BlockerLogicTest {

    private fun atLocal(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: TimeZone = TimeZone.getDefault()
    ): Long = Calendar.getInstance(zone).apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun schedule(
        kind: ZeaScheduleKind,
        startMinute: Int,
        endMinute: Int,
        days: List<Int> = emptyList(),
        oneTimeStart: Long = 0L,
        enabled: Boolean = true
    ) = ZeaSchedule(
        id = "s",
        name = "s",
        kind = kind,
        daysOfWeek = days,
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
        targetGroupId = null,
        targetPackages = listOf("com.example.a"),
        oneTimeStartEpochMillis = oneTimeStart,
        enabled = enabled,
        createdAtEpochMillis = 0L
    )

    private val now = atLocal(2026, Calendar.AUGUST, 26, 12, 0)

    // ---- Profile ownership: deactivation plan ----

    @Test
    fun profileDeactivate_hiddenToProfileTimed_restoresPermanentHidden() {
        // 1B: permanently hidden before activation, profile applied TIMED.
        // Deactivation must restore the permanent hide, never leave the
        // profile timer alive to expose the app later.
        val snapshot = ZeaProfileOwnershipSnapshot(
            previousMode = ZeaHideMode.HIDDEN,
            previousTimedEndEpochMillis = 0L,
            appliedMode = ZeaHideMode.TIMED,
            appliedTimedEndEpochMillis = now + 3_600_000L
        )
        assertEquals(
            ZeaProfileEndAction.RESTORE_HIDDEN,
            zeaProfileDeactivatePlan(
                snapshot,
                ZeaHideMode.TIMED,
                snapshot.appliedTimedEndEpochMillis,
                now
            )
        )
    }

    @Test
    fun profileDeactivate_timedToProfileHidden_restoresOriginalDeadline() {
        // 1C: timed before activation, profile applied permanent HIDDEN.
        // Deactivation restores the original timer when its deadline is still
        // in the future.
        val originalEnd = now + 7_200_000L
        val snapshot = ZeaProfileOwnershipSnapshot(
            previousMode = ZeaHideMode.TIMED,
            previousTimedEndEpochMillis = originalEnd,
            appliedMode = ZeaHideMode.HIDDEN,
            appliedTimedEndEpochMillis = 0L
        )
        assertEquals(
            ZeaProfileEndAction.RESTORE_TIMED,
            zeaProfileDeactivatePlan(snapshot, ZeaHideMode.HIDDEN, 0L, now)
        )
    }

    @Test
    fun profileDeactivate_timedToProfileHidden_expiredDeadlineRestoresVisible() {
        // 1C edge: the original timer would already have expired — leaving the
        // app hidden past its own deadline is dishonest, restore visible.
        val snapshot = ZeaProfileOwnershipSnapshot(
            previousMode = ZeaHideMode.TIMED,
            previousTimedEndEpochMillis = now - 60_000L,
            appliedMode = ZeaHideMode.HIDDEN,
            appliedTimedEndEpochMillis = 0L
        )
        assertEquals(
            ZeaProfileEndAction.RESTORE_VISIBLE,
            zeaProfileDeactivatePlan(snapshot, ZeaHideMode.HIDDEN, 0L, now)
        )
    }

    @Test
    fun profileDeactivate_visibleMember_restoresVisible() {
        val snapshot = ZeaProfileOwnershipSnapshot(
            previousMode = ZeaHideMode.VISIBLE,
            previousTimedEndEpochMillis = 0L,
            appliedMode = ZeaHideMode.HIDDEN,
            appliedTimedEndEpochMillis = 0L
        )
        assertEquals(
            ZeaProfileEndAction.RESTORE_VISIBLE,
            zeaProfileDeactivatePlan(snapshot, ZeaHideMode.HIDDEN, 0L, now)
        )
    }

    @Test
    fun profileDeactivate_independentChange_releasesWithoutTouching() {
        // The user re-timed the app after the profile applied its timer: the
        // reversal would destroy manual state, so it must be refused.
        val snapshot = ZeaProfileOwnershipSnapshot(
            previousMode = ZeaHideMode.VISIBLE,
            previousTimedEndEpochMillis = 0L,
            appliedMode = ZeaHideMode.TIMED,
            appliedTimedEndEpochMillis = now + 3_600_000L
        )
        assertEquals(
            ZeaProfileEndAction.SKIP_INDEPENDENT,
            zeaProfileDeactivatePlan(
                snapshot,
                ZeaHideMode.TIMED,
                now + 9_999_000L, // different deadline: not the applied state
                now
            )
        )
    }

    // ---- Schedule END validation (section 3) ----

    @Test
    fun endValidation_earlyEnd_keepsProtectionAndRearmsTrueEnd() {
        // Window 09:00-17:00, END broadcast arrives at 12:00 (early).
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        val plan = zeaScheduleFirePlan(s, "end", now)
        assertEquals(ZeaScheduleAction.SKIP, plan.action)
        assertEquals(atLocal(2026, Calendar.AUGUST, 26, 17, 0), plan.endEpochMillis)
    }

    @Test
    fun endValidation_staleEndAfterWindowEnded_releases() {
        // END broadcast arrives at 18:00 for a window that ended at 17:00.
        val staleNow = atLocal(2026, Calendar.AUGUST, 26, 18, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        val plan = zeaScheduleFirePlan(s, "end", staleNow)
        assertEquals(ZeaScheduleAction.UNHIDE, plan.action)
    }

    @Test
    fun endValidation_validEndExactlyAtBoundary_releases() {
        val exactEnd = atLocal(2026, Calendar.AUGUST, 26, 17, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        val plan = zeaScheduleFirePlan(s, "end", exactEnd)
        assertEquals(ZeaScheduleAction.UNHIDE, plan.action)
    }

    @Test
    fun endValidation_recurringNextCycle_startFiresAgainAfterEnd() {
        // After today's window ended, tomorrow's START is still a valid HIDE.
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        val tomorrowStart = atLocal(2026, Calendar.AUGUST, 27, 9, 0)
        val plan = zeaScheduleFirePlan(s, "start", tomorrowStart)
        assertEquals(ZeaScheduleAction.HIDE, plan.action)
        assertEquals(atLocal(2026, Calendar.AUGUST, 27, 17, 0), plan.endEpochMillis)
    }

    // ---- Overlapping schedules: ownership transfer (2C) ----

    @Test
    fun overlapTransfer_earliestClaimCarriesOriginalState() {
        val scheduleAClaim = ZeaScheduleOwnershipRecord(
            previousMode = ZeaHideMode.VISIBLE,
            previousTimedEndEpochMillis = 0L,
            claimedAtEpochMillis = 1_000L
        )
        val scheduleBClaim = ZeaScheduleOwnershipRecord(
            previousMode = ZeaHideMode.HIDDEN, // saw the app after A hid it
            previousTimedEndEpochMillis = 0L,
            claimedAtEpochMillis = 2_000L
        )
        val merged = zeaScheduleMergedOwnership(scheduleAClaim, scheduleBClaim)
        assertEquals(ZeaHideMode.VISIBLE, merged?.previousMode)
    }

    @Test
    fun overlapTransfer_missingSurvivingRecord_inheritsEndingClaim() {
        val ending = ZeaScheduleOwnershipRecord(ZeaHideMode.TIMED, 5_000L, 1_000L)
        assertEquals(ending, zeaScheduleMergedOwnership(ending, null))
        assertNull(zeaScheduleMergedOwnership(null, null))
    }

    // ---- Safe Undo exact applied-state audit (section 9) ----

    @Test
    fun undo_retimedTimer_comparesAgainstNewlyAppliedDeadline() {
        // 9A: existing timer 14:00 re-timed to 18:00. canUndo must match the
        // NEW end; undo restores the OLD end.
        val oldEnd = now + 2 * 3_600_000L
        val newEnd = now + 6 * 3_600_000L
        val entry = ZeaUndoEntry(
            operation = UndoOperation.TIMED_HIDE,
            packageName = "com.example.a",
            displayName = "A",
            previousMode = ZeaHideMode.TIMED,
            timedEndEpochMillis = oldEnd,
            epochMillis = now,
            appliedTimedEndEpochMillis = newEnd
        )
        assertTrue(zeaUndoIsSafe(entry, ZeaHideMode.TIMED, newEnd, now))
        assertFalse(zeaUndoIsSafe(entry, ZeaHideMode.TIMED, oldEnd, now))
        assertEquals(ZeaUndoPlan.REARM_TIMER, zeaUndoReversalPlan(entry, now))
    }

    @Test
    fun undo_permanentHiddenToTimed_restoresPermanentHidden() {
        // 9B: permanently hidden app gets a timer; undo must convert back to
        // the permanent hide, not treat the previous state as timed.
        val entry = ZeaUndoEntry(
            operation = UndoOperation.TIMED_HIDE,
            packageName = "com.example.a",
            displayName = "A",
            previousMode = ZeaHideMode.HIDDEN,
            timedEndEpochMillis = 0L,
            epochMillis = now,
            appliedTimedEndEpochMillis = now + 3_600_000L
        )
        assertEquals(ZeaUndoPlan.CONVERT_TO_PERMANENT, zeaUndoReversalPlan(entry, now))
    }

    @Test
    fun undo_independentChangeAfterOperation_refusesReversal() {
        // 9C: a later subsystem changed the mode; the older undo is unsafe.
        val entry = ZeaUndoEntry(
            operation = UndoOperation.HIDE,
            packageName = "com.example.a",
            displayName = "A",
            previousMode = ZeaHideMode.VISIBLE,
            timedEndEpochMillis = 0L,
            epochMillis = now
        )
        // App was converted to TIMED after the hide: not the applied state.
        assertFalse(zeaUndoIsSafe(entry, ZeaHideMode.TIMED, now + 60_000L, now))
        // Outside the 5-minute window: expired.
        assertFalse(
            zeaUndoIsSafe(
                entry,
                ZeaHideMode.HIDDEN,
                0L,
                now + ZeaUndo.WINDOW_MILLIS + 1_000L
            )
        )
    }

    // ---- Search privacy gate (section 10) ----

    @Test
    fun searchGate_lockDisabled_revealsEverything() {
        assertTrue(zeaSearchMayRevealSensitive(lockEnabled = false, sessionAuthenticated = false))
        assertTrue(zeaSearchMayRevealSensitive(lockEnabled = false, sessionAuthenticated = true))
    }

    @Test
    fun searchGate_lockEnabled_requiresAuthentication() {
        assertFalse(zeaSearchMayRevealSensitive(lockEnabled = true, sessionAuthenticated = false))
        assertTrue(zeaSearchMayRevealSensitive(lockEnabled = true, sessionAuthenticated = true))
    }

    // ---- Filter combinations (section 12) ----

    private fun app(
        pkg: String,
        name: String,
        system: Boolean = false,
        manageable: Boolean = true,
        hideMode: ZeaHideMode = ZeaHideMode.VISIBLE,
        installedAt: Long = 0L
    ) = ZeaManagedApp(
        displayName = name,
        packageName = pkg,
        launcherActivityName = "$pkg.Main",
        systemApp = system,
        hideMode = hideMode,
        manageable = manageable,
        firstInstallTimeEpochMillis = installedAt
    )

    @Test
    fun filters_userPlusProtectedPlusRecentlyManaged_andCombined() {
        val recentlyManaged = listOf(
            ZeaRecentlyManagedEntry("com.example.b", "B", "Hide", now - 1_000L)
        )
        val apps = listOf(
            app("com.example.a", "Alpha", manageable = false),                 // protected, not managed
            app("com.example.b", "Beta", manageable = false),                  // protected + managed
            app("com.example.c", "Gamma", manageable = true),                  // unprotected
            app("com.example.d", "Delta", system = true, manageable = false)   // protected but system
        )
        val result = filterAndSortApps(
            apps = apps,
            query = "",
            sort = ZeaAppsSort.NAME,
            filter = ZeaAppsFilter.ALL,
            attributeFilter = ZeaAppsFilter.PROTECTED,
            recentlyManaged = recentlyManaged,
            nowEpochMillis = now
        )
        // PROTECTED attribute: both non-manageable apps survive (AND with ALL).
        assertEquals(listOf("com.example.a", "com.example.b", "com.example.d"), result.map { it.packageName })

        val protectedUser = filterAndSortApps(
            apps = apps,
            query = "",
            sort = ZeaAppsSort.NAME,
            filter = ZeaAppsFilter.ALL,
            attributeFilter = ZeaAppsFilter.PROTECTED,
            recentlyManaged = emptyList(),
            nowEpochMillis = now
        ).filter { !it.systemApp }
        assertEquals(listOf("com.example.a", "com.example.b"), protectedUser.map { it.packageName })
    }

    @Test
    fun filters_statusAndAttributeAreAndCombined() {
        val apps = listOf(
            app("com.example.a", "Alpha", hideMode = ZeaHideMode.HIDDEN),
            app("com.example.b", "Beta", hideMode = ZeaHideMode.HIDDEN, system = true),
            app("com.example.c", "Gamma", hideMode = ZeaHideMode.VISIBLE)
        )
        val result = filterAndSortApps(
            apps = apps,
            query = "",
            sort = ZeaAppsSort.NAME,
            filter = ZeaAppsFilter.HIDDEN,
            attributeFilter = ZeaAppsFilter.USER_APPS,
            nowEpochMillis = now
        )
        assertEquals(listOf("com.example.a"), result.map { it.packageName })
    }
}
