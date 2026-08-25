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
 * Phase 3 recurring/scheduled hiding engine.
 *
 * Schedules persist in SharedPreferences; the engine arms one alarm per
 * schedule (the nearest pending start/end) and re-arms on boot/time-change.
 * Executions re-use the same verified [ZeaAppHideService] transactions.
 */
object ZeaSchedules {
    private const val KEY_SCHEDULES = "app_schedules_v1"
    private const val ACTION_FIRE = "com.raomuhammadnoman.zea.action.SCHEDULE_FIRED"
    private const val EXTRA_SCHEDULE_ID = "schedule_id"
    private const val EXTRA_SCHEDULE_PHASE = "schedule_phase"
    private const val PHASE_START = "start"
    private const val PHASE_END = "end"

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
        if (cleanName.isEmpty() || cleanName.length > 60) return null
        if (startMinuteOfDay !in 0..1439 || endMinuteOfDay !in 0..1439) return null
        if (targetGroupId == null && targetPackages.isEmpty()) return null
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
        val updated = load(context) + schedule
        return if (save(context, updated)) {
            rearm(context)
            schedule
        } else {
            null
        }
    }

    suspend fun updateSchedule(context: Context, schedule: ZeaSchedule): Boolean {
        val updated = load(context).map { existing ->
            if (existing.id == schedule.id) schedule else existing
        }
        return if (save(context, updated)) {
            rearm(context)
            true
        } else {
            false
        }
    }

    suspend fun deleteSchedule(context: Context, scheduleId: String): Boolean {
        val updated = load(context).filterNot { it.id == scheduleId }
        return if (save(context, updated)) {
            cancelAlarms(context, scheduleId)
            true
        } else {
            false
        }
    }

    suspend fun setEnabled(context: Context, scheduleId: String, enabled: Boolean): Boolean {
        val schedule = load(context).firstOrNull { it.id == scheduleId } ?: return false
        return updateSchedule(context, schedule.copy(enabled = enabled))
    }

    /** Rearms the single nearest pending alarm for every schedule. */
    suspend fun rearm(context: Context) {
        val schedules = load(context)
        schedules.forEach { schedule ->
            val nextStart = zeaScheduleNextRun(schedule, System.currentTimeMillis())
            if (nextStart == null) {
                cancelAlarms(context, schedule.id)
                return@forEach
            }
            val end = zeaScheduleEndAfter(schedule, nextStart)
            armAlarm(context, schedule.id, PHASE_START, nextStart)
            armAlarm(context, schedule.id, PHASE_END, end)
        }
    }

    /** Executes a fired start/end phase through the verified engines. */
    suspend fun onFire(
        context: Context,
        scheduleId: String,
        phase: String
    ) {
        val schedule = load(context).firstOrNull { it.id == scheduleId } ?: return
        val targetPackages = resolveTargets(context, schedule)
        if (targetPackages.isEmpty()) return

        var succeeded = 0
        var failed = 0
        if (phase == PHASE_START) {
            for (packageName in targetPackages) {
                val app = zeaManagedAppFromPackage(context, packageName) ?: continue
                val outcome = ZeaAppHideService.hideApp(context, app)
                if (outcome.success) succeeded++ else failed++
            }
        } else {
            for (packageName in targetPackages) {
                val outcome = ZeaAppHideService.unhideApp(context, packageName)
                if (outcome.success) succeeded++ else failed++
            }
        }

        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.SCHEDULE_FIRED,
            schedule.name,
            "$phase: $succeeded succeeded, $failed failed",
            when {
                failed == 0 -> ZeaActivityResult.SUCCESS
                succeeded == 0 -> ZeaActivityResult.FAILURE
                else -> ZeaActivityResult.PARTIAL
            }
        )

        // One-time schedules disable themselves after the first start fire.
        if (schedule.kind == ZeaScheduleKind.ONE_TIME && phase == PHASE_START) {
            updateSchedule(context, schedule.copy(enabled = false))
            return
        }
        rearm(context)
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
        val updated = load(context).filterNot { it.targetGroupId == groupId }
        return if (updated.size != load(context).size) {
            save(context, updated)
        } else {
            true
        }
    }

    suspend fun pruneTargetsForPackage(context: Context, packageName: String): Boolean {
        val updated = load(context).map { schedule ->
            schedule.copy(targetPackages = schedule.targetPackages - packageName)
        }.filter { schedule ->
            schedule.targetGroupId != null || schedule.targetPackages.isNotEmpty()
        }
        return if (updated != load(context)) {
            save(context, updated)
        } else {
            true
        }
    }

    private fun armAlarm(
        context: Context,
        scheduleId: String,
        phase: String,
        triggerAtMillis: Long
    ) {
        if (triggerAtMillis <= System.currentTimeMillis()) return
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = pendingIntentFor(appContext, scheduleId, phase)
        try {
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
        } catch (error: SecurityException) {
            Log.w("ZeaSchedules", "exact schedule alarm denied for $scheduleId", error)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } catch (fallback: RuntimeException) {
                Log.e("ZeaSchedules", "schedule fallback alarm failed for $scheduleId", fallback)
            }
        } catch (error: RuntimeException) {
            Log.e("ZeaSchedules", "schedule alarm failed for $scheduleId", error)
        }
    }

    private fun cancelAlarms(context: Context, scheduleId: String) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        listOf(PHASE_START, PHASE_END).forEach { phase ->
            try {
                alarmManager.cancel(pendingIntentFor(appContext, scheduleId, phase))
            } catch (_: RuntimeException) {
                // Cancel is best-effort; stale alarms no-op when the schedule is gone.
            }
        }
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
