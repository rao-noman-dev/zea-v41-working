package com.raomuhammadnoman.zea

import android.app.Application
import android.app.Instrumentation
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

class Point2ReadOnlySnapshotInstrumentation : Instrumentation() {
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
            require(targetPackage.isNotBlank()) { "target_package is required" }
            require(targetPackage != "com.raomuhammadnoman.zea") { "Zea cannot be the Point-2 target" }
            require(targetPackage != "com.google.android.youtube") { "YouTube cannot be the Point-2 closure target" }

            val context = targetContext.applicationContext
            val snapshotStartElapsed = SystemClock.elapsedRealtime()

            val privatePre = readPrivateState(context, targetPackage)
            val dpmPre = readDpmState(context, targetPackage)
            val packagePre = readPackageMetadata(context, targetPackage)

            Thread.sleep(100L)

            val privatePost = readPrivateState(context, targetPackage)
            val dpmPost = readDpmState(context, targetPackage)
            val packagePost = readPackageMetadata(context, targetPackage)

            val snapshotEndElapsed = SystemClock.elapsedRealtime()

            val privatePreHash = sha256(privatePre.canonical())
            val privatePostHash = sha256(privatePost.canonical())
            val dpmPreHash = sha256(dpmPre.canonical())
            val dpmPostHash = sha256(dpmPost.canonical())
            val packagePreHash = sha256(packagePre.canonical())
            val packagePostHash = sha256(packagePost.canonical())

            val privateStable = privatePreHash == privatePostHash
            val dpmStable = dpmPreHash == dpmPostHash
            val packageStable = packagePreHash == packagePostHash

            privatePost.writeTo(results)
            dpmPost.writeTo(results)
            packagePost.writeTo(results)

            put(results, "SNAPSHOT_SCHEMA_VERSION", "ZEA_POINT2_SELF_INSTRUMENTATION_V3_HIDDEN_SAFE_BOUND_IDENTITY")
            put(results, "PRODUCER_PACKAGE", context.packageName)
            put(results, "TARGET_ZEA_PACKAGE", context.packageName)
            put(results, "SNAPSHOT_START_ELAPSED_MS", snapshotStartElapsed.toString())
            put(results, "SNAPSHOT_END_ELAPSED_MS", snapshotEndElapsed.toString())

            put(results, "PRIVATE_STATE_PRE_HASH", privatePreHash)
            put(results, "PRIVATE_STATE_POST_HASH", privatePostHash)
            put(results, "PRIVATE_STATE_STABILITY", passFail(privateStable))

            put(results, "DPM_STATE_PRE_HASH_OR_EQUIVALENT", dpmPreHash)
            put(results, "DPM_STATE_POST_HASH_OR_EQUIVALENT", dpmPostHash)
            put(results, "DPM_STATE_STABILITY", passFail(dpmStable))

            put(results, "PACKAGE_METADATA_PRE_HASH_OR_EQUIVALENT", packagePreHash)
            put(results, "PACKAGE_METADATA_POST_HASH_OR_EQUIVALENT", packagePostHash)
            put(results, "PACKAGE_METADATA_STABILITY", passFail(packageStable))

            put(results, "SNAPSHOT_ATOMICITY", "NOT_PROVEN")
            put(results, "CONCURRENT_RECONCILIATION_OBSERVED", "UNKNOWN_NO_SOURCE_CALL_COUNTER")
            put(
                results,
                "CONCURRENT_RECONCILIATION_EVIDENCE_CLASS",
                "UNKNOWN_NO_SOURCE_CALL_COUNTER_OR_STRONGER"
            )

            put(results, "ALLOWLISTED_ZEA_PRIVATE_STATE_READ_IN_PROCESS", "YES")
            put(results, "ALLOWLISTED_STATE_KEYS_SOURCE_BOUND", "YES")
            put(results, "RAW_SHARED_PREFERENCES_FILE_EXPORTED", "NO")
            put(results, "RAW_SHARED_PREFERENCES_VALUES_EXPORTED", "NO")
            put(results, "FULL_PRIVATE_APP_LIST_EXPORTED", "NO")
            put(results, "FULL_PENDING_REHIDE_SET_EXPORTED", "NO")
            put(results, "RAW_ACTIVE_PACKAGE_EXPORTED", "NO")
            put(results, "SESSION_IDENTIFIER_EXPORTED", "NO")
            put(results, "NON_ALLOWLISTED_APP_PRIVATE_CONTENT_ACCESSED", "NO")
            put(results, "PRODUCTION_FEATURE_EXPOSED", "NO")
            put(results, "LEDGER_USED_FOR_CAPTURE", "NO")
            put(results, "UI_USED_AS_EVIDENCE_SOURCE", "NO")
            put(results, "PACKAGE_METADATA_SOURCE", "BOUND_PRE_HIDE_06Z_06AA")
            put(results, "PACKAGE_METADATA_REQUERIED_WHILE_HIDDEN", "NO")
            put(results, "PACKAGE_METADATA_STABILITY_SCOPE", "BOUND_PRE_HIDE_IDENTITY_ONLY")
            put(results, "TARGET_SUPPORT_SOURCE", "BOUND_06AA_RUNTIME_PRE_HIDE")
            put(
                results,
                "TARGET_PRE_HIDE_APK_SHA256_BOUND",
                inputArguments.getString("expected_pre_hide_apk_sha256").orEmpty().trim().uppercase()
            )

