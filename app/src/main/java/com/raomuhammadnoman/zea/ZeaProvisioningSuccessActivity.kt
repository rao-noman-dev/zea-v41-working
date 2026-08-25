package com.raomuhammadnoman.zea

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.util.Log

/**
 * Android O+ provisioning-success endpoint.
 *
 * Managed Provisioning sends ACTION_PROVISIONING_SUCCESSFUL to the new owner when provisioning
 * completes. Point-30 keeps this endpoint deliberately side-effect free: it only records the
 * observed owner state and finishes immediately. Runtime owner state is verified independently
 * before any protected-app testing is allowed.
 */
class ZeaProvisioningSuccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != DevicePolicyManager.ACTION_PROVISIONING_SUCCESSFUL) {
            Log.w(TAG, "Rejected unexpected provisioning-success action=${intent?.action}")
            finish()
            return
        }

        val dpm = getSystemService(DevicePolicyManager::class.java)
        Log.i(TAG, "Provisioning-success endpoint invoked; deviceOwnerObserved=${dpm.isDeviceOwnerApp(packageName)}")
        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val TAG = "ZeaProvisioningSuccess"
    }
}
