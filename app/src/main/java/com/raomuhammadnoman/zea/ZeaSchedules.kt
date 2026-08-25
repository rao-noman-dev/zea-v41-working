package com.raomuhammadnoman.zea

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class ZeaScheduleKind(val storageKey: String, val label: String) {
    ONE_TIME("one_time", "One-time"),
    DAILY("daily", "Daily"),
    WEEKDAYS("weekdays", "Weekdays"),
    CUSTOM_DAYS("custom_days", "Custom days");

    companion object {
        fun fromStorageKey(key: String?): ZeaScheduleKind =
            entries.firstOrNull { it.storageKey == key } ?: DAILY
    }
}

data class ZeaSchedule(
    val id: String,
    val name: String,
    val kind: ZeaScheduleKind,
    /** Day-of-week values (Calendar.SUNDAY..SATURDAY) for CUSTOM_DAYS; empty for others. */
    val daysOfWeek: List<Int>,
    /** Minutes since local midnight at which the hide action fires. */
    val startMinuteOfDay: Int,
    /** Minutes since local midnight at which the unhide action fires. */
    val endMinuteOfDay: Int,
    /** Group target id (from [ZeaGroups]) or null when targeting packages directly. */
    val targetGroupId: String?,
    /** Direct package targets (used when no group is selected). */
    val targetPackages: List<String>,
    /** Optional one-time execution timestamp (only meaningful for ONE_TIME). */
    val oneTimeStartEpochMillis: Long,
    /** Whether the schedule is armed. */
    val enabled: Boolean,
    val createdAtEpochMillis: Long
)

/** Next alarm the engine should arm for a schedule, or null when inactive. */
fun zeaScheduleNextRun(
    schedule: ZeaSchedule,
    nowEpochMillis: Long
): Long? {
    if (!schedule.enabled) return null

    // Calendar-based day stepping keeps the local wall-clock time intact
    // across DST transitions; raw +24h arithmetic would drift the fire time.
    fun occurrences(minuteOfDay: Int): Sequence<Long> = generateSequence(
        Calendar.getInstance().apply {
            timeInMillis = nowEpochMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
        }
    ) { previous ->
        (previous.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    }.map { it.timeInMillis }.filter { it > nowEpochMillis }

    return when (schedule.kind) {
        ZeaScheduleKind.ONE_TIME ->
            if (schedule.oneTimeStartEpochMillis > nowEpochMillis) {
                schedule.oneTimeStartEpochMillis
            } else {
                null
            }
        ZeaScheduleKind.DAILY -> occurrences(schedule.startMinuteOfDay).firstOrNull()
        ZeaScheduleKind.WEEKDAYS -> occurrences(schedule.startMinuteOfDay).firstOrNull { epoch ->
            val day = Calendar.getInstance().apply { timeInMillis = epoch }
                .get(Calendar.DAY_OF_WEEK)
            day in Calendar.MONDAY..Calendar.FRIDAY
        }
        ZeaScheduleKind.CUSTOM_DAYS -> {
            if (schedule.daysOfWeek.isEmpty()) return null
            occurrences(schedule.startMinuteOfDay).firstOrNull { epoch ->
                Calendar.getInstance().apply { timeInMillis = epoch }
                    .get(Calendar.DAY_OF_WEEK) in schedule.daysOfWeek
            }
        }
    }
}

/** Next end-time after the given start, used to arm the unhide half. */
fun zeaScheduleEndAfter(
    schedule: ZeaSchedule,
    startEpochMillis: Long
): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = startEpochMillis
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.HOUR_OF_DAY, schedule.endMinuteOfDay / 60)
        set(Calendar.MINUTE, schedule.endMinuteOfDay % 60)
    }
    if (calendar.timeInMillis <= startEpochMillis) {
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return calendar.timeInMillis
}

/**
 * If [nowEpochMillis] is inside the schedule's active hide window, returns the
 * end of that CURRENT window. Returns null when the schedule is outside any
 * active window (or disabled). This is the missed-active-window recovery hook:
 * after reboot/time-change, a still-active window must not be lost.
 *
 * Cross-midnight windows require looking at YESTERDAY's start as well as
 * today's: a Monday 22:00 -> 07:00 window is still active at Tuesday 01:00,
 * even though Tuesday itself may not be a selected day. We therefore evaluate
 * the most recent candidate start on today AND yesterday and return the end of
 * whichever window currently contains [nowEpochMillis].
 */
