package com.raomuhammadnoman.zea

import java.util.Calendar

/**
 * Pure decision logic for the schedule state machine. Kept Android-free so
 * every branch is covered by deterministic unit tests; the runtime layer
 * (ZeaSchedules) only executes what these functions decide.
 */

/** How the runtime must reconcile when a scheduled fire (or its absence) is observed. */
enum class ZeaScheduleAction { HIDE, UNHIDE, SKIP, EXPIRED }

data class ZeaScheduleFireDecision(
    val action: ZeaScheduleAction,
    val endEpochMillis: Long
)

/** Prior state captured at START so END never blindly unhides manual state. */
data class ZeaScheduleOwnershipRecord(
    val previousMode: ZeaHideMode,
    val previousTimedEndEpochMillis: Long
)

/** What END must do for one package, given what START captured. */
enum class ZeaScheduleEndAction { UNHIDE, RESTORE_HIDDEN, RESTORE_TIMED, SKIP }

/**
 * Where the current cycle's START lands relative to the intended window.
 * A delayed START that arrives after the window's END must be reconciled to
 * SKIP — the app must NOT be hidden until tomorrow's end.
 */
fun zeaScheduleFirePlan(
    schedule: ZeaSchedule,
    phase: String,
    nowEpochMillis: Long
): ZeaScheduleFireDecision {
    if (!schedule.enabled) return ZeaScheduleFireDecision(ZeaScheduleAction.SKIP, 0L)

    if (phase == "start") {
        // A START alarm means "begin hiding NOW". If hiding now would run
        // past the end of THIS day's window, the alarm is stale (delayed past
        // its own window) and must be skipped instead of locking the app
        // until the next cycle's end.
        if (schedule.kind == ZeaScheduleKind.ONE_TIME) {
            val start = schedule.oneTimeStartEpochMillis
            val end = zeaScheduleEndAfter(schedule, start)
            return when {
                nowEpochMillis < start ->
                    ZeaScheduleFireDecision(ZeaScheduleAction.HIDE, end)
                nowEpochMillis < end ->
                    ZeaScheduleFireDecision(ZeaScheduleAction.HIDE, end)
                else -> ZeaScheduleFireDecision(ZeaScheduleAction.SKIP, 0L)
            }
        }

        // Today's own occurrence (null when today is not a selected day).
        val todaysStart = zeaScheduleIntendedStart(schedule, nowEpochMillis)
        if (todaysStart != null) {
            val todaysEnd = zeaScheduleEndAfter(schedule, todaysStart)
            if (nowEpochMillis >= todaysEnd) {
                // The window this START belonged to is already over.
                return ZeaScheduleFireDecision(ZeaScheduleAction.SKIP, 0L)
            }
            // Before today's start or inside today's window: hide until the
            // end of today's window.
            return ZeaScheduleFireDecision(ZeaScheduleAction.HIDE, todaysEnd)
        }

        // Today has no own occurrence: only yesterday's cross-midnight carry
        // can justify hiding now.
        val activeEnd = zeaScheduleActiveWindow(schedule, nowEpochMillis)
        return if (activeEnd != null) {
            ZeaScheduleFireDecision(ZeaScheduleAction.HIDE, activeEnd)
        } else {
            ZeaScheduleFireDecision(ZeaScheduleAction.SKIP, 0L)
        }
    }

    // END phase: if the window is somehow still open, keep it; otherwise expire.
    val activeEnd = zeaScheduleActiveWindow(schedule, nowEpochMillis)
    return if (activeEnd != null && nowEpochMillis < activeEnd) {
        ZeaScheduleFireDecision(ZeaScheduleAction.UNHIDE, activeEnd)
    } else {
        ZeaScheduleFireDecision(ZeaScheduleAction.EXPIRED, 0L)
    }
}

/**
 * The most recent START occurrence of [schedule] at or before [nowEpochMillis],
 * or null when none exists today (or for one-time, when its moment is not yet
 * reached). Used to detect "the START alarm that just fired belonged to a
 * window that has already ended".
 */
fun zeaScheduleIntendedStart(
    schedule: ZeaSchedule,
    nowEpochMillis: Long
): Long? {
    return when (schedule.kind) {
        ZeaScheduleKind.ONE_TIME ->
            schedule.oneTimeStartEpochMillis.takeIf { it <= nowEpochMillis }
        ZeaScheduleKind.DAILY ->
            zeaTodayOccurrence(schedule.startMinuteOfDay, nowEpochMillis)
        ZeaScheduleKind.WEEKDAYS -> {
            val day = Calendar.getInstance().apply { timeInMillis = nowEpochMillis }
                .get(Calendar.DAY_OF_WEEK)
            if (day !in Calendar.MONDAY..Calendar.FRIDAY) null
            else zeaTodayOccurrence(schedule.startMinuteOfDay, nowEpochMillis)
        }
        ZeaScheduleKind.CUSTOM_DAYS -> {
            if (schedule.daysOfWeek.isEmpty()) null
            else {
                val day = Calendar.getInstance().apply { timeInMillis = nowEpochMillis }
                    .get(Calendar.DAY_OF_WEEK)
                if (day !in schedule.daysOfWeek) null
                else zeaTodayOccurrence(schedule.startMinuteOfDay, nowEpochMillis)
            }
        }
    }
}

private fun zeaTodayOccurrence(minuteOfDay: Int, nowEpochMillis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = nowEpochMillis
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
    }.timeInMillis

/**
 * Decides what a schedule END may do with one package.
 *
 * - priorState null          → the schedule owns it (was VISIBLE at START) → UNHIDE
 * - priorState HIDDEN        → manual hide predates the schedule → leave hidden (SKIP via RESTORE_HIDDEN)
 * - priorState TIMED         → restore the ORIGINAL timer end, never blindly unhide
 * - overlappedByOther true   → another active schedule still needs it hidden → SKIP
 * - priorTimedEnd <= now     → the original timer already expired → UNHIDE is honest
 */
fun zeaScheduleEndPlan(
    priorState: ZeaScheduleOwnershipRecord?,
    overlappedByOther: Boolean,
    nowEpochMillis: Long
): ZeaScheduleEndAction {
    if (overlappedByOther) return ZeaScheduleEndAction.SKIP
    return when {
        priorState == null -> ZeaScheduleEndAction.UNHIDE
        priorState.previousMode == ZeaHideMode.HIDDEN -> ZeaScheduleEndAction.RESTORE_HIDDEN
        priorState.previousMode == ZeaHideMode.TIMED ->
            if (priorState.previousTimedEndEpochMillis > nowEpochMillis) {
                ZeaScheduleEndAction.RESTORE_TIMED
            } else {
                ZeaScheduleEndAction.UNHIDE
            }
        else -> ZeaScheduleEndAction.UNHIDE
    }
}

/**
 * A Custom-days schedule with zero selected days is invalid: it can never fire
 * and would silently present as enabled. Blocked at create/update time.
 */
fun zeaScheduleIsValid(schedule: ZeaSchedule): Boolean =
    schedule.name.isNotBlank() &&
            schedule.name.length <= 60 &&
            schedule.startMinuteOfDay in 0..1439 &&
            schedule.endMinuteOfDay in 0..1439 &&
            (schedule.targetGroupId != null || schedule.targetPackages.isNotEmpty()) &&
            (schedule.kind != ZeaScheduleKind.CUSTOM_DAYS || schedule.daysOfWeek.isNotEmpty())
