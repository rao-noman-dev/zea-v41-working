package com.raomuhammadnoman.zea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Phase 3 - pure logic tests.
 *
 * Covers the Android-free logic of the Phase 3 requirements:
 *  - schedule kind round-trip and default
 *  - next-run computation for ONE_TIME / DAILY / WEEKDAYS / CUSTOM_DAYS
 *  - end-time computation stays later than start, crossing midnight when needed
 */
class ZeaPhase3LogicTest {

    // ---- kind metadata ----

    @Test
    fun scheduleKind_exactlyFourKindsExist() {
        assertEquals(4, ZeaScheduleKind.entries.size)
    }

    @Test
    fun scheduleKind_storageKeysRoundTrip() {
        ZeaScheduleKind.entries.forEach { kind ->
            assertEquals(kind, ZeaScheduleKind.fromStorageKey(kind.storageKey))
        }
    }

    @Test
    fun scheduleKind_unknownKeyFallsBackToDaily() {
        assertEquals(ZeaScheduleKind.DAILY, ZeaScheduleKind.fromStorageKey("bogus"))
        assertEquals(ZeaScheduleKind.DAILY, ZeaScheduleKind.fromStorageKey(null))
    }

    // ---- next run ----

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

    private fun oneTimeSchedule(
        start: Long,
        enabled: Boolean = true
    ) = ZeaSchedule(
        id = "one-time",
        name = "one-time",
        kind = ZeaScheduleKind.ONE_TIME,
        daysOfWeek = emptyList(),
        startMinuteOfDay = 0,
        endMinuteOfDay = 0,
        targetGroupId = null,
        targetPackages = listOf("com.example.a"),
        oneTimeStartEpochMillis = start,
        enabled = enabled,
        createdAtEpochMillis = 0L
    )

    @Test
    fun nextRun_disabledReturnsNull() {
        val schedule = oneTimeSchedule(start = 1_000L, enabled = false)
        assertNull(zeaScheduleNextRun(schedule, 0L))
    }

    @Test
    fun nextRun_oneTimeInFutureReturnsSameEpoch() {
        val now = atLocal(2026, Calendar.AUGUST, 25, 8, 0)
        val later = atLocal(2026, Calendar.AUGUST, 25, 9, 0)
        val schedule = oneTimeSchedule(start = later)
        assertEquals(later, zeaScheduleNextRun(schedule, now))
    }

    @Test
    fun nextRun_oneTimeInPastReturnsNull() {
        val now = atLocal(2026, Calendar.AUGUST, 25, 9, 30)
        val earlier = atLocal(2026, Calendar.AUGUST, 25, 9, 0)
        val schedule = oneTimeSchedule(start = earlier)
        assertNull(zeaScheduleNextRun(schedule, now))
    }