fun zeaScheduleActiveWindow(
    schedule: ZeaSchedule,
    nowEpochMillis: Long
): Long? {
    if (!schedule.enabled) return null

    fun startOnDay(dayOffset: Int): Long? {
        val base = Calendar.getInstance().apply {
            timeInMillis = nowEpochMillis
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        val day = base.get(Calendar.DAY_OF_WEEK)
        val daySelected = when (schedule.kind) {
            ZeaScheduleKind.ONE_TIME -> false
            ZeaScheduleKind.DAILY -> true
            ZeaScheduleKind.WEEKDAYS -> day in Calendar.MONDAY..Calendar.FRIDAY
            ZeaScheduleKind.CUSTOM_DAYS ->
                schedule.daysOfWeek.isNotEmpty() && day in schedule.daysOfWeek
        }
        if (!daySelected) return null
        return base.apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, schedule.startMinuteOfDay / 60)
            set(Calendar.MINUTE, schedule.startMinuteOfDay % 60)
        }.timeInMillis
    }

    val candidates = when (schedule.kind) {
        ZeaScheduleKind.ONE_TIME -> listOf(schedule.oneTimeStartEpochMillis)
        else -> listOfNotNull(startOnDay(0), startOnDay(-1))
    }

    var activeEnd: Long? = null
    for (start in candidates) {
        val end = zeaScheduleEndAfter(schedule, start)
        if (nowEpochMillis >= start && nowEpochMillis < end) {
            if (activeEnd == null || end > activeEnd) activeEnd = end
        }
    }
    return activeEnd
}

/**
 * Phase 3 recurring/scheduled hiding engine.
 *
 * Schedules persist in SharedPreferences; the engine arms one alarm per
 * schedule (the nearest pending start/end) and re-arms on boot/time-change.
 * Executions re-use the same verified [ZeaAppHideService] transactions.
 */
object ZeaSchedules {
    private const val KEY_SCHEDULES = "app_schedules_v1"
    private const val KEY_SCHEDULE_OWNERSHIP = "app_schedule_ownership_v1"
    private const val ACTION_FIRE = "com.raomuhammadnoman.zea.action.SCHEDULE_FIRED"
    private const val EXTRA_SCHEDULE_ID = "schedule_id"
    private const val EXTRA_SCHEDULE_PHASE = "schedule_phase"
    private const val PHASE_START = "start"
    private const val PHASE_END = "end"

    // ---- Per-schedule prior-state ownership ----
    // Captured at START so a later END never blindly unhides state the user
    // had before the schedule claimed the app (manual hide / timed state).

    private fun loadOwnership(context: Context): Map<String, Map<String, ZeaScheduleOwnershipRecord>> {
        val raw = getZeaPrefs(context.applicationContext)
            .getString(KEY_SCHEDULE_OWNERSHIP, null) ?: return emptyMap()
        return decodeOwnership(raw)
    }

    private fun saveOwnership(
        context: Context,
        ownership: Map<String, Map<String, ZeaScheduleOwnershipRecord>>
    ): Boolean = getZeaPrefs(context.applicationContext)
        .edit()
        .putString(KEY_SCHEDULE_OWNERSHIP, encodeOwnership(ownership))
        .commit()

    private fun recordOwnership(
        context: Context,
        scheduleId: String,
        packageName: String,
        record: ZeaScheduleOwnershipRecord
    ) {
        val all = loadOwnership(context).toMutableMap()
        val perSchedule = (all[scheduleId] ?: emptyMap()).toMutableMap()
        perSchedule[packageName] = record
        all[scheduleId] = perSchedule
        saveOwnership(context, all)
    }

    private fun ownershipFor(
        context: Context,
        scheduleId: String,
        packageName: String
    ): ZeaScheduleOwnershipRecord? = loadOwnership(context)[scheduleId]?.get(packageName)

