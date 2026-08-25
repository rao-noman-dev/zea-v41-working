package com.raomuhammadnoman.zea

import android.app.admin.DeviceAdminService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android keeps a bound connection to this service while Zea is Device Owner.
 * The bound owner process registers package lifecycle broadcasts at runtime,
 * because Android 8.0+ does not deliver most implicit package broadcasts to
 * manifest receivers of apps targeting API 26 or higher.
 */
class ZeaDeviceOwnerKeepAliveService : DeviceAdminService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packageLifecycleReceiver = ZeaDeviceOwnerSafetyReceiver()
    private var packageLifecycleReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        registerPackageLifecycleReceiver()

        scope.launch {
            if (!ZeaDeviceOwnerController.isDeviceOwner(this@ZeaDeviceOwnerKeepAliveService)) {
                return@launch
            }

            val uninstallProtection = ZeaDeviceOwnerController.reconcileUninstallProtection(
                this@ZeaDeviceOwnerKeepAliveService,
                "DeviceAdminService owner-process startup"
            )
            Log.i(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "owner process uninstall-protection reconciliation success=${uninstallProtection.success}"
            )

            if (ZeaDeviceOwnerController.isProtectionPaused(this@ZeaDeviceOwnerKeepAliveService)) {
                return@launch
            }

            val activePackage = ZeaDeviceOwnerController.activePrivatePackage(
                this@ZeaDeviceOwnerKeepAliveService
            )
            if (activePackage.isNotBlank()) {
                val result = ZeaDeviceOwnerController.ensureProtectedState(
                    this@ZeaDeviceOwnerKeepAliveService,
                    activePackage
                )
                Log.i(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "owner process recovery protected-state package=$activePackage success=${result.success}"
                )
                if (result.success) {
                    ZeaDeviceOwnerController.clearActivePrivatePackage(
                        this@ZeaDeviceOwnerKeepAliveService
                    )
                }
            }

            val reconciliation = ZeaDeviceOwnerController.reconcileHiddenState(
                this@ZeaDeviceOwnerKeepAliveService,
                "DeviceAdminService owner-process recovery"
            )
            Log.i(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "owner process reconciliation success=${reconciliation.success}"
            )
        }
    }

    private fun registerPackageLifecycleReceiver() {
        if (packageLifecycleReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                packageLifecycleReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageLifecycleReceiver, filter)
        }

        packageLifecycleReceiverRegistered = true
        Log.i(
            ZEA_DEVICE_OWNER_LOG_TAG,
            "runtime package lifecycle receiver registered"
        )
    }

    override fun onDestroy() {
        if (packageLifecycleReceiverRegistered) {
            try {
                unregisterReceiver(packageLifecycleReceiver)
            } catch (error: IllegalArgumentException) {
                Log.w(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "runtime package lifecycle receiver was already unregistered",
                    error
                )
            } finally {
                packageLifecycleReceiverRegistered = false
            }
        }
        scope.cancel()
        super.onDestroy()
    }
}