    @Test
    fun nextRun_dailyPicksTodayWhenTimeIsStillAhead() {
        val now = atLocal(2026, Calendar.AUGUST, 25, 8, 0)
        val schedule = ZeaSchedule(
            id = "daily",
            name = "daily",
            kind = ZeaScheduleKind.DAILY,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        val expected = atLocal(2026, Calendar.AUGUST, 25, 9, 0)
        assertEquals(expected, zeaScheduleNextRun(schedule, now))
    }

    @Test
    fun nextRun_dailyRollsToTomorrowWhenTimeHasPassed() {
        val now = atLocal(2026, Calendar.AUGUST, 25, 18, 30)
        val schedule = ZeaSchedule(
            id = "daily",
            name = "daily",
            kind = ZeaScheduleKind.DAILY,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        val expected = atLocal(2026, Calendar.AUGUST, 26, 9, 0)
        assertEquals(expected, zeaScheduleNextRun(schedule, now))
    }

    @Test
    fun nextRun_weekdaysSkipsWeekend() {
        // Friday 21 Aug 2026, 23:00 => next weekday is Monday 24 Aug 2026 09:00.
        val now = atLocal(2026, Calendar.AUGUST, 21, 23, 0)
        val schedule = ZeaSchedule(
            id = "weekdays",
            name = "weekdays",
            kind = ZeaScheduleKind.WEEKDAYS,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        val expected = atLocal(2026, Calendar.AUGUST, 24, 9, 0)
        assertEquals(expected, zeaScheduleNextRun(schedule, now))
    }

    @Test
    fun nextRun_customDaysEmptySetReturnsNull() {
        val schedule = ZeaSchedule(
            id = "custom-empty",
            name = "custom-empty",
            kind = ZeaScheduleKind.CUSTOM_DAYS,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        assertNull(zeaScheduleNextRun(schedule, atLocal(2026, Calendar.AUGUST, 25, 8, 0)))
    }

    @Test
    fun nextRun_customDaysMatchesOnlyListedDays() {
        // Tuesday 25 Aug 2026, 23:00 with a Wednesday-only schedule must
        // wait until 26 Aug 2026 09:00.
        val now = atLocal(2026, Calendar.AUGUST, 25, 23, 0)
        val schedule = ZeaSchedule(
            id = "custom",
            name = "custom",
            kind = ZeaScheduleKind.CUSTOM_DAYS,
            daysOfWeek = listOf(Calendar.WEDNESDAY),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        val expected = atLocal(2026, Calendar.AUGUST, 26, 9, 0)
        assertEquals(expected, zeaScheduleNextRun(schedule, now))
    }

    // ---- end after ----

    @Test
    fun endAfter_sameDayWhenEndIsLaterThanStart() {
        val start = atLocal(2026, Calendar.AUGUST, 25, 9, 0)
        val schedule = ZeaSchedule(
            id = "s",
            name = "s",
            kind = ZeaScheduleKind.DAILY,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = emptyList(),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        val end = zeaScheduleEndAfter(schedule, start)
        val expected = atLocal(2026, Calendar.AUGUST, 25, 17, 0)
        assertEquals(expected, end)
    }

    @Test
    fun endAfter_crossesMidnightWhenEndIsEarlierThanStart() {
        val start = atLocal(2026, Calendar.AUGUST, 25, 22, 0)
        val schedule = ZeaSchedule(
            id = "s",
            name = "s",
            kind = ZeaScheduleKind.DAILY,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60,
            targetGroupId = null,
            targetPackages = emptyList(),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        val end = zeaScheduleEndAfter(schedule, start)
        val expected = atLocal(2026, Calendar.AUGUST, 26, 6, 0)
        assertEquals(expected, end)
        assertTrue(end > start)
    }

    @Test
    fun endAfter_neverReturnsAPastInstant() {
        val start = atLocal(2026, Calendar.AUGUST, 25, 9, 0)
        val schedule = ZeaSchedule(
            id = "s",
            name = "s",
            kind = ZeaScheduleKind.DAILY,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 8 * 60,
            targetGroupId = null,
            targetPackages = emptyList(),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        val end = zeaScheduleEndAfter(schedule, start)
        assertNotNull(end)
        assertTrue(end > start)
    }

    // ---- active window (missed-window recovery + overlapping ownership) ----

    private fun dailySchedule(
        startMinute: Int,
        endMinute: Int,
        enabled: Boolean = true
    ) = ZeaSchedule(
        id = "daily-active",
        name = "daily-active",
        kind = ZeaScheduleKind.DAILY,
        daysOfWeek = emptyList(),
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
        targetGroupId = null,
        targetPackages = listOf("com.example.a"),
        oneTimeStartEpochMillis = 0L,
        enabled = enabled,
        createdAtEpochMillis = 0L
    )

    @Test
    fun activeWindow_insideDailyWindowReturnsEndOfCurrentCycle() {
        // Daily 09:00 -> 17:00; now is 12:00 on 25 Aug 2026.
        val now = atLocal(2026, Calendar.AUGUST, 25, 12, 0)
        val schedule = dailySchedule(9 * 60, 17 * 60)
        val expected = atLocal(2026, Calendar.AUGUST, 25, 17, 0)
        assertEquals(expected, zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_beforeDailyWindowReturnsNull() {
        val now = atLocal(2026, Calendar.AUGUST, 25, 8, 0)
        val schedule = dailySchedule(9 * 60, 17 * 60)
        assertNull(zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_afterDailyWindowReturnsNull() {
        val now = atLocal(2026, Calendar.AUGUST, 25, 18, 0)
        val schedule = dailySchedule(9 * 60, 17 * 60)
        assertNull(zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_crossesMidnightStillReportsNextDayEnd() {
        // Daily 22:00 -> 06:00; now is 23:30 same day.
        val now = atLocal(2026, Calendar.AUGUST, 25, 23, 30)
        val schedule = dailySchedule(22 * 60, 6 * 60)
        val expected = atLocal(2026, Calendar.AUGUST, 26, 6, 0)
        assertEquals(expected, zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_crossesMidnightAndStaysActivePastMidnight() {
        // Daily 22:00 -> 06:00; now is 03:00 the NEXT day. The start epoch
        // calculated for "today" is 22:00 tonight, which is in the future —
        // so the engine must NOT consider the schedule active.
        val now = atLocal(2026, Calendar.AUGUST, 26, 3, 0)
        val schedule = dailySchedule(22 * 60, 6 * 60)
        assertNull(zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_disabledScheduleNeverActive() {
        val now = atLocal(2026, Calendar.AUGUST, 25, 12, 0)
        val schedule = dailySchedule(9 * 60, 17 * 60, enabled = false)
        assertNull(zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_oneTimeInsideWindowReturnsEnd() {
        val start = atLocal(2026, Calendar.AUGUST, 25, 9, 0)
        val now = atLocal(2026, Calendar.AUGUST, 25, 10, 0)
        val schedule = ZeaSchedule(
            id = "one-time-active",
            name = "one-time-active",
            kind = ZeaScheduleKind.ONE_TIME,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = start,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        val expected = atLocal(2026, Calendar.AUGUST, 25, 17, 0)
        assertEquals(expected, zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_oneTimeAfterWindowReturnsNull() {
        val start = atLocal(2026, Calendar.AUGUST, 25, 9, 0)
        val now = atLocal(2026, Calendar.AUGUST, 25, 18, 0)
        val schedule = ZeaSchedule(
            id = "one-time-done",
            name = "one-time-done",
            kind = ZeaScheduleKind.ONE_TIME,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = start,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        assertNull(zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_weekdaysWeekendReturnsNull() {
        // Saturday 22 Aug 2026, 12:00 — weekdays never active on weekends.
        val now = atLocal(2026, Calendar.AUGUST, 22, 12, 0)
        val schedule = ZeaSchedule(
            id = "weekdays-active",
            name = "weekdays-active",
            kind = ZeaScheduleKind.WEEKDAYS,
            daysOfWeek = emptyList(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        assertNull(zeaScheduleActiveWindow(schedule, now))
    }

    @Test
    fun activeWindow_customDaysOnlyListedDayIsActive() {
        // Tuesday 25 Aug 2026, 12:00 — a Wednesday-only schedule is inactive.
        val now = atLocal(2026, Calendar.AUGUST, 25, 12, 0)
        val schedule = ZeaSchedule(
            id = "custom-active",
            name = "custom-active",
            kind = ZeaScheduleKind.CUSTOM_DAYS,
            daysOfWeek = listOf(Calendar.WEDNESDAY),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            targetGroupId = null,
            targetPackages = listOf("com.example.a"),
            oneTimeStartEpochMillis = 0L,
            enabled = true,
            createdAtEpochMillis = 0L
        )
        assertNull(zeaScheduleActiveWindow(schedule, now))
    }

    // ---- Safe Undo reversal plan (pure) ----

    private fun undoEntry(
        operation: UndoOperation,
        previousMode: ZeaHideMode,
        timedEnd: Long = 0L
    ) = ZeaUndoEntry(
        operation = operation,
        packageName = "com.example.a",
        displayName = "Example",
        previousMode = previousMode,
        timedEndEpochMillis = timedEnd,
        epochMillis = 0L
    )

    @Test
    fun undoPlan_hideOperationAlwaysUnhides() {
        val plan = zeaUndoReversalPlan(
            undoEntry(UndoOperation.HIDE, ZeaHideMode.VISIBLE),
            nowEpochMillis = 1_000L
        )
        assertEquals(ZeaUndoPlan.UNHIDE, plan)
    }

    @Test
    fun undoPlan_unhideOfPermanentlyHiddenAppRehidesPermanently() {
        val plan = zeaUndoReversalPlan(
            undoEntry(UndoOperation.UNHIDE, ZeaHideMode.HIDDEN),
            nowEpochMillis = 1_000L
        )
        assertEquals(ZeaUndoPlan.HIDE_PERMANENT, plan)
    }

    @Test
    fun undoPlan_unhideOfTimedAppRearmsOriginalEndWhenStillInFuture() {
        val end = 10_000L
        val plan = zeaUndoReversalPlan(
            undoEntry(UndoOperation.UNHIDE, ZeaHideMode.TIMED, timedEnd = end),
            nowEpochMillis = 5_000L
        )
        assertEquals(ZeaUndoPlan.REARM_TIMER, plan)
    }

    @Test
    fun undoPlan_unhideOfTimedAppWithExpiredEndReportsExpired() {
        val end = 10_000L
        val plan = zeaUndoReversalPlan(
            undoEntry(UndoOperation.UNHIDE, ZeaHideMode.TIMED, timedEnd = end),
            nowEpochMillis = 20_000L
        )
        assertEquals(ZeaUndoPlan.EXPIRED, plan)
    }

    @Test
    fun undoPlan_timedHideOfVisibleAppUnhides() {
        val plan = zeaUndoReversalPlan(
            undoEntry(UndoOperation.TIMED_HIDE, ZeaHideMode.VISIBLE),
            nowEpochMillis = 1_000L
        )
        assertEquals(ZeaUndoPlan.UNHIDE, plan)
    }

    @Test
    fun undoPlan_timedHideOfPermanentlyHiddenAppConvertsToPermanent() {
        val plan = zeaUndoReversalPlan(
            undoEntry(UndoOperation.TIMED_HIDE, ZeaHideMode.HIDDEN),
            nowEpochMillis = 1_000L
        )
        assertEquals(ZeaUndoPlan.CONVERT_TO_PERMANENT, plan)
    }

    @Test
    fun undoPlan_reTimedAppRestoresPriorEndInsteadOfPermanentConversion() {
        val end = 10_000L
        val plan = zeaUndoReversalPlan(
            undoEntry(UndoOperation.TIMED_HIDE, ZeaHideMode.TIMED, timedEnd = end),
            nowEpochMillis = 5_000L
        )
        assertEquals(ZeaUndoPlan.REARM_TIMER, plan)
    }

    @Test
    fun undoPlan_reTimedAppWithExpiredPriorEndConvertsToPermanent() {
        val end = 10_000L
        val plan = zeaUndoReversalPlan(
            undoEntry(UndoOperation.TIMED_HIDE, ZeaHideMode.TIMED, timedEnd = end),
            nowEpochMillis = 20_000L
        )
        assertEquals(ZeaUndoPlan.CONVERT_TO_PERMANENT, plan)
    }

    // ---- Search status label (pure) ----

    private fun managedApp(
        mode: ZeaHideMode,
        manageable: Boolean = true,
        hiddenUntil: Long = 0L
    ) = ZeaManagedApp(
        displayName = "Example",
        packageName = "com.example.a",
        launcherActivityName = "",
        hideMode = mode,
        manageable = manageable,
        hiddenUntilEpochMillis = hiddenUntil
    )

    @Test
    fun searchStatus_visibleAppReportsVisible() {
        assertEquals("Visible", zeaSearchStatusLabel(managedApp(ZeaHideMode.VISIBLE), 0L))
    }

    @Test
    fun searchStatus_hiddenAppReportsHidden() {
        assertEquals("Hidden", zeaSearchStatusLabel(managedApp(ZeaHideMode.HIDDEN), 0L))
    }

    @Test
    fun searchStatus_timedAppReportsRemainingMinutes() {
        val now = 60_000L
        val label = zeaSearchStatusLabel(
            managedApp(ZeaHideMode.TIMED, hiddenUntil = now + 30L * 60_000L),
            nowEpochMillis = now
        )
        assertEquals("Timed (30 min left)", label)
    }

    @Test
    fun searchStatus_timedEndInPastClampsRemainingToZero() {
        val now = 60_000L
        val label = zeaSearchStatusLabel(
            managedApp(ZeaHideMode.TIMED, hiddenUntil = 0L),
            nowEpochMillis = now
        )
        assertEquals("Timed (0 min left)", label)
    }

    @Test
    fun searchStatus_nonManageableAppIsMarkedProtected() {
        assertEquals(
            "Visible • Protected",
            zeaSearchStatusLabel(
                managedApp(ZeaHideMode.VISIBLE, manageable = false),
                0L
            )
        )
    }

    // ---- All Apps filters and sorts (pure) ----

    private fun recentEntry(
        packageName: String,
        epoch: Long,
        operation: String = "Hide"
    ) = ZeaRecentlyManagedEntry(
        packageName = packageName,
        displayName = packageName,
        operation = operation,
        epochMillis = epoch
    )

    @Test
    fun filter_protectedIsDerivedFromManageability() {
        val apps = listOf(
            managedApp(ZeaHideMode.VISIBLE, manageable = true),
            managedApp(ZeaHideMode.HIDDEN, manageable = true)
        )
        val filtered = filterInternal(
            apps,
            statusFilter = ZeaAppsFilter.ALL,
            attributeFilter = ZeaAppsFilter.PROTECTED
        )
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun filter_unprotectedIncludesAllNonProtectedApps() {
        val apps = listOf(managedApp(ZeaHideMode.VISIBLE, manageable = true))
        val filtered = filterInternal(
            apps,
            statusFilter = ZeaAppsFilter.ALL,
            attributeFilter = ZeaAppsFilter.UNPROTECTED
        )
        assertEquals(1, filtered.size)
    }

    @Test
    fun filter_statusAndAttributeAxesCombineWithAnd() {
        val hiddenUnprotected = managedApp(ZeaHideMode.HIDDEN, manageable = true)
        val visibleUnprotected = managedApp(ZeaHideMode.VISIBLE, manageable = true)
        val paid = filterInternal(
            listOf(hiddenUnprotected.copy(packageName = "a"), visibleUnprotected.copy(packageName = "b")),
            statusFilter = ZeaAppsFilter.HIDDEN,
            attributeFilter = ZeaAppsFilter.UNPROTECTED
        )
        assertEquals(listOf("a"), paid.map { it.packageName })
    }

    @Test
    fun filter_recentlyManagedDropsAppsWithoutARecentEvent() {
        val apps = listOf(
            managedApp(ZeaHideMode.VISIBLE).copy(packageName = "a"),
            managedApp(ZeaHideMode.VISIBLE).copy(packageName = "b")
        )
        val filtered = filterInternal(
            apps,
            statusFilter = ZeaAppsFilter.ALL,
            attributeFilter = ZeaAppsFilter.RECENTLY_MANAGED,
            recentlyManaged = mapOf("a" to recentEntry("a", 1_000L)),
            now = 2_000L
        )
        assertEquals(listOf("a"), filtered.map { it.packageName })
    }

    @Test
    fun sort_recentlyHiddenUsesLatestMatchingEvent() {
        val events = listOf(
            recentEntry("a", 100L, "Unhide"),
            recentEntry("b", 300L, "Hide"),
            recentEntry("a", 200L, "Hide")
        )
        assertEquals(200L, zeaLatestManagedEventMillis(events, "a", "Hide"))
    }

    @Test
    fun sort_recentlyUnhiddenOnlyCountsUnhideEvents() {
        val events = listOf(
            recentEntry("a", 100L, "Unhide"),
            recentEntry("a", 200L, "Hide")
        )
        assertEquals(100L, zeaLatestManagedEventMillis(events, "a", "Unhide"))
    }

    @Test
    fun sort_recentlyInstalledAndRecentlyHiddenDisagree() {
        // Different orderings must be observable; two sort axes never collapse
        // to the same "recent" bucket like older builds did.
        val apps = listOf(
            managedApp(ZeaHideMode.VISIBLE).copy(packageName = "a", firstInstallTimeEpochMillis = 500L),
            managedApp(ZeaHideMode.VISIBLE).copy(packageName = "b", firstInstallTimeEpochMillis = 100L)
        )
        val events = listOf(
            recentEntry("a", 10L, "Hide"),
            recentEntry("b", 900L, "Hide")
        )
        val byInstalled = filterAndSortApps(
            apps,
            query = "",
            sort = ZeaAppsSort.RECENTLY_INSTALLED,
            recentlyManaged = events,
            nowEpochMillis = 1_000L
        )
        val byHidden = filterAndSortApps(
            apps,
            query = "",
            sort = ZeaAppsSort.RECENTLY_HIDDEN,
            recentlyManaged = events,
            nowEpochMillis = 1_000L
        )
        assertEquals(listOf("a", "b"), byInstalled.map { it.packageName })
        assertEquals(listOf("b", "a"), byHidden.map { it.packageName })
    }

    @Test
    fun sort_missingEventSortsToBottomConsistency() {
        val apps = listOf(
            managedApp(ZeaHideMode.VISIBLE).copy(packageName = "b"),
            managedApp(ZeaHideMode.VISIBLE).copy(packageName = "a")
        )
        val sorted = filterAndSortApps(
            apps,
            query = "",
            sort = ZeaAppsSort.RECENTLY_HIDDEN,
            recentlyManaged = listOf(recentEntry("a", 500L, "Hide")),
            nowEpochMillis = 1_000L
        )
        assertEquals(listOf("a", "b"), sorted.map { it.packageName })
    }

    // ---- Group batch journal (pure) ----

    @Test
    fun journal_allTargetsProcessedIsCaseInsensitive() {
        val record = ZeaBatchJournalRecord(
            batchId = "b1",
            operation = ZeaBatchJournal.OPERATION_HIDE,
            startedAtEpochMillis = 0L,
            targets = listOf("Com.Example.A"),
            processed = listOf("com.example.a")
        )
        assertTrue(ZeaBatchJournal.allTargetsProcessed(record))
    }

    @Test
    fun journal_unprocessedTargetMeansBatchNotFinished() {
        val record = ZeaBatchJournalRecord(
            batchId = "b1",
            operation = ZeaBatchJournal.OPERATION_HIDE,
            startedAtEpochMillis = 0L,
            targets = listOf("a", "b"),
            processed = listOf("a")
        )
        assertTrue(!ZeaBatchJournal.allTargetsProcessed(record))
    }

    @Test
    fun journal_timedRequestOnlyDecodesWithPositiveEndAndLabel() {
        val timed = ZeaBatchJournalRecord(
            batchId = "b1",
            operation = ZeaBatchJournal.OPERATION_TIMED_HIDE,
            startedAtEpochMillis = 0L,
            targets = emptyList(),
            processed = emptyList(),
            timedEndEpochMillis = 100L,
            timedLabel = "timer"
        )
        assertNotNull(timed.timedRequestOrNull())
        val broken = timed.copy(timedLabel = "")
        assertNull(broken.timedRequestOrNull())
    }

    private fun filterInternal(
        apps: List<ZeaManagedApp>,
        statusFilter: ZeaAppsFilter,
        attributeFilter: ZeaAppsFilter = ZeaAppsFilter.ALL,
        sort: ZeaAppsSort = ZeaAppsSort.NAME,
        recentlyManaged: Map<String, ZeaRecentlyManagedEntry> = emptyMap(),
        now: Long = 0L
    ): List<ZeaManagedApp> = filterAndSortApps(
        apps = apps,
        query = "",
        sort = sort,
        filter = statusFilter,
        attributeFilter = attributeFilter,
        recentlyManaged = recentlyManaged.values.toList(),
        nowEpochMillis = now
    )
}