    private fun clearOwnership(context: Context, scheduleId: String, packageName: String) {
        val all = loadOwnership(context).toMutableMap()
        val perSchedule = (all[scheduleId] ?: return).toMutableMap()
        perSchedule.remove(packageName)
        if (perSchedule.isEmpty()) all.remove(scheduleId) else all[scheduleId] = perSchedule
        saveOwnership(context, all)
    }

    private fun clearOwnershipForSchedule(context: Context, scheduleId: String) {
        val all = loadOwnership(context).toMutableMap()
        if (all.remove(scheduleId) != null) saveOwnership(context, all)
    }

    suspend fun load(context: Context): List<ZeaSchedule> = withContext(Dispatchers.IO) {
        val raw = getZeaPrefs(context.applicationContext)
            .getString(KEY_SCHEDULES, null) ?: return@withContext emptyList()
        decode(raw)
    }

    private suspend fun save(context: Context, schedules: List<ZeaSchedule>): Boolean =
        withContext(Dispatchers.IO) {
            getZeaPrefs(context.applicationContext)
                .edit()
                .putString(KEY_SCHEDULES, encode(schedules))
                .commit()
        }

    suspend fun createSchedule(
        context: Context,
        name: String,
        kind: ZeaScheduleKind,
        daysOfWeek: List<Int>,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        targetGroupId: String?,
        targetPackages: List<String>,
        oneTimeStartEpochMillis: Long
    ): ZeaSchedule? {
        val cleanName = name.trim()
        val schedule = ZeaSchedule(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            kind = kind,
            daysOfWeek = daysOfWeek.distinct(),
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = endMinuteOfDay,
            targetGroupId = targetGroupId,
            targetPackages = targetPackages.distinct(),
            oneTimeStartEpochMillis = oneTimeStartEpochMillis,
            enabled = true,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        // zeaScheduleIsValid rejects blank/oversized names, out-of-range
        // minutes, empty targets and CUSTOM_DAYS with zero selected days.
        if (!zeaScheduleIsValid(schedule)) return null
        val updated = load(context) + schedule
        return if (save(context, updated)) {
            // Honest status: if the engine cannot arm the required alarms, the
            // schedule is persisted but reported as NOT successfully activated.
            val armFailures = rearmWithResult(context)
            if (armFailures > 0) {
                ZeaActivityLog.record(
                    context,
                    ZeaActivityEventType.PROTECTION_FAILURE,
                    schedule.name,
                    "schedule saved but $armFailures alarm(s) could not be armed",
                    ZeaActivityResult.FAILURE
                )
                null
            } else {
                schedule
            }
        } else {
            null
        }
    }

    suspend fun updateSchedule(context: Context, schedule: ZeaSchedule): Boolean {
        if (!zeaScheduleIsValid(schedule)) return false
        val updated = load(context).map { existing ->
            if (existing.id == schedule.id) schedule else existing
        }
        return if (save(context, updated)) {
            val armFailures = rearmWithResult(context)
            if (armFailures > 0) {
                ZeaActivityLog.record(
                    context,
                    ZeaActivityEventType.PROTECTION_FAILURE,
                    schedule.name,
                    "schedule updated but $armFailures alarm(s) could not be armed",
                    ZeaActivityResult.FAILURE
                )
                false
            } else {
                true
            }
        } else {
            false
        }
    }

    suspend fun deleteSchedule(context: Context, scheduleId: String): Boolean {
        val updated = load(context).filterNot { it.id == scheduleId }
        return if (save(context, updated)) {
            cancelAlarms(context, scheduleId)
            clearOwnershipForSchedule(context, scheduleId)
            true
        } else {
            false
        }
    }

    suspend fun setEnabled(context: Context, scheduleId: String, enabled: Boolean): Boolean {
        val schedule = load(context).firstOrNull { it.id == scheduleId } ?: return false
        return updateSchedule(context, schedule.copy(enabled = enabled))
    }

    /**
     * Rearms the two-phase alarm pair for every schedule.
     *
     * When the device booted (or the time changed) DURING a schedule's active
     * window, the OLD code relied on the user waiting for the next day's start
     * alarm — leaving protected apps visible until then. This rearm reconciles
     * safely: it re-applies hide (idempotent) and arms ONLY the END of the
     * current window, instead of clobbering it with a next-cycle START/END pair.
     */
    suspend fun rearm(context: Context) {
        rearmWithResult(context)
    }

    /**
     * Same as [rearm] but returns the number of alarms that could not be armed,
     * so create/update callers can honestly report a degraded schedule.
     */
    suspend fun rearmWithResult(context: Context): Int {
        val schedules = load(context)
        var alarmFailures = 0
        for (schedule in schedules) {
            if (!schedule.enabled) {
                cancelAlarms(context, schedule.id)
                continue
            }
            val now = System.currentTimeMillis()
            // Prune stale targets first so dead packages can never be armed.
            val liveTargets = resolveInstalledTargets(context, schedule)
            if (liveTargets.size != resolveTargets(context, schedule).size) {
                val stalePackages = resolveTargets(context, schedule) - liveTargets.toSet()
                stalePackages.forEach { pruneTargetsForPackage(context, it) }
            }
            val activeEnd = zeaScheduleActiveWindow(schedule, now)
            if (activeEnd != null) {
                // Still within the active window: the START alarm already
                // passed (or was missed by reboot/time-change). Reconcile the
                // intended current state — re-apply hide (idempotent) so a
                // device reboot inside the window does not leave apps visible
                // until the next day's START.
                for (packageName in liveTargets) {
                    val app = zeaManagedAppFromPackage(context, packageName) ?: continue
                    if (app.hideMode == ZeaHideMode.VISIBLE) {
                        ZeaAppHideService.hideApp(context, app)
                    }
                }
                // The old START PendingIntent belongs to a previous cycle;
                // cancel it so the engine only waits for the CURRENT END.
                cancelAlarmPhase(context, schedule.id, PHASE_START)
                if (!armAlarm(context, schedule.id, PHASE_END, activeEnd)) alarmFailures++
            } else {
                val nextStart = zeaScheduleNextRun(schedule, now)
                if (nextStart == null) {
                    cancelAlarms(context, schedule.id)
                    continue
                }
                val end = zeaScheduleEndAfter(schedule, nextStart)
                if (!armAlarm(context, schedule.id, PHASE_START, nextStart)) alarmFailures++
                if (!armAlarm(context, schedule.id, PHASE_END, end)) alarmFailures++
            }
        }
        if (alarmFailures > 0) {
            ZeaActivityLog.record(
                context,
                ZeaActivityEventType.PROTECTION_FAILURE,
                "schedule rearm",
                "$alarmFailures alarm(s) could not be armed; schedule behavior is degraded",
                ZeaActivityResult.FAILURE
            )
        }
        return alarmFailures
    }

    /**
     * Executes a fired start/end phase through the verified engines.
     *
     * Critical invariant: after START, only the END of THIS cycle is armed —
     * rearm() must NOT be called here, because rearm-ing the next cycle's
     * END PendingIntent would destroy the pending END alarm. The next cycle's
     * START/END pair is armed by rearm() when the current END fires.
     */
    suspend fun onFire(
        context: Context,
        scheduleId: String,
        phase: String
    ) {
        val schedule = load(context).firstOrNull { it.id == scheduleId } ?: return
        if (!schedule.enabled) {
            cancelAlarms(context, schedule.id)
            return
        }
        // Stale targets: drop any package that is no longer installed so the
        // engine can never block on a ghost.
        val raw = resolveTargets(context, schedule)
        val liveTargets = resolveInstalledTargets(context, schedule)
        (raw - liveTargets.toSet()).forEach { pruneTargetsForPackage(context, it) }

        val now = System.currentTimeMillis()
        val plan = zeaScheduleFirePlan(schedule, phase, now)

        var succeeded = 0
        var failed = 0
        var keptHidden = 0
        var skipped = 0
        if (phase == PHASE_START) {
            if (plan.action == ZeaScheduleAction.SKIP) {
                // Delayed START: the window it belonged to has already ended.
                // Hiding now would keep the app hidden until tomorrow's END,
                // which is exactly the trap this reconciliation prevents.
                skipped = liveTargets.size
                val nextStart = zeaScheduleNextRun(schedule, now)
                if (nextStart != null) {
                    armAlarm(context, schedule.id, PHASE_START, nextStart)
                    armAlarm(context, schedule.id, PHASE_END, zeaScheduleEndAfter(schedule, nextStart))
                }
            } else {
                for (packageName in liveTargets) {
                    val app = zeaManagedAppFromPackage(context, packageName) ?: continue
                    // Capture pre-schedule state ONLY the first time this cycle
                    // claims the package; later reconciliation must not clobber
                    // the original manual state with schedule-produced state.
                    if (ownershipFor(context, schedule.id, packageName) == null) {
                        recordOwnership(
                            context,
                            schedule.id,
                            packageName,
                            ZeaScheduleOwnershipRecord(
                                previousMode = app.hideMode,
                                previousTimedEndEpochMillis = app.hiddenUntilEpochMillis
                            )
                        )
                    }
                    val outcome = ZeaAppHideService.hideApp(context, app)
                    if (outcome.success) succeeded++ else failed++
                }
                // Arm ONLY the current cycle's END. Calling rearm() here would
                // compute tomorrow's END and silently replace today's pending END.
                val end = if (plan.endEpochMillis > now) plan.endEpochMillis
                else zeaScheduleEndAfter(schedule, now)
                val armed = armAlarm(context, schedule.id, PHASE_END, end)
                if (!armed) {
                    ZeaActivityLog.record(
                        context,
                        ZeaActivityEventType.PROTECTION_FAILURE,
                        schedule.name,
                        "end-of-window alarm could not be armed; app may stay hidden until repaired",
                        ZeaActivityResult.FAILURE
                    )
                }
            }
        } else {
            for (packageName in liveTargets) {
                val prior = ownershipFor(context, schedule.id, packageName)
                val overlapped = isStillOwnedByOtherActiveSchedule(context, schedule, packageName)
                when (zeaScheduleEndPlan(prior, overlapped, now)) {
                    ZeaScheduleEndAction.UNHIDE -> {
                        val outcome = ZeaAppHideService.unhideApp(context, packageName)
                        if (outcome.success) succeeded++ else failed++
                        clearOwnership(context, schedule.id, packageName)
                    }
                    ZeaScheduleEndAction.RESTORE_HIDDEN -> {
                        // Manually hidden before START: the schedule never owned
                        // this state; leave it hidden and release the claim.
                        keptHidden++
                        clearOwnership(context, schedule.id, packageName)
                    }
                    ZeaScheduleEndAction.RESTORE_TIMED -> {
                        val app = zeaManagedAppFromPackage(context, packageName) ?: continue
                        val end = prior?.previousTimedEndEpochMillis ?: 0L
                        val label = "until ${zeaSnapshotLabel(end)}"
                        val outcome = ZeaAppHideService.hideAppForTime(
                            context,
                            app,
                            ZeaTimedHideRequest(label = label, endEpochMillis = end)
                        )
                        if (outcome.success) succeeded++ else failed++
                        clearOwnership(context, schedule.id, packageName)
                    }
                    ZeaScheduleEndAction.SKIP -> {
                        // Another active schedule still requires this app hidden;
                        // keep it protected instead of blindly unhiding.
                        keptHidden++
                    }
                }
            }
        }

        val summary = buildString {
            append("$phase: $succeeded succeeded, $failed failed")
            if (keptHidden > 0) append(", $keptHidden kept hidden (overlap/prior state)")
            if (skipped > 0) append(", $skipped skipped (delayed start, window already over)")
        }
        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.SCHEDULE_FIRED,
            schedule.name,
            summary,
            when {
                failed == 0 -> ZeaActivityResult.SUCCESS
                succeeded == 0 && keptHidden == 0 -> ZeaActivityResult.FAILURE
                else -> ZeaActivityResult.PARTIAL
            }
        )

        if (phase == PHASE_END) {
            // The still-needed END has completed; ONE_TIME now disables itself
            // (disabling at START would leave the app hidden forever), and
            // recurring schedules arm the NEXT cycle only here.
            if (schedule.kind == ZeaScheduleKind.ONE_TIME) {
                updateSchedule(context, schedule.copy(enabled = false))
            } else {
                rearm(context)
            }
        }
    }

