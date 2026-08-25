package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 2 (P1) - Emergency Recovery / Safe Mode.
 *
 * The nine recovery actions the roadmap defines so a user never needs ADB or
 * a reinstall to escape a state mismatch. Every action runs through the same
 * transactional engines as the normal flows (hide/unhide service, Device
 * Owner controller, timed-hide scheduler) - recovery never bypasses safety
 * validation. Destructive actions are PIN-gated at the UI layer.
 */
enum class ZeaRecoveryAction(
    val title: String,
    val description: String,
    val destructive: Boolean
) {
    UNHIDE_ALL_APPS(
        title = "Unhide All Apps",
        description = "Makes every hidden and time-hidden app visible again. Apps stay listed but protection pauses until you hide them again.",
        destructive = true
    ),
    CANCEL_ALL_TIMERS(
        title = "Cancel All Timers",
        description = "Cancels every pending timed-hide schedule. Apps stay in their current state.",
        destructive = true
    ),
    RECONCILE_HIDDEN_STATE(
        title = "Reconcile Hidden State",
        description = "Re-applies hidden + uninstall-protected state to every registered app and sweeps orphaned hidden apps.",
        destructive = false
    ),
    RESTORE_LAUNCHER_VISIBILITY(
        title = "Restore Launcher Visibility",
        description = "Rebuilds the app catalog and forces the launcher to refresh its app list.",
        destructive = false
    ),
    REPAIR_REGISTRY(
        title = "Repair Registry",
        description = "Removes corrupted or duplicate entries from the protected-app registry.",
        destructive = true
    ),
    CLEAR_PENDING_REHIDE(
        title = "Clear Pending Re-hide Queue",
        description = "Retries re-hiding queued apps that still have records; safely drops queue entries whose app no longer exists.",
        destructive = true
    ),
    RESUME_PROTECTION(
        title = "Resume Protection",
        description = "Leaves emergency pause: re-hides every configured app and re-arms protection after full verification.",
        destructive = false
    ),
    PAUSE_PROTECTION(
        title = "Pause Protection",
        description = "Emergency pause: temporarily un-hides everything while keeping uninstall protection active.",
        destructive = true
    ),
    RERUN_SYSTEM_CHECK(
        title = "Re-run System Check",
        description = "Runs the full diagnostics suite again so you can confirm the recovery worked.",
        destructive = false
    )
}

object ZeaEmergencyRecovery {

