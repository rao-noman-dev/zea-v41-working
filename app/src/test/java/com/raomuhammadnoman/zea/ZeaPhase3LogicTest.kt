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
}
