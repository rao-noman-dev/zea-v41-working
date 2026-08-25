package com.raomuhammadnoman.zea

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 2 (P1) - System Check / Diagnostics engine.
 *
 * Runs the thirteen structured self-tests the roadmap defines for
 * Settings -> Diagnostics -> Run System Check. Each check produces a
 * pass/fail/not-applicable verdict with a human-readable explanation and,
 * where a safe automatic recovery exists, a repair action.
 */
enum class ZeaCheckStatus {
    PASS,
    FAIL,
    NOT_APPLICABLE
}

enum class ZeaRepairAction {
    NONE,
    OPEN_USAGE_ACCESS_SETTINGS,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_EXACT_ALARM_SETTINGS,
    REPAIR_REGISTRY,
    RECONCILE_HIDDEN_STATE,
    REPAIR_CACHE,
    REPAIR_INSTALL_LOCK
}

data class ZeaSystemCheckResult(
    val id: String,
    val title: String,
    val status: ZeaCheckStatus,
    val detail: String,
    val repair: ZeaRepairAction = ZeaRepairAction.NONE
)

data class ZeaSystemCheckReport(
    val results: List<ZeaSystemCheckResult>
) {
    val passedCount: Int
        get() = results.count { result -> result.status == ZeaCheckStatus.PASS }

    val failedCount: Int
        get() = results.count { result -> result.status == ZeaCheckStatus.FAIL }

    val checkedCount: Int
        get() = results.count { result -> result.status != ZeaCheckStatus.NOT_APPLICABLE }
}

data class ZeaRepairOutcome(
    val success: Boolean,
    val message: String
)

object ZeaSystemCheck {

    suspend fun run(context: Context): ZeaSystemCheckReport =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val deviceOwnerMode = ZeaOnboardingState.readSelectedMode(appContext) ==
                    ZeaOnboardingState.MODE_DEVICE_OWNER
            val deviceOwnerActive = ZeaDeviceOwnerController.isDeviceOwner(appContext)
            val results = mutableListOf<ZeaSystemCheckResult>()

            // 1. Registry readable.
            val registryRawReadable = try {
                getZeaPrefs(appContext)
                    .getString(ZeaStorageContract.PRIVATE_APPS_JSON, "") != null
            } catch (_: RuntimeException) {
                false
            }
            results += ZeaSystemCheckResult(
                id = "registry_readable",
                title = "Registry readable",
                status = if (registryRawReadable) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                detail = if (registryRawReadable) {
                    "Protected-app registry storage is readable."
                } else {
                    "Protected-app registry storage could not be read."
                }
            )

            // 2. Registry schema valid.
            val registryIssue = ZeaProtectionHealth.registryIntegrityIssue(appContext)
            results += ZeaSystemCheckResult(
                id = "registry_schema",
                title = "Registry schema valid",
                status = if (registryIssue == null) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                detail = registryIssue ?: "Every registry record matches the expected schema.",
                repair = if (registryIssue == null) {
                    ZeaRepairAction.NONE
                } else {
                    ZeaRepairAction.REPAIR_REGISTRY
                }
            )

            // 3. Device Owner accessible.
            results += when {
                !deviceOwnerMode -> ZeaSystemCheckResult(
                    id = "device_owner_accessible",
                    title = "Device Owner accessible",
                    status = ZeaCheckStatus.NOT_APPLICABLE,
                    detail = "Standard mode does not use Device Owner."
                )
                deviceOwnerActive -> ZeaSystemCheckResult(
                    id = "device_owner_accessible",
                    title = "Device Owner accessible",
                    status = ZeaCheckStatus.PASS,
                    detail = "Device Owner APIs respond and the role is active."
                )
                else -> ZeaSystemCheckResult(
                    id = "device_owner_accessible",
                    title = "Device Owner accessible",
                    status = ZeaCheckStatus.FAIL,
                    detail = "Device Owner mode is selected but the role is not provisioned."
                )
            }

