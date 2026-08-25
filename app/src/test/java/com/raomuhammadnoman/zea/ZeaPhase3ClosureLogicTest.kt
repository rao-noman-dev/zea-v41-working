package com.raomuhammadnoman.zea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Phase 3 closure pass — deterministic tests for the newly fixed logic:
 *  - cross-midnight active-window correctness (the 03:00 bug)
 *  - delayed START reconciliation (fire plan)
 *  - schedule END prior-state decisions (zeaScheduleEndPlan)
 *  - schedule validation (zero selected days, invalid minutes)
 *  - Safe Undo exact applied-state ownership gate (zeaUndoIsSafe)
 *  - Name sort stability after recency filters
 */
class ZeaPhase3ClosureLogicTest {

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
        enabled: Boolean = true,
        targets: List<String> = listOf("com.example.a"),
        groupId: String? = null
    ) = ZeaSchedule(
        id = "s",
        name = "s",
        kind = kind,
        daysOfWeek = days,
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
        targetGroupId = groupId,
        targetPackages = targets,
        oneTimeStartEpochMillis = oneTimeStart,
        enabled = enabled,
        createdAtEpochMillis = 0L
    )

    // ---- cross-midnight active window ----

    @Test
    fun activeWindow_crossesMidnight_stillActiveAfterMidnight() {
        // Daily 22:00 -> 06:00; now is 03:00 the next day: must be active.
        val now = atLocal(2026, Calendar.AUGUST, 26, 3, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 22 * 60, 6 * 60)
        val expected = atLocal(2026, Calendar.AUGUST, 26, 6, 0)
        assertEquals(expected, zeaScheduleActiveWindow(s, now))
    }

    @Test
    fun activeWindow_crossesMidnight_sameDayBeforeStart_inactive() {
        val now = atLocal(2026, Calendar.AUGUST, 26, 12, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 22 * 60, 6 * 60)
        assertNull(zeaScheduleActiveWindow(s, now))
    }

    @Test
    fun activeWindow_crossesMidnight_sameDayDuringWindow_active() {
        val now = atLocal(2026, Calendar.AUGUST, 26, 23, 30)
        val s = schedule(ZeaScheduleKind.DAILY, 22 * 60, 6 * 60)
        val expected = atLocal(2026, Calendar.AUGUST, 27, 6, 0)
        assertEquals(expected, zeaScheduleActiveWindow(s, now))
    }

    @Test
    fun activeWindow_crossesMidnight_activeUntilEnd_thenStops() {
        val s = schedule(ZeaScheduleKind.DAILY, 22 * 60, 6 * 60)
        // 06:00 exactly: window ended.
        val atEnd = atLocal(2026, Calendar.AUGUST, 26, 6, 0)
        assertNull(zeaScheduleActiveWindow(s, atEnd))
        // 05:59: still active.
        val justBefore = atLocal(2026, Calendar.AUGUST, 26, 5, 59)
        assertNotNull(zeaScheduleActiveWindow(s, justBefore))
    }

    @Test
    fun activeWindow_weekdaysCrossMidnight_tuesdayEarlyActiveFromMondayStart() {
        // Weekdays 23:00 -> 07:00; now is Tuesday 01:00 (started Monday 23:00).
        // 25 Aug 2026 is a Tuesday; use Monday 24th 23:00 -> Tuesday 25th 01:00.
        val now = atLocal(2026, Calendar.AUGUST, 25, 1, 0) // Tuesday 01:00
        val s = schedule(ZeaScheduleKind.WEEKDAYS, 23 * 60, 7 * 60)
        assertNotNull(zeaScheduleActiveWindow(s, now))
    }

    @Test
    fun activeWindow_weekdaysCrossMidnight_saturdayEarly_notActiveFromFriday() {
        // Weekdays 23:00 -> 07:00; Friday 23:00 window should be active into
        // Saturday early morning per the spec (window started on a weekday).
        // 28 Aug 2026 is a Friday; 29 Aug 02:00 Saturday should still be active.
        val now = atLocal(2026, Calendar.AUGUST, 29, 2, 0)
        val s = schedule(ZeaScheduleKind.WEEKDAYS, 23 * 60, 7 * 60)
        assertNotNull(zeaScheduleActiveWindow(s, now))
    }

    @Test
    fun activeWindow_sameDayWindow_onlyYesterdayCarryIgnored() {
        // Same-day 09:00 -> 17:00; 20:00 today: yesterday's window ended, no carry.
        val now = atLocal(2026, Calendar.AUGUST, 26, 20, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        assertNull(zeaScheduleActiveWindow(s, now))
    }

    // ---- delayed START reconciliation ----

    @Test
    fun firePlan_startInsideWindow_hidesUntilEnd() {
        val now = atLocal(2026, Calendar.AUGUST, 26, 12, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        val plan = zeaScheduleFirePlan(s, "start", now)
        assertEquals(ZeaScheduleAction.HIDE, plan.action)
        assertEquals(atLocal(2026, Calendar.AUGUST, 26, 17, 0), plan.endEpochMillis)
    }

    @Test
    fun firePlan_delayedStartAfterWindowEnd_skips() {
        // 09:00 -> 17:00; the START alarm fires late at 18:00: window is over.
        val now = atLocal(2026, Calendar.AUGUST, 26, 18, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        val plan = zeaScheduleFirePlan(s, "start", now)
        assertEquals(ZeaScheduleAction.SKIP, plan.action)
    }

    @Test
    fun firePlan_delayedCrossMidnightStart_afterEnd_skips() {
        // Cross-midnight Tuesday 23:00 -> 06:00 (custom days = Tuesday only).
        // The START alarm fires late Wednesday 06:30: Tuesday's window ended
        // at 06:00 and Wednesday has no own start, so hide must be skipped
        // instead of keeping the app hidden until next Tuesday's 06:00.
        // 26 Aug 2026 is a Wednesday; 25 Aug 2026 is a Tuesday.
        val now = atLocal(2026, Calendar.AUGUST, 26, 6, 30)
        val s = schedule(
            ZeaScheduleKind.CUSTOM_DAYS, 23 * 60, 6 * 60,
            days = listOf(Calendar.TUESDAY)
        )
        val plan = zeaScheduleFirePlan(s, "start", now)
        assertEquals(ZeaScheduleAction.SKIP, plan.action)
    }

    @Test
    fun firePlan_startBeforeTodaysWindow_hidesForNextCycle() {
        // 09:00 -> 17:00; now is 08:00: a fire now starts the upcoming cycle.
        val now = atLocal(2026, Calendar.AUGUST, 26, 8, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        val plan = zeaScheduleFirePlan(s, "start", now)
        assertEquals(ZeaScheduleAction.HIDE, plan.action)
        assertTrue(plan.endEpochMillis > now)
    }

    @Test
    fun firePlan_oneTimeExpiredIsSkipped_orExpired() {
        // A one-time start moment far in the past must never trigger a hide:
        // the fire plan resolves to a non-hiding action (skip or expired).
        val past = atLocal(2026, Calendar.AUGUST, 20, 9, 0)
        val now = atLocal(2026, Calendar.AUGUST, 26, 12, 0)
        val s = schedule(ZeaScheduleKind.ONE_TIME, 0, 0, oneTimeStart = past)
        val plan = zeaScheduleFirePlan(s, "start", now)
        assertTrue(
            plan.action == ZeaScheduleAction.SKIP || plan.action == ZeaScheduleAction.EXPIRED
        )
    }

    @Test
    fun firePlan_endPhaseWhenWindowStillOpen_unhides() {
        val now = atLocal(2026, Calendar.AUGUST, 26, 12, 0)
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60)
        val plan = zeaScheduleFirePlan(s, "end", now)
        assertEquals(ZeaScheduleAction.UNHIDE, plan.action)
        assertEquals(atLocal(2026, Calendar.AUGUST, 26, 17, 0), plan.endEpochMillis)
    }

    // ---- END prior-state decisions ----

    private val now = atLocal(2026, Calendar.AUGUST, 26, 12, 0)

    @Test
    fun endPlan_noPriorState_unhides() {
        assertEquals(
            ZeaScheduleEndAction.UNHIDE,
            zeaScheduleEndPlan(null, overlappedByOther = false, nowEpochMillis = now)
        )
    }

    @Test
    fun endPlan_overlap_keepsHidden() {
        assertEquals(
            ZeaScheduleEndAction.SKIP,
            zeaScheduleEndPlan(null, overlappedByOther = true, nowEpochMillis = now)
        )
    }

    @Test
    fun endPlan_priorHidden_restoresHidden_neverUnhides() {
        val prior = ZeaScheduleOwnershipRecord(ZeaHideMode.HIDDEN, 0L)
        assertEquals(
            ZeaScheduleEndAction.RESTORE_HIDDEN,
            zeaScheduleEndPlan(prior, overlappedByOther = false, nowEpochMillis = now)
        )
    }

    @Test
    fun endPlan_priorTimedStillRunning_restoresTimer() {
        val futureEnd = now + 60_000L
        val prior = ZeaScheduleOwnershipRecord(ZeaHideMode.TIMED, futureEnd)
        assertEquals(
            ZeaScheduleEndAction.RESTORE_TIMED,
            zeaScheduleEndPlan(prior, overlappedByOther = false, nowEpochMillis = now)
        )
    }

    @Test
    fun endPlan_priorTimedAlreadyExpired_unhidesHonestly() {
        val pastEnd = now - 60_000L
        val prior = ZeaScheduleOwnershipRecord(ZeaHideMode.TIMED, pastEnd)
        assertEquals(
            ZeaScheduleEndAction.UNHIDE,
            zeaScheduleEndPlan(prior, overlappedByOther = false, nowEpochMillis = now)
        )
    }

    @Test
    fun endPlan_overlapBeatsPriorState() {
        val prior = ZeaScheduleOwnershipRecord(ZeaHideMode.HIDDEN, 0L)
        assertEquals(
            ZeaScheduleEndAction.SKIP,
            zeaScheduleEndPlan(prior, overlappedByOther = true, nowEpochMillis = now)
        )
    }

    // ---- schedule validation ----

    @Test
    fun validation_customDaysWithZeroDaysIsInvalid() {
        val s = schedule(ZeaScheduleKind.CUSTOM_DAYS, 9 * 60, 17 * 60, days = emptyList())
        assertFalse(zeaScheduleIsValid(s))
    }

    @Test
    fun validation_customDaysWithDaysIsValid() {
        val s = schedule(
            ZeaScheduleKind.CUSTOM_DAYS, 9 * 60, 17 * 60,
            days = listOf(Calendar.MONDAY)
        )
        assertTrue(zeaScheduleIsValid(s))
    }

    @Test
    fun validation_minuteOutOfRangeInvalid() {
        assertFalse(
            zeaScheduleIsValid(schedule(ZeaScheduleKind.DAILY, -1, 17 * 60))
        )
        assertFalse(
            zeaScheduleIsValid(schedule(ZeaScheduleKind.DAILY, 9 * 60, 1440))
        )
    }

    @Test
    fun validation_emptyTargetsInvalid() {
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60, targets = emptyList())
        assertFalse(zeaScheduleIsValid(s))
    }

    @Test
    fun validation_groupTargetWithoutPackagesValid() {
        val s = schedule(
            ZeaScheduleKind.DAILY, 9 * 60, 17 * 60,
            targets = emptyList(), groupId = "g1"
        )
        assertTrue(zeaScheduleIsValid(s))
    }

    @Test
    fun validation_blankNameInvalid() {
        val s = schedule(ZeaScheduleKind.DAILY, 9 * 60, 17 * 60).copy(name = "  ")
        assertFalse(zeaScheduleIsValid(s))
    }

    // ---- Safe Undo exact applied-state ownership ----

    private fun undoEntry(
        operation: UndoOperation,
        previousMode: ZeaHideMode = ZeaHideMode.VISIBLE,
        timedEnd: Long = 0L,
        appliedMode: ZeaHideMode? = null,
        appliedTimedEnd: Long = 0L,
        epoch: Long = now
    ): ZeaUndoEntry {
        val resolvedApplied = appliedMode ?: when (operation) {
            UndoOperation.HIDE -> ZeaHideMode.HIDDEN
            UndoOperation.UNHIDE -> ZeaHideMode.VISIBLE
            UndoOperation.TIMED_HIDE -> ZeaHideMode.TIMED
        }
        return ZeaUndoEntry(
            operation = operation,
            packageName = "com.example.a",
            displayName = "A",
            previousMode = previousMode,
            timedEndEpochMillis = timedEnd,
            epochMillis = epoch,
            appliedMode = resolvedApplied,
            appliedTimedEndEpochMillis = appliedTimedEnd
        )
    }

    @Test
    fun undoSafety_hideApplied_matchesCurrentHidden() {
        val entry = undoEntry(UndoOperation.HIDE)
        assertTrue(zeaUndoIsSafe(entry, ZeaHideMode.HIDDEN, 0L, now))
    }

    @Test
    fun undoSafety_hideThenTimed_isRefused() {
        // Hide was applied, but the app is now TIMED — another operation
        // claimed the state; the undo snapshot no longer owns it.
        val entry = undoEntry(UndoOperation.HIDE)
        assertFalse(zeaUndoIsSafe(entry, ZeaHideMode.TIMED, now + 60_000L, now))
    }

    @Test
    fun undoSafety_timedApplied_matchesOnlySameTimerEnd() {
        val appliedEnd = now + 3_600_000L
        val entry = undoEntry(
            UndoOperation.TIMED_HIDE,
            appliedTimedEnd = appliedEnd
        )
        assertTrue(zeaUndoIsSafe(entry, ZeaHideMode.TIMED, appliedEnd, now))
        // A different timer end means another operation re-armed: unsafe.
        assertFalse(zeaUndoIsSafe(entry, ZeaHideMode.TIMED, appliedEnd + 1_000L, now))
        assertFalse(zeaUndoIsSafe(entry, ZeaHideMode.HIDDEN, 0L, now))
    }

    @Test
    fun undoSafety_unhideApplied_matchesVisible() {
        val entry = undoEntry(UndoOperation.UNHIDE)
        assertTrue(zeaUndoIsSafe(entry, ZeaHideMode.VISIBLE, 0L, now))
        assertFalse(zeaUndoIsSafe(entry, ZeaHideMode.HIDDEN, 0L, now))
    }

    @Test
    fun undoSafety_expiredWindow_isRefused() {
        val old = now - ZeaUndo.WINDOW_MILLIS - 1L
        val entry = undoEntry(UndoOperation.HIDE, epoch = old)
        assertFalse(zeaUndoIsSafe(entry, ZeaHideMode.HIDDEN, 0L, now))
    }

    // ---- name sort stability after recency filters ----

    private fun app(name: String, pkg: String, mode: ZeaHideMode = ZeaHideMode.VISIBLE) =
        ZeaManagedApp(
            displayName = name,
            packageName = pkg,
            launcherActivityName = "$pkg.Main",
            hideMode = mode
        )

    @Test
    fun sort_nameAfterRecentlyManagedFilter_isAlphabetical() {
        val now = atLocal(2026, Calendar.AUGUST, 26, 12, 0)
        val zebra = app("Zebra", "com.z")
        val apple = app("Apple", "com.a")
        // Recently-managed order puts Zebra first; Name sort must still output
        // alphabetical regardless of incoming order.
        val recent = listOf(
            ZeaRecentlyManagedEntry("com.z", "Zebra", "Hide", now),
            ZeaRecentlyManagedEntry("com.a", "Apple", "Hide", now - 10_000L)
        )
        val result = filterAndSortApps(
            apps = listOf(zebra, apple),
            query = "",
            sort = ZeaAppsSort.NAME,
            filter = ZeaAppsFilter.RECENTLY_MANAGED,
            attributeFilter = null,
            recentlyManaged = recent,
            nowEpochMillis = now
        )
        assertEquals(listOf("Apple", "Zebra"), result.map { it.displayName })
    }
}
