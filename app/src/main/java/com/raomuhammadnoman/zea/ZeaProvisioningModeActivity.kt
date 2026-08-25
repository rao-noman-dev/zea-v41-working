package com.raomuhammadnoman.zea

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Android 12+ admin-integrated provisioning mode bridge.
 *
 * Point-30 requires a fully managed device. Managed Provisioning normally supplies
 * EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES; some OEM flows may omit or supply an empty
 * list. In that compatibility case Zea follows the platform/TestDPC pattern and returns the
 * fully-managed mode directly. If a non-empty list is supplied and fully-managed mode is not
 * offered, Zea still fails closed and never falls back to a managed-profile mode.
 */
class ZeaProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != DevicePolicyManager.ACTION_GET_PROVISIONING_MODE) {
            Log.w(TAG, "Rejected unexpected provisioning action=${intent?.action}")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val allowedModes = intent.getIntegerArrayListExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
        )

        if (!allowedModes.isNullOrEmpty() &&
            !allowedModes.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        ) {
            Log.w(TAG, "Fully managed device mode was explicitly not offered; failing closed")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        if (allowedModes.isNullOrEmpty()) {
            Log.i(TAG, "Allowed provisioning modes absent/empty; using fully managed compatibility fallback")
        }

        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        )
        setResult(RESULT_OK, result)
        finish()
    }

    private companion object {
        const val TAG = "ZeaProvisioningMode"
    }
}
