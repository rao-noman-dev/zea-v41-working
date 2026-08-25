package com.raomuhammadnoman.zea

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.util.Log

/**
 * Android 12+ admin policy compliance endpoint used by Managed Provisioning.
 *
 * This endpoint intentionally performs no private-app or policy mutation. The provisioning
 * framework owns Device Owner establishment; Zea records the observed owner state for diagnostics
 * but does not cancel the compliance handshake based on a timing-sensitive local owner-state
 * query. Device Owner status is verified independently after provisioning completes.
 */
class ZeaAdminPolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE) {
            Log.w(TAG, "Rejected unexpected compliance action=${intent?.action}")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val dpm = getSystemService(DevicePolicyManager::class.java)
        Log.i(TAG, "Compliance endpoint invoked; deviceOwnerObserved=${dpm.isDeviceOwnerApp(packageName)}")

        // No setup mutation is required here. Returning RESULT_OK completes the Android 12+
        // admin-integrated compliance handshake; post-provisioning validation independently proves
        // Device Owner state before any Point-30 runtime testing is allowed.
        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val TAG = "ZeaAdminCompliance"
    }
}