            // 4. Hidden states queryable.
            results += when {
                !deviceOwnerMode -> ZeaSystemCheckResult(
                    id = "hidden_states_queryable",
                    title = "Hidden states queryable",
                    status = ZeaCheckStatus.NOT_APPLICABLE,
                    detail = "Standard mode hides via the lock engine, not DPM."
                )
                !deviceOwnerActive -> ZeaSystemCheckResult(
                    id = "hidden_states_queryable",
                    title = "Hidden states queryable",
                    status = ZeaCheckStatus.FAIL,
                    detail = "Hidden-state queries require an active Device Owner."
                )
                else -> {
                    val sample = loadPrivateApps(appContext).firstOrNull()
                    val queryWorked = sample == null ||
                            ZeaDeviceOwnerController.isHidden(
                                appContext,
                                sample.packageName
                            ) != null
                    ZeaSystemCheckResult(
                        id = "hidden_states_queryable",
                        title = "Hidden states queryable",
                        status = if (queryWorked) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                        detail = if (queryWorked) {
                            "DPM hidden-state queries respond correctly."
                        } else {
                            "DPM hidden-state query failed for ${sample.displayName}."
                        }
                    )
                }
            }

            // 5. Timed records valid.
            val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
            val timedRecords = loadTimedHides(appContext)
            val invalidTimed = timedRecords.count { record ->
                !packagePattern.matches(record.packageName) ||
                        record.hiddenUntilEpochMillis <= 0L ||
                        record.hiddenAtEpochMillis <= 0L ||
                        record.hiddenUntilEpochMillis < record.hiddenAtEpochMillis
            }
            results += ZeaSystemCheckResult(
                id = "timed_records_valid",
                title = "Timed records valid",
                status = if (invalidTimed == 0) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                detail = if (invalidTimed == 0) {
                    "${timedRecords.size} timed-hide record(s) are structurally valid."
                } else {
                    "$invalidTimed timed-hide record(s) are corrupted."
                }
            )

