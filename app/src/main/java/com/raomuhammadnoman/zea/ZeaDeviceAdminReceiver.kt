package com.raomuhammadnoman.zea

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log

class ZeaDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val result = ZeaDeviceOwnerController.reconcileHiddenState(
            context,
            "device admin enabled"
        )
        Log.i(ZEA_DEVICE_OWNER_LOG_TAG, "device admin enabled result=${result.message}")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        Log.i(
            ZEA_DEVICE_OWNER_LOG_TAG,
            "profile provisioning complete; deviceOwnerObserved=${dpm.isDeviceOwnerApp(context.packageName)}"
        )
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
        return "Disabling Zyro device management can leave private-app state inconsistent. Use Emergency Unhide + Pause first, confirm every private app is visible, then use the test-only recovery script on the dedicated test device."
    }
}
