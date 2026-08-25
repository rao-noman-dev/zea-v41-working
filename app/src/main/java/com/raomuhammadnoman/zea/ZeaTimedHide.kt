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

/**
 * The clock for Hide For Time.
 *
 * Hiding and restoring still go through [ZeaAppHideService]. This object only
 * records when a hide must end, wakes the app at that moment, and re-arms the
 * same alarms after a reboot.
 */
object ZeaTimedHide {
    const val ACTION_EXPIRED = "com.raomuhammadnoman.zea.action.TIMED_HIDE_EXPIRED"
    const val EXTRA_PACKAGE_NAME = "packageName"

    private const val EXPIRY_RECOVERY_RETRY_MILLIS = 60_000L

    suspend fun restoreExpiredHides(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val due = loadTimedHides(appContext).filter { record ->
            record.hiddenUntilEpochMillis <= now
        }

        val failedRecords = mutableListOf<ZeaTimedHideRecord>()
        due.forEach { record ->
            val outcome = ZeaAppHideService.unhideApp(appContext, record.packageName)
            if (!outcome.success) {
                failedRecords.add(record)
            }
            Log.i(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "timed hide expiry for ${record.packageName}: ${outcome.message}"
            )
        }

        // A transient firmware or binder hiccup during the sweep must not
        // leave an app stuck hidden past its promised window, so every
        // failure gets one immediate retry before a delayed alarm is armed.
        val stillFailed = mutableListOf<ZeaTimedHideRecord>()
        failedRecords.forEach { record ->
            val outcome = ZeaAppHideService.unhideApp(appContext, record.packageName)
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "timed hide expiry retry for ${record.packageName}: ${outcome.message}"
            )
            if (!outcome.success) {
                stillFailed.add(record)
            }
        }

        stillFailed.forEach { record ->
            val retryAt = System.currentTimeMillis() + EXPIRY_RECOVERY_RETRY_MILLIS
            val scheduled = scheduleAt(appContext, record.packageName, retryAt)
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "timed hide delayed recovery package=${record.packageName} scheduled=$scheduled retryAt=$retryAt"
            )
        }

        withContext(Dispatchers.IO) {
            ZeaAppHideService.sweepOrphanedHiddenApps(appContext, force = stillFailed.isNotEmpty())
        }
    }

    fun rearmActiveAlarms(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()

        loadTimedHides(appContext).forEach { record ->
            if (record.hiddenUntilEpochMillis > now) {
                schedule(appContext, record)
            } else {
                // Expired rows are retained only when restoration has not yet
                // succeeded. Ensure they have a future recovery wake-up.
                scheduleAt(
                    appContext,
                    record.packageName,
                    now + EXPIRY_RECOVERY_RETRY_MILLIS
                )
            }
        }
    }

    /** Returns true only when Android accepted an alarm for this timer. */
    fun schedule(context: Context, record: ZeaTimedHideRecord): Boolean {
        if (record.hiddenUntilEpochMillis <= System.currentTimeMillis()) {
            return false
        }
        return scheduleAt(context.applicationContext, record.packageName, record.hiddenUntilEpochMillis)
    }

    private fun scheduleAt(
        context: Context,
        packageName: String,
        triggerAtMillis: Long
    ): Boolean {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return false
        val pendingIntent = pendingIntentFor(appContext, packageName)
        if (triggerAtMillis <= System.currentTimeMillis()) return false

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
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "exact timed-hide alarm denied for $packageName; using inexact",
                error
            )
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                true
            } catch (fallbackError: RuntimeException) {
                Log.e(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "timed-hide fallback alarm failed for $packageName",
                    fallbackError
                )
                false
            }
        } catch (error: RuntimeException) {
            Log.e(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "timed-hide alarm scheduling failed for $packageName",
                error
            )
            false
        }
    }

    fun cancel(context: Context, packageName: String) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarmManager.cancel(pendingIntentFor(appContext, packageName))
        } catch (error: RuntimeException) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "timed-hide alarm cancel failed for $packageName",
                error
            )
        }
    }

    suspend fun onBootOrPackageReplaced(context: Context) {
        restoreExpiredHides(context)
        rearmActiveAlarms(context)
    }

    suspend fun onExpiryAlarm(context: Context, packageName: String) {
        val appContext = context.applicationContext
        val outcome = ZeaAppHideService.unhideApp(appContext, packageName)
        Log.i(
            ZEA_DEVICE_OWNER_LOG_TAG,
            "timed hide alarm for $packageName: ${outcome.message}"
        )
        restoreExpiredHides(appContext)
        rearmActiveAlarms(appContext)
    }

    private fun pendingIntentFor(
        context: Context,
        packageName: String
    ): PendingIntent {
        val intent = Intent(context, ZeaDeviceOwnerSafetyReceiver::class.java).apply {
            action = ACTION_EXPIRED
            data = Uri.parse("zea-timed-hide://$packageName")
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }

        return PendingIntent.getBroadcast(
            context,
            packageName.lowercase().hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