            // 6. Exact alarms available.
            val exactAlarmsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(AlarmManager::class.java)
                    ?.canScheduleExactAlarms() == true
            } else {
                true
            }
            results += ZeaSystemCheckResult(
                id = "exact_alarms",
                title = "Exact alarms available",
                status = if (exactAlarmsOk) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                detail = if (exactAlarmsOk) {
                    "Exact alarms are available for timed hiding."
                } else {
                    "Exact alarms are not allowed; timed unhide may be delayed."
                },
                repair = if (exactAlarmsOk) {
                    ZeaRepairAction.NONE
                } else {
                    ZeaRepairAction.OPEN_EXACT_ALARM_SETTINGS
                }
            )

            // 7. Pending re-hide queue empty.
            val pendingRehide = ZeaDeviceOwnerController.pendingRehidePackages(appContext)
            results += ZeaSystemCheckResult(
                id = "pending_rehide_empty",
                title = "Pending re-hide queue empty",
                status = if (pendingRehide.isEmpty()) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                detail = if (pendingRehide.isEmpty()) {
                    "No apps are waiting to be re-hidden."
                } else {
                    "${pendingRehide.size} app(s) are stuck in the re-hide queue."
                },
                repair = if (pendingRehide.isEmpty()) {
                    ZeaRepairAction.NONE
                } else {
                    ZeaRepairAction.RECONCILE_HIDDEN_STATE
                }
            )

            // 8. App Lock service active.
            val lockEngineHealthy = zyroIsLockEngineHealthy(appContext)
            results += ZeaSystemCheckResult(
                id = "app_lock_service",
                title = "App Lock service active",
                status = if (lockEngineHealthy) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                detail = if (lockEngineHealthy) {
                    if (deviceOwnerMode) {
                        "Not required in Device Owner mode."
                    } else {
                        "Accessibility lock engine is running."
                    }
                } else {
                    "App Lock accessibility service is disabled."
                },
                repair = if (lockEngineHealthy) {
                    ZeaRepairAction.NONE
                } else {
                    ZeaRepairAction.OPEN_ACCESSIBILITY_SETTINGS
                }
            )

            // 9. Usage Access active.
            val usageAccess = ZeaDeviceOwnerController.isUsageAccessGranted(appContext)
            results += ZeaSystemCheckResult(
                id = "usage_access",
                title = "Usage Access active",
                status = if (usageAccess) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                detail = if (usageAccess) {
                    "Usage Access is granted."
                } else {
                    "Usage Access is disabled; private session monitoring may not work."
                },
                repair = if (usageAccess) {
                    ZeaRepairAction.NONE
                } else {
                    ZeaRepairAction.OPEN_USAGE_ACCESS_SETTINGS
                }
            )

            // 10. Monitor service available.
            val monitorAvailable = try {
                appContext.packageManager.getServiceInfo(
                    android.content.ComponentName(
                        appContext,
                        ZeaPrivateSessionMonitorService::class.java
                    ),
                    0
                )
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: RuntimeException) {
                false
            }
            results += when {
                !deviceOwnerMode -> ZeaSystemCheckResult(
                    id = "monitor_service",
                    title = "Monitor service available",
                    status = ZeaCheckStatus.NOT_APPLICABLE,
                    detail = "Session monitoring is a Device Owner mode feature."
                )
                monitorAvailable -> ZeaSystemCheckResult(
                    id = "monitor_service",
                    title = "Monitor service available",
                    status = ZeaCheckStatus.PASS,
                    detail = "Private Session Monitor service is declared and available."
                )
                else -> ZeaSystemCheckResult(
                    id = "monitor_service",
                    title = "Monitor service available",
                    status = ZeaCheckStatus.FAIL,
                    detail = "Private Session Monitor service is unavailable."
                )
            }

            // 11. Launcher resolvable.
            val launcherPackage = try {
                appContext.packageManager.resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo?.packageName
            } catch (_: RuntimeException) {
                null
            }
            results += ZeaSystemCheckResult(
                id = "launcher_resolvable",
                title = "Launcher resolvable",
                status = if (!launcherPackage.isNullOrEmpty()) {
                    ZeaCheckStatus.PASS
                } else {
                    ZeaCheckStatus.FAIL
                },
                detail = if (!launcherPackage.isNullOrEmpty()) {
                    "Default launcher resolved: $launcherPackage."
                } else {
                    "No default launcher could be resolved; visibility sync may fail."
                }
            )

            // 12. Cache consistency (registry vs reloaded catalog).
            val registryCount = loadPrivateApps(appContext).size
            val cacheIssue = try {
                ZeaAppCatalog.invalidateCatalogCache()
                val catalogProtected = ZeaAppCatalog.loadManagedApps(appContext)
                    .count { app -> app.hideMode != ZeaHideMode.VISIBLE }
                if (catalogProtected == registryCount) {
                    null
                } else {
                    "Catalog reports $catalogProtected protected app(s) but the registry holds $registryCount."
                }
            } catch (_: RuntimeException) {
                "Catalog could not be reloaded for comparison."
            }
            results += ZeaSystemCheckResult(
                id = "cache_consistency",
                title = "Cache consistency",
                status = if (cacheIssue == null) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                detail = cacheIssue ?: "Catalog cache and registry agree on the protected-app set.",
                repair = if (cacheIssue == null) {
                    ZeaRepairAction.NONE
                } else {
                    ZeaRepairAction.REPAIR_CACHE
                }
            )

            // 13. Protection install lock consistency.
            results += when {
                !deviceOwnerActive -> ZeaSystemCheckResult(
                    id = "install_lock_consistency",
                    title = "Install lock consistency",
                    status = if (deviceOwnerMode) {
                        ZeaCheckStatus.FAIL
                    } else {
                        ZeaCheckStatus.NOT_APPLICABLE
                    },
                    detail = if (deviceOwnerMode) {
                        "Install lock cannot be verified without Device Owner."
                    } else {
                        "Install lock is a Device Owner mode feature."
                    }
                )
                else -> {
                    val lockActive = ZeaDeviceOwnerController
                        .readUiState(appContext)
                        .protectionInstallLockActive
                    val shouldBeActive = registryCount > 0 || pendingRehide.isNotEmpty()
                    val consistent = lockActive == shouldBeActive
                    ZeaSystemCheckResult(
                        id = "install_lock_consistency",
                        title = "Install lock consistency",
                        status = if (consistent) ZeaCheckStatus.PASS else ZeaCheckStatus.FAIL,
                        detail = if (consistent) {
                            "Install lock state matches the protected-app registry."
                        } else if (shouldBeActive) {
                            "Install lock should be active but is not."
                        } else {
                            "A stale install lock is active although nothing is protected."
                        },
                        repair = if (consistent) {
                            ZeaRepairAction.NONE
                        } else {
                            ZeaRepairAction.REPAIR_INSTALL_LOCK
                        }
                    )
                }
            }

            ZeaSystemCheckReport(results = results)
        }

    /**
     * Executes the safe repair attached to a failed check. Settings-opening
     * repairs return an intent-launch outcome handled by the caller; the
     * remaining repairs run the same transactional engines the normal flows
     * use, never a shortcut.
     */
    suspend fun repair(
        context: Context,
        action: ZeaRepairAction
    ): ZeaRepairOutcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        when (action) {
            ZeaRepairAction.NONE -> ZeaRepairOutcome(
                success = false,
                message = "This check has no automatic repair."
            )

            ZeaRepairAction.OPEN_USAGE_ACCESS_SETTINGS,
            ZeaRepairAction.OPEN_ACCESSIBILITY_SETTINGS,
            ZeaRepairAction.OPEN_NOTIFICATION_SETTINGS,
            ZeaRepairAction.OPEN_EXACT_ALARM_SETTINGS -> ZeaRepairOutcome(
                success = true,
                message = "Open the shown system settings page to resolve this issue."
            )

            ZeaRepairAction.REPAIR_REGISTRY -> {
                // Rewriting the registry through the normal save path drops
                // invalid records via the same sanitizer every write uses.
                val current = loadPrivateApps(appContext)
                savePrivateApps(appContext, current)
                val remainingIssue = ZeaProtectionHealth.registryIntegrityIssue(appContext)
                ZeaRepairOutcome(
                    success = remainingIssue == null,
                    message = remainingIssue
                        ?: "Registry repaired: invalid records were removed."
                )
            }

            ZeaRepairAction.RECONCILE_HIDDEN_STATE -> {
                // Re-hide every pending package that still has a durable
                // record; drop queue entries that no longer map to one.
                val records = loadPrivateApps(appContext)
                val pending = ZeaDeviceOwnerController.pendingRehidePackages(appContext)
                if (pending.isEmpty()) {
                    ZeaRepairOutcome(success = true, message = "Re-hide queue is already empty.")
                } else {
                    val catalog = ZeaAppCatalog.loadManagedApps(appContext)
                    var recovered = 0
                    val failures = mutableListOf<String>()
                    pending.forEach { packageName ->
                        val record = records.firstOrNull { record ->
                            record.packageName.equals(packageName, ignoreCase = true)
                        }
                        val managedApp = catalog.firstOrNull { app ->
                            app.packageName.equals(packageName, ignoreCase = true)
                        }
                        when {
                            record == null -> {
                                ZeaDeviceOwnerController.clearPendingRehidePackage(
                                    appContext,
                                    packageName
                                )
                                recovered++
                            }
                            managedApp != null -> {
                                val outcome = ZeaAppHideService.hideApp(appContext, managedApp)
                                if (outcome.success) {
                                    ZeaDeviceOwnerController.clearPendingRehidePackage(
                                        appContext,
                                        packageName
                                    )
                                    recovered++
                                } else {
                                    failures += "${record.displayName}: ${outcome.message}"
                                }
                            }
                            else -> failures += "${record.displayName}: app is no longer installed"
                        }
                    }
                    ZeaAppCatalog.invalidateCatalogCache()
                    ZeaRepairOutcome(
                        success = failures.isEmpty(),
                        message = if (failures.isEmpty()) {
                            "Re-hide queue cleared; $recovered app(s) recovered."
                        } else {
                            "Recovered $recovered app(s); failed: ${failures.joinToString("; ")}"
                        }
                    )
                }
            }

            ZeaRepairAction.REPAIR_CACHE -> {
                ZeaAppCatalog.invalidateCatalogCache()
                ZeaAppCatalog.loadManagedApps(appContext)
                ZeaRepairOutcome(
                    success = true,
                    message = "Catalog cache invalidated and rebuilt."
                )
            }

            ZeaRepairAction.REPAIR_INSTALL_LOCK -> {
                val result = ZeaDeviceOwnerController
                    .reconcileProtectionInstallLock(appContext)
                ZeaRepairOutcome(
                    success = result.success,
                    message = result.message
                )
            }
        }
    }
}