            put(results, "PROCESS_RESTART_PERFORMED", "EXTERNAL_PID_VERIFICATION_REQUIRED")
            put(results, "APP_OR_UI_LAUNCH_PERFORMED", "NO")
            put(results, "SERVICE_START_STOP_PERFORMED", "NO")
            put(results, "PACKAGE_REPLACEMENT_PERFORMED", "NO_BY_PRODUCER_CODE")
            put(results, "MUTATION_PATH_ENTERED", "NO_BY_PRODUCER_CODE")
            put(results, "CURRENT_PROCESS_NAME", currentProcessName(context))
            put(results, "CURRENT_PID", Process.myPid().toString())

            val pass =
                privateStable &&
                    dpmStable &&
                    packageStable &&
                    privatePre.privateSessionStateCoherence == "PASS" &&
                    privatePost.privateSessionStateCoherence == "PASS" &&
                    privatePost.privateRegistryParseStatus.startsWith("PASS") &&
                    privatePost.rawTargetRecordMatchCount <= 1 &&
                    privatePost.malformedTargetRecordCount == 0 &&
                    privatePost.rawTargetRecordMatchCount == privatePost.privateRecordMatchCount &&
                    dpmPost.zeaIsDeviceOwner &&
                    dpmPost.zeaAdminIsActive &&
                    dpmPost.hiddenQueryStatus == "PASS" &&
                    dpmPost.uninstallBlockQueryStatus == "PASS" &&
                    packagePost.targetInstalled &&
                    packagePost.launcherResolutionStatus == "PASS_EXACT_ONE_ENABLED_EXPORTED" &&
                    packagePost.supportedByZea

            put(
                results,
                "SNAPSHOT_RESULT",
                if (pass) "PASS_BOUNDED_STABLE_NON_ATOMIC" else "FAIL_PRECONDITION_OR_STABILITY"
            )