    private suspend fun resolveInstalledTargets(
        context: Context,
        schedule: ZeaSchedule
    ): List<String> = withContext(Dispatchers.IO) {
        val installed = ZeaAppCatalog.loadManagedApps(context)
            .map { it.packageName }
            .toSet()
        resolveTargets(context, schedule).filter { it in installed }
    }

    /** True when another ACTIVE schedule still claims this package right now. */
    private suspend fun isStillOwnedByOtherActiveSchedule(
        context: Context,
        endingSchedule: ZeaSchedule,
        packageName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        load(context).any { other ->
            other.id != endingSchedule.id &&
                    other.enabled &&
                    zeaScheduleActiveWindow(other, now) != null &&
                    resolveTargets(context, other).contains(packageName)
        }
    }

    private suspend fun resolveTargets(
        context: Context,
        schedule: ZeaSchedule
    ): List<String> {
        val groupTargets = schedule.targetGroupId?.let { groupId ->
            ZeaGroups.load(context).firstOrNull { it.id == groupId }?.memberPackages
        } ?: emptyList()
        return (groupTargets + schedule.targetPackages).distinct()
    }

    suspend fun pruneTargetsForGroup(context: Context, groupId: String): Boolean {
        val before = load(context)
        val prunedIds = before.filter { it.targetGroupId == groupId }.map { it.id }
        val updated = before.filterNot { it.targetGroupId == groupId }
        return if (updated.size != before.size) {
            val saved = save(context, updated)
            if (saved) {
                // Dead schedules are cancelled outright; survivors rearm.
                prunedIds.forEach { cancelAlarms(context, it) }
                rearm(context)
            }
            saved
        } else {
            true
        }
    }

