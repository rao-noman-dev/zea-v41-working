package com.raomuhammadnoman.zea

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZeaDeviceOwnerSafetyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val action = intent.action.orEmpty()
                val result = when (action) {
                    ZeaTimedHide.ACTION_EXPIRED -> {
                        val packageName = intent.getStringExtra(ZeaTimedHide.EXTRA_PACKAGE_NAME)
                            ?.trim()
                            .orEmpty()
                            .ifBlank {
                                intent.data?.schemeSpecificPart.orEmpty().trim()
                            }
                        if (packageName.isNotBlank()) {
                            ZeaTimedHide.onExpiryAlarm(appContext, packageName)
                        } else {
                            ZeaTimedHide.restoreExpiredHides(appContext)
                        }
                        ZeaDeviceOwnerController.reconcileHiddenState(
                            context = appContext,
                            reason = action
                        )
                    }
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        ZeaTimedHide.onBootOrPackageReplaced(appContext)
                        ZeaDeviceOwnerController.reconcileHiddenState(
                            context = appContext,
                            reason = action
                        )
                    }
                    Intent.ACTION_PACKAGE_ADDED,
                    Intent.ACTION_PACKAGE_REPLACED,
                    Intent.ACTION_PACKAGE_REMOVED,
                    Intent.ACTION_PACKAGE_CHANGED -> {
                        val packageName = intent.data
                            ?.schemeSpecificPart
                            .orEmpty()
                            .trim()
                        ZeaDeviceOwnerController.reconcileProtectedPackageLifecycle(
                            context = appContext,
                            packageName = packageName,
                            eventAction = action,
                            packageReplacing = intent.getBooleanExtra(
                                Intent.EXTRA_REPLACING,
                                false
                            )
                        )
                    }
                    else -> ZeaDeviceOwnerController.reconcileHiddenState(
                        context = appContext,
                        reason = action.ifBlank { "safety receiver" }
                    )
                }
                Log.i(ZEA_DEVICE_OWNER_LOG_TAG, "safety reconciliation: ${result.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