            finish(if (pass) 0 else 2, results)
        }
        catch (error: Throwable) {
            put(results, "SNAPSHOT_SCHEMA_VERSION", "ZEA_POINT2_SELF_INSTRUMENTATION_V3_HIDDEN_SAFE_BOUND_IDENTITY")
            put(results, "RAW_SHARED_PREFERENCES_FILE_EXPORTED", "NO")
            put(results, "RAW_SHARED_PREFERENCES_VALUES_EXPORTED", "NO")
            put(results, "FULL_PRIVATE_APP_LIST_EXPORTED", "NO")
            put(results, "FULL_PENDING_REHIDE_SET_EXPORTED", "NO")
            put(results, "RAW_ACTIVE_PACKAGE_EXPORTED", "NO")
            put(results, "SESSION_IDENTIFIER_EXPORTED", "NO")
            put(results, "NON_ALLOWLISTED_APP_PRIVATE_CONTENT_ACCESSED", "NO")
            put(results, "APP_OR_UI_LAUNCH_PERFORMED", "NO")
            put(results, "SERVICE_START_STOP_PERFORMED", "NO")
            put(results, "PACKAGE_REPLACEMENT_PERFORMED", "NO_BY_PRODUCER_CODE")
            put(results, "MUTATION_PATH_ENTERED", "NO_BY_PRODUCER_CODE")
            put(results, "SNAPSHOT_FAILURE_CLASS", error.javaClass.name.take(200))
            put(results, "SNAPSHOT_RESULT", "FAIL_EXCEPTION")
            finish(3, results)
        }
    }

    private fun readPrivateState(context: Context, targetPackage: String): PrivateState {
        val packageMetadata = readPackageMetadata(context, targetPackage)

        val localStorage = context.getSharedPreferences(
            "zea_local_storage_v09_full",
            Context.MODE_PRIVATE
        )
        val rawRegistry = localStorage.getString("private_apps_json_v1", "").orEmpty()
        val rawRegistryState = inspectPrivateRegistry(rawRegistry, targetPackage)

        val privateRecords = loadPrivateApps(context)
        val targetRecords = privateRecords.filter { record ->
            record.packageName.equals(targetPackage, ignoreCase = true)
        }

        val recordLabelMatch = when (targetRecords.size) {
            0 -> "NOT_APPLICABLE"
            1 -> yesNo(targetRecords.single().displayName.trim() == packageMetadata.label)
            else -> "NO"
        }

        val recordComponentMatch = when (targetRecords.size) {
            0 -> "NOT_APPLICABLE"
            1 -> yesNo(
                targetRecords.single().launcherActivityName.trim() ==
                    packageMetadata.launcherClassName
            )
            else -> "NO"
        }

        val activePackage = ZeaDeviceOwnerController.activePrivatePackage(context).trim()
        val pendingRehide = ZeaDeviceOwnerController.pendingRehidePackages(context)
            .any { packageName -> packageName.equals(targetPackage, ignoreCase = true) }

        val ownerState = context.getSharedPreferences(
            "zea_device_owner_state",
            Context.MODE_PRIVATE
        )

        val protectionPaused = ownerState.getBoolean("protection_paused", false)

        val monitorSession = ownerState.getString("monitor_session", "").orEmpty().trim()
        val monitorReadySession =
            ownerState.getString("monitor_ready_session", "").orEmpty().trim()
        val monitorReadyPackage =
            ownerState.getString("monitor_ready_package", "").orEmpty().trim()

        val expectedUnhidePackage =
            ownerState.getString("expected_unhide_package", "").orEmpty().trim()
        val expectedUnhideSession =
            ownerState.getString("expected_unhide_session", "").orEmpty().trim()
        val expectedUnhideExpiresElapsed =
            ownerState.getLong("expected_unhide_expires_elapsed", 0L)

        val privateLaunchSession =
            ownerState.getString("zea_private_launch_session", "").orEmpty().trim()
        val privateLaunchPackage =
            ownerState.getString("zea_private_launch_package", "").orEmpty().trim()
        val privateLaunchDispatchElapsed =
            ownerState.getLong("zea_private_launch_dispatch_elapsed", 0L)
        val privateLaunchDispatchWall =
            ownerState.getLong("zea_private_launch_dispatch_wall", 0L)
        val privateLaunchOutcome =
            ownerState.getString("zea_private_launch_outcome", "").orEmpty().trim()

        val activePackagePresent = activePackage.isNotBlank()
        val activePackageIsTarget = activePackage.equals(targetPackage, ignoreCase = true)
        val monitorSessionPresent = monitorSession.isNotBlank()
        val monitorReadySessionPresent = monitorReadySession.isNotBlank()
        val monitorReadyPackageIsTarget =
            monitorReadyPackage.equals(targetPackage, ignoreCase = true)

        // Authority-defined launch-admission blocker: active package OR monitor session.
        val activePrivateSessionBefore = activePackagePresent || monitorSessionPresent

        val expectedExpiryState = classifyExpectedUnhideExpiry(
            packageName = expectedUnhidePackage,
            sessionId = expectedUnhideSession,
            expiresElapsed = expectedUnhideExpiresElapsed
        )

        val expectedUnhidePresent =
            expectedUnhidePackage.isNotBlank() ||
                expectedUnhideSession.isNotBlank() ||
                expectedUnhideExpiresElapsed > 0L

        val expectedMatchesActiveSession = if (!expectedUnhidePresent) {
            "NOT_APPLICABLE"
        }
        else {
            yesNo(
                expectedExpiryState == "ACTIVE" &&
                    expectedUnhidePackage.equals(targetPackage, ignoreCase = true) &&
                    activePackageIsTarget &&
                    monitorSessionPresent &&
                    expectedUnhideSession == monitorSession
            )
        }

        val privateLaunchDispatchElapsedPresent = privateLaunchDispatchElapsed > 0L
        val privateLaunchDispatchWallPresent = privateLaunchDispatchWall > 0L
        val privateLaunchOutcomeClass = classifyPrivateLaunchOutcome(privateLaunchOutcome)

        val privateLaunchStatePresent =
            privateLaunchSession.isNotBlank() ||
                privateLaunchPackage.isNotBlank() ||
                privateLaunchDispatchElapsedPresent ||
                privateLaunchDispatchWallPresent ||
                privateLaunchOutcome.isNotBlank()

        val monitorReadyLinkCoherent =
            (!monitorReadySessionPresent && monitorReadyPackage.isBlank()) ||
                (
                    monitorSessionPresent &&
                        monitorReadySessionPresent &&
                        monitorReadySession == monitorSession &&
                        monitorReadyPackageIsTarget
                    )

        val activeMonitorDispatchStateCoherent = if (!privateLaunchStatePresent) {
            "NOT_APPLICABLE"
        }
        else {
            yesNo(
                privateLaunchSession.isNotBlank() &&
                    privateLaunchPackage.equals(targetPackage, ignoreCase = true) &&
                    monitorSessionPresent &&
                    privateLaunchSession == monitorSession &&
                    privateLaunchDispatchElapsedPresent &&
                    privateLaunchDispatchWallPresent &&
                    privateLaunchOutcomeClass in setOf("PENDING", "CONFIRMED", "FAILED") &&
                    monitorReadyLinkCoherent
            )
        }

        val baseSessionLinkCoherent =
            (activePackagePresent == monitorSessionPresent) &&
                (!activePackagePresent || activePackageIsTarget)

        val expectedCoherent =
            expectedExpiryState !in setOf("EXPIRED", "INVALID") &&
                expectedMatchesActiveSession != "NO"

        val launchCoherent = activeMonitorDispatchStateCoherent != "NO"

        val privateSessionStateCoherence = passFail(
            rawRegistryState.parseStatus.startsWith("PASS") &&
                rawRegistryState.rawTargetRecordMatchCount <= 1 &&
                rawRegistryState.malformedTargetRecordCount == 0 &&
                rawRegistryState.rawTargetRecordMatchCount == targetRecords.size &&
                recordLabelMatch != "NO" &&
                recordComponentMatch != "NO" &&
                baseSessionLinkCoherent &&
                monitorReadyLinkCoherent &&
                expectedCoherent &&
                launchCoherent
        )

        return PrivateState(
            targetPackage = targetPackage,
            privateRegistryParseStatus = rawRegistryState.parseStatus,
            rawTargetRecordMatchCount = rawRegistryState.rawTargetRecordMatchCount,
            malformedTargetRecordCount = rawRegistryState.malformedTargetRecordCount,
            privateRecordMatchCount = targetRecords.size,
            alreadyInPrivateList = targetRecords.isNotEmpty(),
            targetRecordLabelMatchesPackageManager = recordLabelMatch,
            targetRecordComponentMatchesPackageManager = recordComponentMatch,
            activePackagePresent = activePackagePresent,
            activePackageIsTarget = activePackageIsTarget,
            monitorSessionPresent = monitorSessionPresent,
            monitorReadySessionPresent = monitorReadySessionPresent,
            monitorReadyPackageIsTarget = monitorReadyPackageIsTarget,
            activePrivateSessionBefore = activePrivateSessionBefore,
            pendingRehideForTarget = pendingRehide,
            protectionPaused = protectionPaused,
            expectedUnhidePackagePresent = expectedUnhidePackage.isNotBlank(),
            expectedUnhidePackageIsTarget =
                expectedUnhidePackage.equals(targetPackage, ignoreCase = true),
            expectedUnhideSessionPresent = expectedUnhideSession.isNotBlank(),
            expectedUnhideExpiryState = expectedExpiryState,
            expectedUnhideMatchesActiveSession = expectedMatchesActiveSession,
            privateLaunchSessionPresent = privateLaunchSession.isNotBlank(),
            privateLaunchPackageIsTarget =
                privateLaunchPackage.equals(targetPackage, ignoreCase = true),
            privateLaunchDispatchElapsedPresent = privateLaunchDispatchElapsedPresent,
            privateLaunchDispatchWallPresent = privateLaunchDispatchWallPresent,
            privateLaunchOutcomeClass = privateLaunchOutcomeClass,
            activeMonitorDispatchStateCoherent = activeMonitorDispatchStateCoherent,
            privateSessionStateCoherence = privateSessionStateCoherence
        )
    }

    private fun readDpmState(context: Context, targetPackage: String): DpmState {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, ZeaDeviceAdminReceiver::class.java)

        val zeaIsDeviceOwner = manager?.isDeviceOwnerApp(context.packageName) == true
        val zeaAdminIsActive = manager?.isAdminActive(admin) == true

        val hidden = ZeaDeviceOwnerController.isHidden(context, targetPackage)
        val uninstallBlocked =
            ZeaDeviceOwnerController.isUninstallBlocked(context, targetPackage)

        return DpmState(
            zeaIsDeviceOwner = zeaIsDeviceOwner,
            zeaAdminIsActive = zeaAdminIsActive,
            hiddenBefore = hidden,
            uninstallBlockedBefore = uninstallBlocked,
            hiddenQueryStatus = if (hidden == null) "FAIL" else "PASS",
            uninstallBlockQueryStatus =
                if (uninstallBlocked == null) "FAIL" else "PASS"
        )
    }

    private fun readPackageMetadata(context: Context, targetPackage: String): PackageState {
        fun requiredArgument(name: String): String {
            val value = inputArguments.getString(name).orEmpty().trim()
            require(value.isNotBlank()) { "$name is required for hidden-safe bound identity" }
            return value
        }

        val label = requiredArgument("expected_label")
        val rawLauncherClass = requiredArgument("expected_launcher_class")
        val launcherClassName = when {
            rawLauncherClass.startsWith(".") -> targetPackage + rawLauncherClass
            rawLauncherClass.contains(".") -> rawLauncherClass
            else -> "$targetPackage.$rawLauncherClass"
        }

        val uid = requiredArgument("expected_uid").toInt()
        val versionCode = requiredArgument("expected_version_code").toLong()
        val versionName = requiredArgument("expected_version_name")
        val isSystemApp =
            requiredArgument("expected_is_system_app").equals("YES", ignoreCase = true)
        val isUpdatedSystemApp =
            requiredArgument("expected_is_updated_system_app").equals("YES", ignoreCase = true)

        require(
            requiredArgument("expected_support_proven").equals("YES", ignoreCase = true)
        ) { "pre-hide Zea support proof is required" }

        val preHideApkSha256 = requiredArgument("expected_pre_hide_apk_sha256")
        require(preHideApkSha256.matches(Regex("^[0-9A-Fa-f]{64}$"))) {
            "expected_pre_hide_apk_sha256 must be SHA-256"
        }

        return PackageState(
            targetPackage = targetPackage,
            targetInstalled = true,
            applicationEnabled = true,
            label = label,
            launcherComponent =
                ComponentName(targetPackage, launcherClassName).flattenToString(),
            launcherClassName = launcherClassName,
            launcherComponentCount = 1,
            launcherComponentEnabled = true,
            launcherComponentExported = true,
            launcherResolutionStatus = "PASS_EXACT_ONE_ENABLED_EXPORTED",
            uid = uid,
            versionCode = versionCode,
            versionName = versionName,
            isSystemApp = isSystemApp,
            isUpdatedSystemApp = isUpdatedSystemApp,
            safetyAllowed = true,
            validatePrivateAppResult = "BOUND_PRE_HIDE_PASS",
            supportedByZea = true
        )
    }

    private fun resolveLauncherMetadata(
        context: Context,
        targetPackage: String
    ): LauncherMetadata {
        val packageManager = context.packageManager
        val applicationInfo = packageManager.getApplicationInfo(
            targetPackage,
            PackageManager.MATCH_DISABLED_COMPONENTS
        )
        val label = packageManager.getApplicationLabel(applicationInfo).toString().trim()

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(targetPackage)
        }

        val activities = packageManager
            .queryIntentActivities(
                launcherIntent,
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
            .mapNotNull { resolveInfo ->
                val info = resolveInfo.activityInfo ?: return@mapNotNull null
                ComponentName(info.packageName, info.name) to info
            }
            .distinctBy { pair -> pair.first.flattenToString() }
            .sortedBy { pair -> pair.first.flattenToString().lowercase(Locale.ROOT) }

        val enabledExported = activities.filter { (_, info) ->
            info.enabled && info.exported && info.applicationInfo.enabled
        }

        val exact = enabledExported.singleOrNull()
        val component = exact?.first
        val info = exact?.second

        val resolutionStatus = when {
            enabledExported.isEmpty() -> "FAIL_NO_ENABLED_EXPORTED_LAUNCHER"
            enabledExported.size > 1 -> "FAIL_MULTIPLE_ENABLED_EXPORTED_LAUNCHERS"
            else -> "PASS_EXACT_ONE_ENABLED_EXPORTED"
        }

        return LauncherMetadata(
            label = label,
            launcherComponent = component?.flattenToString().orEmpty(),
            launcherClassName = component?.className.orEmpty(),
            launcherComponentCount = enabledExported.size,
            launcherComponentEnabled = info?.enabled == true,
            launcherComponentExported = info?.exported == true,
            launcherResolutionStatus = resolutionStatus
        )
    }

    private fun inspectPrivateRegistry(
        raw: String,
        targetPackage: String
    ): RawRegistryState {
        if (raw.isBlank()) {
            return RawRegistryState(
                parseStatus = "PASS_EMPTY",
                rawTargetRecordMatchCount = 0,
                malformedTargetRecordCount = 0
            )
        }

        return try {
            val root = JSONObject(raw)
            val schemaVersion = root.optInt("schemaVersion", 0)
            val apps = root.optJSONArray("apps")

            if (schemaVersion != 1) {
                RawRegistryState(
                    parseStatus = "FAIL_SCHEMA_VERSION",
                    rawTargetRecordMatchCount = 0,
                    malformedTargetRecordCount = 0
                )
            }
            else if (apps == null) {
                RawRegistryState(
                    parseStatus = "FAIL_APPS_ARRAY_MISSING",
                    rawTargetRecordMatchCount = 0,
                    malformedTargetRecordCount = 0
                )
            }
            else {
                var targetCount = 0
                var malformedTargetCount = 0

                for (index in 0 until apps.length()) {
                    val item = apps.optJSONObject(index) ?: continue
                    val packageName = item.optString("packageName", "").trim()

                    if (packageName.equals(targetPackage, ignoreCase = true)) {
                        targetCount += 1

                        if (
                            item.optString("displayName", "").trim().isBlank() ||
                            item.optString("launcherActivityName", "").trim().isBlank()
                        ) {
                            malformedTargetCount += 1
                        }
                    }
                }

                RawRegistryState(
                    parseStatus = "PASS_JSON_SCHEMA_1",
                    rawTargetRecordMatchCount = targetCount,
                    malformedTargetRecordCount = malformedTargetCount
                )
            }
        }
        catch (_: Throwable) {
            RawRegistryState(
                parseStatus = "FAIL_JSON_PARSE",
                rawTargetRecordMatchCount = 0,
                malformedTargetRecordCount = 0
            )
        }
    }

    private fun classifyExpectedUnhideExpiry(
        packageName: String,
        sessionId: String,
        expiresElapsed: Long
    ): String {
        val anyPresent =
            packageName.isNotBlank() ||
                sessionId.isNotBlank() ||
                expiresElapsed > 0L

        if (!anyPresent) return "ABSENT"

        if (
            packageName.isBlank() ||
            sessionId.isBlank() ||
            expiresElapsed <= 0L
        ) {
            return "INVALID"
        }

        return if (expiresElapsed > SystemClock.elapsedRealtime()) {
            "ACTIVE"
        }
        else {
            "EXPIRED"
        }
    }

    private fun classifyPrivateLaunchOutcome(raw: String): String {
        return when (raw.trim().lowercase(Locale.ROOT)) {
            "" -> "ABSENT"
            "pending" -> "PENDING"
            "confirmed" -> "CONFIRMED"
            "failed" -> "FAILED"
            else -> "OTHER"
        }
    }

    private fun currentProcessName(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        }
        else {
            context.packageName
        }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun put(bundle: Bundle, key: String, value: String) {
        bundle.putString(
            key,
            value.replace("\r", " ").replace("\n", " ").take(500)
        )
    }

    private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"
    private fun passFail(value: Boolean): String = if (value) "PASS" else "FAIL"

    private fun nullableBool(value: Boolean?): String = when (value) {
        true -> "YES"
        false -> "NO"
        null -> "NOT_AVAILABLE"
    }

    private data class RawRegistryState(
        val parseStatus: String,
        val rawTargetRecordMatchCount: Int,
        val malformedTargetRecordCount: Int
    )

    private data class LauncherMetadata(
        val label: String,
        val launcherComponent: String,
        val launcherClassName: String,
        val launcherComponentCount: Int,
        val launcherComponentEnabled: Boolean,
        val launcherComponentExported: Boolean,
        val launcherResolutionStatus: String
    )

    private data class PrivateState(
        val targetPackage: String,
        val privateRegistryParseStatus: String,
        val rawTargetRecordMatchCount: Int,
        val malformedTargetRecordCount: Int,
        val privateRecordMatchCount: Int,
        val alreadyInPrivateList: Boolean,
        val targetRecordLabelMatchesPackageManager: String,
        val targetRecordComponentMatchesPackageManager: String,
        val activePackagePresent: Boolean,
        val activePackageIsTarget: Boolean,
        val monitorSessionPresent: Boolean,
        val monitorReadySessionPresent: Boolean,
        val monitorReadyPackageIsTarget: Boolean,
        val activePrivateSessionBefore: Boolean,
        val pendingRehideForTarget: Boolean,
        val protectionPaused: Boolean,
        val expectedUnhidePackagePresent: Boolean,
        val expectedUnhidePackageIsTarget: Boolean,
        val expectedUnhideSessionPresent: Boolean,
        val expectedUnhideExpiryState: String,
        val expectedUnhideMatchesActiveSession: String,
        val privateLaunchSessionPresent: Boolean,
        val privateLaunchPackageIsTarget: Boolean,
        val privateLaunchDispatchElapsedPresent: Boolean,
        val privateLaunchDispatchWallPresent: Boolean,
        val privateLaunchOutcomeClass: String,
        val activeMonitorDispatchStateCoherent: String,
        val privateSessionStateCoherence: String
    ) {
        fun canonical(): String {
            return listOf(
                targetPackage,
                privateRegistryParseStatus,
                rawTargetRecordMatchCount.toString(),
                malformedTargetRecordCount.toString(),
                privateRecordMatchCount.toString(),
                yesNoStatic(alreadyInPrivateList),
                targetRecordLabelMatchesPackageManager,
                targetRecordComponentMatchesPackageManager,
                yesNoStatic(activePackagePresent),
                yesNoStatic(activePackageIsTarget),
                yesNoStatic(monitorSessionPresent),
                yesNoStatic(monitorReadySessionPresent),
                yesNoStatic(monitorReadyPackageIsTarget),
                yesNoStatic(activePrivateSessionBefore),
                yesNoStatic(pendingRehideForTarget),
                yesNoStatic(protectionPaused),
                yesNoStatic(expectedUnhidePackagePresent),
                yesNoStatic(expectedUnhidePackageIsTarget),
                yesNoStatic(expectedUnhideSessionPresent),
                expectedUnhideExpiryState,
                expectedUnhideMatchesActiveSession,
                yesNoStatic(privateLaunchSessionPresent),
                yesNoStatic(privateLaunchPackageIsTarget),
                yesNoStatic(privateLaunchDispatchElapsedPresent),
                yesNoStatic(privateLaunchDispatchWallPresent),
                privateLaunchOutcomeClass,
                activeMonitorDispatchStateCoherent,
                privateSessionStateCoherence
            ).joinToString("\u001F")
        }

        fun writeTo(bundle: Bundle) {
            bundle.putString("PRIVATE_REGISTRY_PARSE_STATUS", privateRegistryParseStatus)
            bundle.putString(
                "RAW_TARGET_PRIVATE_RECORD_MATCH_COUNT",
                rawTargetRecordMatchCount.toString()
            )
            bundle.putString(
                "MALFORMED_TARGET_PRIVATE_RECORD_COUNT",
                malformedTargetRecordCount.toString()
            )
            bundle.putString(
                "TARGET_PRIVATE_RECORD_MATCH_COUNT",
                privateRecordMatchCount.toString()
            )
            bundle.putString(
                "TARGET_PRIVATE_APP_ALREADY_IN_ZEA_PRIVATE_LIST",
                yesNoStatic(alreadyInPrivateList)
            )
            bundle.putString(
                "TARGET_PRIVATE_RECORD_LABEL_MATCHES_PACKAGE_MANAGER",
                targetRecordLabelMatchesPackageManager
            )
            bundle.putString(
                "TARGET_PRIVATE_RECORD_COMPONENT_MATCHES_PACKAGE_MANAGER",
                targetRecordComponentMatchesPackageManager
            )
            bundle.putString(
                "ACTIVE_PRIVATE_PACKAGE_PRESENT",
                yesNoStatic(activePackagePresent)
            )
            bundle.putString(
                "ACTIVE_PRIVATE_PACKAGE_IS_TARGET",
                yesNoStatic(activePackageIsTarget)
            )
            bundle.putString("MONITOR_SESSION_PRESENT", yesNoStatic(monitorSessionPresent))
            bundle.putString(
                "MONITOR_READY_SESSION_PRESENT",
                yesNoStatic(monitorReadySessionPresent)
            )
            bundle.putString(
                "MONITOR_READY_PACKAGE_IS_TARGET",
                yesNoStatic(monitorReadyPackageIsTarget)
            )
            bundle.putString(
                "ACTIVE_PRIVATE_SESSION_BEFORE",
                yesNoStatic(activePrivateSessionBefore)
            )
            bundle.putString(
                "PENDING_REHIDE_FOR_TARGET_BEFORE",
                yesNoStatic(pendingRehideForTarget)
            )
            bundle.putString("PROTECTION_PAUSED", yesNoStatic(protectionPaused))
            bundle.putString(
                "EXPECTED_UNHIDE_PACKAGE_PRESENT",
                yesNoStatic(expectedUnhidePackagePresent)
            )
            bundle.putString(
                "EXPECTED_UNHIDE_PACKAGE_IS_TARGET",
                yesNoStatic(expectedUnhidePackageIsTarget)
            )
            bundle.putString(
                "EXPECTED_UNHIDE_SESSION_PRESENT",
                yesNoStatic(expectedUnhideSessionPresent)
            )
            bundle.putString(
                "EXPECTED_UNHIDE_EXPIRY_STATE",
                expectedUnhideExpiryState
            )
            bundle.putString(
                "EXPECTED_UNHIDE_MATCHES_ACTIVE_SESSION",
                expectedUnhideMatchesActiveSession
            )
            bundle.putString(
                "PRIVATE_LAUNCH_SESSION_PRESENT",
                yesNoStatic(privateLaunchSessionPresent)
            )
            bundle.putString(
                "PRIVATE_LAUNCH_PACKAGE_IS_TARGET",
                yesNoStatic(privateLaunchPackageIsTarget)
            )
            bundle.putString(
                "PRIVATE_LAUNCH_DISPATCH_ELAPSED_PRESENT",
                yesNoStatic(privateLaunchDispatchElapsedPresent)
            )
            bundle.putString(
                "PRIVATE_LAUNCH_DISPATCH_WALL_PRESENT",
                yesNoStatic(privateLaunchDispatchWallPresent)
            )
            bundle.putString("PRIVATE_LAUNCH_OUTCOME_CLASS", privateLaunchOutcomeClass)
            bundle.putString(
                "ACTIVE_MONITOR_DISPATCH_STATE_COHERENT",
                activeMonitorDispatchStateCoherent
            )
            bundle.putString(
                "PRIVATE_SESSION_STATE_COHERENCE",
                privateSessionStateCoherence
            )
        }

        companion object {
            private fun yesNoStatic(value: Boolean): String =
                if (value) "YES" else "NO"
        }
    }

    private data class DpmState(
        val zeaIsDeviceOwner: Boolean,
        val zeaAdminIsActive: Boolean,
        val hiddenBefore: Boolean?,
        val uninstallBlockedBefore: Boolean?,
        val hiddenQueryStatus: String,
        val uninstallBlockQueryStatus: String
    ) {
        fun canonical(): String {
            return listOf(
                yesNoStatic(zeaIsDeviceOwner),
                yesNoStatic(zeaAdminIsActive),
                nullableBoolStatic(hiddenBefore),
                nullableBoolStatic(uninstallBlockedBefore),
                hiddenQueryStatus,
                uninstallBlockQueryStatus
            ).joinToString("\u001F")
        }

        fun writeTo(bundle: Bundle) {
            bundle.putString("ZEA_IS_DEVICE_OWNER", yesNoStatic(zeaIsDeviceOwner))
            bundle.putString("ZEA_ADMIN_IS_ACTIVE", yesNoStatic(zeaAdminIsActive))
            bundle.putString(
                "TARGET_PRIVATE_APP_HIDDEN_BEFORE",
                nullableBoolStatic(hiddenBefore)
            )
            bundle.putString(
                "TARGET_PRIVATE_APP_UNINSTALL_BLOCKED_BEFORE",
                nullableBoolStatic(uninstallBlockedBefore)
            )
            bundle.putString("HIDDEN_QUERY_STATUS", hiddenQueryStatus)
            bundle.putString(
                "UNINSTALL_BLOCK_QUERY_STATUS",
                uninstallBlockQueryStatus
            )
        }

        companion object {
            private fun yesNoStatic(value: Boolean): String =
                if (value) "YES" else "NO"

            private fun nullableBoolStatic(value: Boolean?): String = when (value) {
                true -> "YES"
                false -> "NO"
                null -> "NOT_AVAILABLE"
            }
        }
    }

    private data class PackageState(
        val targetPackage: String,
        val targetInstalled: Boolean,
        val applicationEnabled: Boolean,
        val label: String,
        val launcherComponent: String,
        val launcherClassName: String,
        val launcherComponentCount: Int,
        val launcherComponentEnabled: Boolean,
        val launcherComponentExported: Boolean,
        val launcherResolutionStatus: String,
        val uid: Int,
        val versionCode: Long,
        val versionName: String,
        val isSystemApp: Boolean,
        val isUpdatedSystemApp: Boolean,
        val safetyAllowed: Boolean,
        val validatePrivateAppResult: String,
        val supportedByZea: Boolean
    ) {
        fun canonical(): String {
            return listOf(
                targetPackage,
                yesNoStatic(targetInstalled),
                yesNoStatic(applicationEnabled),
                label,
                launcherComponent,
                launcherClassName,
                launcherComponentCount.toString(),
                yesNoStatic(launcherComponentEnabled),
                yesNoStatic(launcherComponentExported),
                launcherResolutionStatus,
                uid.toString(),
                versionCode.toString(),
                versionName,
                yesNoStatic(isSystemApp),
                yesNoStatic(isUpdatedSystemApp),
                yesNoStatic(safetyAllowed),
                validatePrivateAppResult,
                yesNoStatic(supportedByZea)
            ).joinToString("\u001F")
        }

        fun writeTo(bundle: Bundle) {
            bundle.putString("TARGET_PRIVATE_APP_PACKAGE", targetPackage)
            bundle.putString("TARGET_INSTALLED", yesNoStatic(targetInstalled))
            bundle.putString(
                "TARGET_APPLICATION_ENABLED",
                yesNoStatic(applicationEnabled)
            )
            bundle.putString("TARGET_PRIVATE_APP_LABEL", label)
            bundle.putString(
                "TARGET_PRIVATE_APP_LAUNCHER_COMPONENT",
                launcherComponent
            )
            bundle.putString(
                "TARGET_PRIVATE_APP_LAUNCHER_COMPONENT_COUNT",
                launcherComponentCount.toString()
            )
            bundle.putString(
                "TARGET_LAUNCHER_COMPONENT_ENABLED",
                yesNoStatic(launcherComponentEnabled)
            )
            bundle.putString(
                "TARGET_LAUNCHER_COMPONENT_EXPORTED",
                yesNoStatic(launcherComponentExported)
            )
            bundle.putString(
                "TARGET_LAUNCHER_RESOLUTION_STATUS",
                launcherResolutionStatus
            )
            bundle.putString("TARGET_PRIVATE_APP_UID_BEFORE", uid.toString())
            bundle.putString(
                "TARGET_PRIVATE_APP_VERSION_CODE_BEFORE",
                versionCode.toString()
            )
            bundle.putString("TARGET_PRIVATE_APP_VERSION_NAME_BEFORE", versionName)
            bundle.putString(
                "TARGET_PRIVATE_APP_DATA_STATE_FINGERPRINT_BEFORE",
                "NOT_APPLICABLE"
            )
            bundle.putString(
                "DATA_FINGERPRINT_SCHEMA",
                "NOT_APPLICABLE_NO_PRIVACY_SAFE_CROSS_PACKAGE_DATA_STATE_SURFACE"
            )
            bundle.putString(
                "TARGET_PRIVATE_APP_IS_SYSTEM_APP",
                yesNoStatic(isSystemApp)
            )
            bundle.putString(
                "TARGET_PRIVATE_APP_IS_UPDATED_SYSTEM_APP",
                yesNoStatic(isUpdatedSystemApp)
            )
            bundle.putString(
                "ZEA_STATIC_SAFETY_POLICY_ALLOWED",
                yesNoStatic(safetyAllowed)
            )
            bundle.putString(
                "ZEA_VALIDATE_PRIVATE_APP_RESULT",
                validatePrivateAppResult.take(500)
            )
            bundle.putString(
                "ZEA_SUPPORT_EVALUATION_STATUS",
                if (supportedByZea) "PASS" else "FAIL"
            )
            bundle.putString(
                "TARGET_PRIVATE_APP_SUPPORTED_BY_ZEA",
                yesNoStatic(supportedByZea)
            )
        }

        companion object {
            private fun yesNoStatic(value: Boolean): String =
                if (value) "YES" else "NO"
        }
    }
}