    suspend fun pruneTargetsForPackage(context: Context, packageName: String): Boolean {
        val before = load(context)
        val updated = before.map { schedule ->
            schedule.copy(targetPackages = schedule.targetPackages - packageName)
        }.filter { schedule ->
            schedule.targetGroupId != null || schedule.targetPackages.isNotEmpty()
        }
        return if (updated != before) {
            val saved = save(context, updated)
            if (saved) {
                // A schedule that lost its last target is now dead; cancel its
                // alarms so it cannot still fire into a nonexistent target.
                val killed = before.map { it.id } - updated.map { it.id }.toSet()
                killed.forEach { cancelAlarms(context, it) }
                rearm(context)
            }
            saved
        } else {
            true
        }
    }

    /**
     * Arms a single phase. Returns true when the alarm was actually armed, so
     * callers can honestly report a degraded schedule instead of pretending
     * the schedule was enabled when its alarm never registered.
     */
    private fun armAlarm(
        context: Context,
        scheduleId: String,
        phase: String,
        triggerAtMillis: Long
    ): Boolean {
        if (triggerAtMillis <= System.currentTimeMillis()) return false
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return false
        val pendingIntent = pendingIntentFor(appContext, scheduleId, phase)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            true
        } catch (error: SecurityException) {
            Log.w("ZeaSchedules", "exact schedule alarm denied for $scheduleId", error)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                true
            } catch (fallback: RuntimeException) {
                Log.e("ZeaSchedules", "schedule fallback alarm failed for $scheduleId", fallback)
                false
            }
        } catch (error: RuntimeException) {
            Log.e("ZeaSchedules", "schedule alarm failed for $scheduleId", error)
            false
        }
    }

    private fun cancelAlarmPhase(context: Context, scheduleId: String, phase: String) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarmManager.cancel(pendingIntentFor(appContext, scheduleId, phase))
        } catch (_: RuntimeException) {
            // Cancel is best-effort; stale alarms no-op when the schedule is gone.
        }
    }

    private fun cancelAlarms(context: Context, scheduleId: String) {
        cancelAlarmPhase(context, scheduleId, PHASE_START)
        cancelAlarmPhase(context, scheduleId, PHASE_END)
    }

    private fun pendingIntentFor(
        context: Context,
        scheduleId: String,
        phase: String
    ): PendingIntent {
        val intent = Intent(context, ZeaScheduleReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("zea-schedule://$scheduleId/$phase")
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_SCHEDULE_PHASE, phase)
        }
        return PendingIntent.getBroadcast(
            context,
            (scheduleId + phase).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun encodeOwnership(
        ownership: Map<String, Map<String, ZeaScheduleOwnershipRecord>>
    ): String {
        val root = JSONObject()
        ownership.forEach { (scheduleId, perSchedule) ->
            val pkgObj = JSONObject()
            perSchedule.forEach { (pkg, record) ->
                pkgObj.put(
                    pkg,
                    JSONObject()
                        .put("previousMode", record.previousMode.name)
                        .put("previousTimedEnd", record.previousTimedEndEpochMillis)
                )
            }
            root.put(scheduleId, pkgObj)
        }
        return root.toString()
    }

    private fun decodeOwnership(
        raw: String
    ): Map<String, Map<String, ZeaScheduleOwnershipRecord>> {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
        val result = mutableMapOf<String, Map<String, ZeaScheduleOwnershipRecord>>()
        val scheduleKeys = root.keys()
        while (scheduleKeys.hasNext()) {
            val scheduleId = scheduleKeys.next()
            val pkgObj = root.optJSONObject(scheduleId) ?: continue
            val perSchedule = mutableMapOf<String, ZeaScheduleOwnershipRecord>()
            val pkgKeys = pkgObj.keys()
            while (pkgKeys.hasNext()) {
                val pkg = pkgKeys.next()
                val recObj = pkgObj.optJSONObject(pkg) ?: continue
                val mode = runCatching {
                    ZeaHideMode.valueOf(recObj.optString("previousMode"))
                }.getOrNull() ?: continue
                perSchedule[pkg] = ZeaScheduleOwnershipRecord(
                    previousMode = mode,
                    previousTimedEndEpochMillis = recObj.optLong("previousTimedEnd")
                )
            }
            if (perSchedule.isNotEmpty()) result[scheduleId] = perSchedule
        }
        return result
    }

    private fun encode(schedules: List<ZeaSchedule>): String {
        val array = JSONArray()
        schedules.forEach { schedule ->
            val obj = JSONObject()
                .put("id", schedule.id)
                .put("name", schedule.name)
                .put("kind", schedule.kind.storageKey)
                .put("days", JSONArray(schedule.daysOfWeek))
                .put("startMinute", schedule.startMinuteOfDay)
                .put("endMinute", schedule.endMinuteOfDay)
                .put("groupId", schedule.targetGroupId)
                .put("packages", JSONArray(schedule.targetPackages))
                .put("oneTimeStart", schedule.oneTimeStartEpochMillis)
                .put("enabled", schedule.enabled)
                .put("createdAt", schedule.createdAtEpochMillis)
            array.put(obj)
        }
        return array.toString()
    }

    private fun decode(raw: String): List<ZeaSchedule> {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val schedules = mutableListOf<ZeaSchedule>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val days = mutableListOf<Int>()
            val daysArray = obj.optJSONArray("days")
            if (daysArray != null) {
                for (dayIndex in 0 until daysArray.length()) {
                    days += daysArray.optInt(dayIndex)
                }
            }
            val packages = mutableListOf<String>()
            val packagesArray = obj.optJSONArray("packages")
            if (packagesArray != null) {
                for (pkgIndex in 0 until packagesArray.length()) {
                    val value = packagesArray.optString(pkgIndex, "")
                    if (value.isNotBlank()) packages += value
                }
            }
            schedules += ZeaSchedule(
                id = obj.optString("id"),
                name = obj.optString("name"),
                kind = ZeaScheduleKind.fromStorageKey(obj.optString("kind")),
                daysOfWeek = days,
                startMinuteOfDay = obj.optInt("startMinute"),
                endMinuteOfDay = obj.optInt("endMinute"),
                targetGroupId = obj.optString("groupId").takeIf { it.isNotBlank() && it != "null" },
                targetPackages = packages,
                oneTimeStartEpochMillis = obj.optLong("oneTimeStart"),
                enabled = obj.optBoolean("enabled", true),
                createdAtEpochMillis = obj.optLong("createdAt")
            )
        }
        return schedules
    }
}
