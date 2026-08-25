package com.raomuhammadnoman.zea

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives schedule start/end alarms and re-arms the schedule engine after
 * reboot, app update, or system time/timezone changes (alarms do not survive
 * those events and absolute fire times become invalid).
 */
class ZeaScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                val phase = intent.getStringExtra(EXTRA_SCHEDULE_PHASE) ?: return
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ZeaSchedules.onFire(context, scheduleId, phase)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ZeaSchedules.rearm(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val ACTION_FIRE = "com.raomuhammadnoman.zea.action.SCHEDULE_FIRED"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_SCHEDULE_PHASE = "schedule_phase"
    }
}