    /**
     * Executes a recovery action on the IO dispatcher and returns a
     * user-readable outcome. Long actions (unhide-all, reconcile) iterate
     * with the same per-app transactional verification as normal operations.
     */
    suspend fun execute(
        context: Context,
        action: ZeaRecoveryAction
    ): ZeaRepairOutcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        when (action) {
            ZeaRecoveryAction.UNHIDE_ALL_APPS -> unhideAllApps(appContext)
            ZeaRecoveryAction.CANCEL_ALL_TIMERS -> cancelAllTimers(appContext)
            ZeaRecoveryAction.RECONCILE_HIDDEN_STATE -> reconcileHiddenState(appContext)
            ZeaRecoveryAction.RESTORE_LAUNCHER_VISIBILITY -> restoreLauncherVisibility(appContext)
            ZeaRecoveryAction.REPAIR_REGISTRY -> repairRegistry(appContext)
            ZeaRecoveryAction.CLEAR_PENDING_REHIDE ->
                ZeaSystemCheck.repair(appContext, ZeaRepairAction.RECONCILE_HIDDEN_STATE)
            ZeaRecoveryAction.RESUME_PROTECTION -> {
                val result = ZeaDeviceOwnerController.resumeProtection(appContext)
                ZeaRepairOutcome(result.success, result.message)
            }
            ZeaRecoveryAction.PAUSE_PROTECTION -> {
                val result = ZeaDeviceOwnerController.unhideAllAndPause(appContext)
                ZeaRepairOutcome(result.success, result.message)
            }
            ZeaRecoveryAction.RERUN_SYSTEM_CHECK -> {
                val report = ZeaSystemCheck.run(appContext)
                ZeaRepairOutcome(
                    success = report.failedCount == 0,
                    message = if (report.failedCount == 0) {
                        "System Check: ${report.passedCount} checks passed, no issues found."
                    } else {
                        "System Check: ${report.passedCount} passed, ${report.failedCount} issue(s) found."
                    }
                )
            }
        }
    }

    private suspend fun unhideAllApps(context: Context): ZeaRepairOutcome {
        val records = loadPrivateApps(context)
        val timedRecords = loadTimedHides(context)
        if (records.isEmpty() && timedRecords.isEmpty()) {
            return ZeaRepairOutcome(true, "No hidden apps to restore.")
        }

        val catalog = ZeaAppCatalog.loadManagedApps(context)
        val targets = (records.map { it.packageName } + timedRecords.map { it.packageName })
            .distinctBy { packageName -> packageName.lowercase() }

        var restored = 0
        val failures = mutableListOf<String>()
        targets.forEach { packageName ->
            val managedApp = catalog.firstOrNull { app ->
                app.packageName.equals(packageName, ignoreCase = true)
            }
            if (managedApp == null) {
                failures += "$packageName: app is no longer installed"
                return@forEach
            }
            val outcome = ZeaAppHideService.unhideApp(context, managedApp.packageName)
            if (outcome.success) {
                restored++
            } else {
                failures += "${managedApp.packageName}: ${outcome.message}"
            }
        }

        ZeaAppCatalog.invalidateCatalogCache()
        return ZeaRepairOutcome(
            success = failures.isEmpty(),
            message = if (failures.isEmpty()) {
                "All $restored app(s) are visible again."
            } else {
                "Restored $restored app(s); failed: ${failures.joinToString("; ")}"
            }
        )
    }

    private fun cancelAllTimers(context: Context): ZeaRepairOutcome {
        val timedRecords = loadTimedHides(context)
        if (timedRecords.isEmpty()) {
            return ZeaRepairOutcome(true, "No active timers to cancel.")
        }
        timedRecords.forEach { record ->
            ZeaTimedHide.cancel(context, record.packageName)
        }
        saveTimedHides(context, emptyList())
        return ZeaRepairOutcome(
            success = true,
            message = "Cancelled ${timedRecords.size} timed-hide schedule(s)."
        )
    }

    private suspend fun reconcileHiddenState(context: Context): ZeaRepairOutcome {
        val records = loadPrivateApps(context)
        if (records.isEmpty()) {
            ZeaAppHideService.sweepOrphanedHiddenApps(context, force = true)
            return ZeaRepairOutcome(true, "Registry is empty; orphan sweep completed.")
        }

        val catalog = ZeaAppCatalog.loadManagedApps(context)
        var reconciled = 0
        val failures = mutableListOf<String>()
        records.forEach { record ->
            val managedApp = catalog.firstOrNull { app ->
                app.packageName.equals(record.packageName, ignoreCase = true)
            }
            if (managedApp == null) {
                failures += "${record.displayName}: app is no longer installed"
                return@forEach
            }
            if (managedApp.hideMode == ZeaHideMode.VISIBLE) {
                val outcome = ZeaAppHideService.hideApp(context, managedApp)
                if (outcome.success) {
                    reconciled++
                } else {
                    failures += "${record.displayName}: ${outcome.message}"
                }
            } else {
                reconciled++
            }
        }

        ZeaAppHideService.sweepOrphanedHiddenApps(context, force = failures.isNotEmpty())
        ZeaAppCatalog.invalidateCatalogCache()
        return ZeaRepairOutcome(
            success = failures.isEmpty(),
            message = if (failures.isEmpty()) {
                "Hidden state reconciled for $reconciled app(s)."
            } else {
                "Reconciled $reconciled app(s); failed: ${failures.joinToString("; ")}"
            }
        )
    }

    private suspend fun restoreLauncherVisibility(context: Context): ZeaRepairOutcome {
        ZeaAppCatalog.invalidateCatalogCache()
        ZeaAppCatalog.loadManagedApps(context)
        ZeaDeviceOwnerController.flushPendingLauncherRefresh(context)
        return ZeaRepairOutcome(
            success = true,
            message = "Catalog rebuilt and launcher refresh requested."
        )
    }

    private fun repairRegistry(context: Context): ZeaRepairOutcome {
        val before = loadPrivateApps(context)
        val saved = savePrivateApps(context, before)
        val after = loadPrivateApps(context)
        val issue = ZeaProtectionHealth.registryIntegrityIssue(context)
        val removed = before.size - after.size
        return ZeaRepairOutcome(
            success = saved && issue == null,
            message = when {
                issue != null -> issue
                removed > 0 -> "Registry repaired: $removed invalid record(s) removed."
                else -> "Registry is healthy; nothing needed repair."
            }
        )
    }
}
