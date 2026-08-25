package com.raomuhammadnoman.zea

import android.app.Instrumentation
import android.app.admin.DevicePolicyManager
import android.os.Bundle

class Point27R5OwnerSuspensionFeasibilityInstrumentation : Instrumentation() {
    private var inputArguments: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        inputArguments = Bundle(arguments ?: Bundle())
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            val targetPackage = inputArguments.getString("target_package").orEmpty().trim()
            val requestedState = inputArguments.getString("requested_state").orEmpty().trim().uppercase()
            require(targetPackage.matches(Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$"))) {
                "target_package is missing or malformed"
            }
            require(targetPackage != targetContext.packageName) { "Zea cannot be the suspension target" }
            require(requestedState == "SUSPEND" || requestedState == "UNSUSPEND") {
                "requested_state must be SUSPEND or UNSUSPEND"
            }

            val context = targetContext.applicationContext
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
                ?: error("DevicePolicyManager unavailable")
            val admin = ZeaDeviceOwnerController.adminComponent(context)
            val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
            val isAdminActive = dpm.isAdminActive(admin)
            require(isDeviceOwner) { "Zea is not Device Owner" }
            require(isAdminActive) { "Zea admin component is not active" }

            // Hidden private apps may be invisible to ordinary PackageManager queries from Zea.
            // DevicePolicyManager.setPackagesSuspended() is the owner-authoritative mutation API;
            // its returned package list is the contract for packages whose requested state was
            // not applied. Host-side dumpsys verification proves the resulting suspended state.
            val desired = requestedState == "SUSPEND"
            val failures = dpm.setPackagesSuspended(admin, arrayOf(targetPackage), desired)
            val failureContainsTarget = failures.any { it.equals(targetPackage, ignoreCase = true) }
            val requestAccepted = !failureContainsTarget

            results.putString("SCHEMA_VERSION", "ZEA_POINT27_R5_DO_SUSPENSION_FEASIBILITY_V2_HIDDEN_SAFE")
            results.putString("PRODUCER_PACKAGE", context.packageName)
            results.putString("TARGET_PACKAGE", targetPackage)
            results.putString("REQUESTED_STATE", requestedState)
            results.putString("ZEA_IS_DEVICE_OWNER", if (isDeviceOwner) "YES" else "NO")
            results.putString("ZEA_ADMIN_ACTIVE", if (isAdminActive) "YES" else "NO")
            results.putString("PACKAGE_MANAGER_PRELOOKUP_PERFORMED", "NO")
            results.putString("DPM_IS_PACKAGE_SUSPENDED_QUERY_PERFORMED", "NO")
            results.putString("DPM_FAILURE_COUNT", failures.size.toString())
            results.putString("DPM_FAILURE_CONTAINS_TARGET", if (failureContainsTarget) "YES" else "NO")
            results.putString("OWNER_POLICY_REQUEST_ACCEPTED", if (requestAccepted) "YES" else "NO")
            results.putString("HOST_SUSPENDED_STATE_VERIFICATION_REQUIRED", "YES")
            results.putString("APP_OR_UI_LAUNCH_PERFORMED", "NO")
            results.putString("SOURCE_MUTATION_PERFORMED_BY_INSTRUMENTATION", "NO")
            results.putString("RESULT", if (requestAccepted) "PASS_REQUEST_ACCEPTED_HOST_VERIFY_REQUIRED" else "FAIL_REQUEST_REJECTED")
            finish(if (requestAccepted) 0 else 2, results)
        } catch (error: Throwable) {
            results.putString("SCHEMA_VERSION", "ZEA_POINT27_R5_DO_SUSPENSION_FEASIBILITY_V2_HIDDEN_SAFE")
            results.putString("RESULT", "FAIL_EXCEPTION")
            results.putString("FAILURE_CLASS", error.javaClass.name.take(200))
            results.putString("FAILURE_MESSAGE", error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(500))
            results.putString("APP_OR_UI_LAUNCH_PERFORMED", "NO")
            finish(3, results)
        }
    }
}
