package com.raomuhammadnoman.zea

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ZeaHideOutcome(
    val success: Boolean,
    val message: String
)

/**
 * Hides an app on behalf of the Apps screens.
 *
 * Hiding is the same operation the Private Apps panel performs: the app is
 * recorded in the private registry, hidden by Device Owner policy, protected
 * from uninstall, and covered by the Protection Install Lock. It deliberately
 * reuses [rollbackFailedPrivateAppAdd] rather than repeating that recovery
 * logic, because a half-applied hide is the dangerous case here.
 */
object ZeaAppHideService {
    // Phase 3 history hook: every hide/unhide/timed outcome is recorded with a
    // short subject + message and a distinguishable result. Never logs payloads.
    // Successful outcomes also feed Recently Managed and the Safe Undo snapshot.
    private suspend fun recordHideOutcome(
        appContext: Context,
        displayName: String,
        packageName: String,
        outcome: ZeaHideOutcome,
        eventType: ZeaActivityEventType,
        previousMode: ZeaHideMode? = null,
        timedEndEpochMillis: Long = 0L
    ) {
        ZeaActivityLog.record(
            appContext,
            eventType,
            displayName,
            outcome.message.take(200),
            if (outcome.success) ZeaActivityResult.SUCCESS else ZeaActivityResult.FAILURE
        )
        val opLabel = if (eventType == ZeaActivityEventType.TIMED_HIDE) "Timed hide" else "Hide"
        ZeaRecentlyManaged.record(
            appContext,
            packageName,
            displayName,
            if (outcome.success) opLabel else "$opLabel (failed)"
        )
        if (outcome.success && previousMode != null) {
            val operation = if (eventType == ZeaActivityEventType.TIMED_HIDE) {
                UndoOperation.TIMED_HIDE
            } else {
                UndoOperation.HIDE
            }
            ZeaUndo.record(
                appContext,
                packageName,
                displayName,
                operation,
                previousMode,
                timedEndEpochMillis,
                appliedTimedEndEpochMillis = if (operation == UndoOperation.TIMED_HIDE) {
                    timedEndEpochMillis
                } else {
                    0L
                }
            )
        }
    }

    private suspend fun recordUnhideOutcome(
        appContext: Context,
        displayName: String,
        packageName: String,
        outcome: ZeaHideOutcome,
        previousMode: ZeaHideMode? = null,
        timedEndEpochMillis: Long = 0L
    ) {
        ZeaActivityLog.record(
            appContext,
            ZeaActivityEventType.UNHIDE,
            displayName,
            outcome.message.take(200),
            if (outcome.success) ZeaActivityResult.SUCCESS else ZeaActivityResult.FAILURE
        )
        ZeaRecentlyManaged.record(
            appContext,
            packageName,
            displayName,
            if (outcome.success) "Unhide" else "Unhide (failed)"
        )
        if (outcome.success && previousMode != null) {
            ZeaUndo.record(
                appContext,
                packageName,
                displayName,
                UndoOperation.UNHIDE,
                previousMode,
                timedEndEpochMillis
            )
        }
    }

    suspend fun isFirstHiddenApp(context: Context): Boolean = withContext(Dispatchers.IO) {
        loadPrivateApps(context.applicationContext).isEmpty()
    }

    suspend fun hideApp(
        context: Context,
        app: ZeaManagedApp
    ): ZeaHideOutcome {
        val appContext = context.applicationContext

        val ownerState = ZeaDeviceOwnerController.readUiState(appContext)
        if (!ownerState.isDeviceOwner) {
            // History evidence: full protection was requested but the device
            // owner grant that powers it is missing — a permission issue.
            ZeaActivityLog.record(
                appContext,
                ZeaActivityEventType.PERMISSION_ISSUE,
                app.displayName,
                "Device owner is not granted; fell back to lock mode",
                ZeaActivityResult.FAILURE
            )
            return hideAppInLockMode(appContext, app).also { outcome ->
                recordHideOutcome(
                    appContext,
                    app.displayName,
                    app.packageName,
                    outcome,
                    ZeaActivityEventType.HIDE,
                    ZeaHideMode.VISIBLE
                )
            }
        }
        if (ownerState.protectionPaused) {
            return ZeaHideOutcome(
                success = false,
                message = "Protection is paused. Resume protection from Private Apps before hiding an app."
            )
        }

        val record = PrivateAppRecord(
            displayName = app.displayName,
            packageName = app.packageName,
            launcherActivityName = app.launcherActivityName
        )

        val validation = withContext(Dispatchers.IO) {
            ZeaDeviceOwnerController.validatePrivateApp(appContext, record)
        }
        if (validation != null) {
            return ZeaHideOutcome(success = false, message = validation)
        }

        val recordsBeforeAdd = withContext(Dispatchers.IO) {
            loadPrivateApps(appContext)
        }
        val pendingRecoveryBeforeAdd = withContext(Dispatchers.IO) {
            ZeaDeviceOwnerController.pendingRehidePackages(appContext)
        }

        val alreadyStored = recordsBeforeAdd.any { stored ->
            stored.packageName.equals(record.packageName, ignoreCase = true)
        }
        if (alreadyStored) {
            return ZeaHideOutcome(
                success = false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "${record.displayName} is already managed by Zyro."
            )
        }

        if (recordsBeforeAdd.size >= ZeaStorageContract.MAX_PRIVATE_APPS) {
            return ZeaHideOutcome(
                success = false,
                message = "${record.displayName} was not hidden because the private registry is full (${ZeaStorageContract.MAX_PRIVATE_APPS} apps). Release an app before hiding more."
            )
        }

        val firstRecord = recordsBeforeAdd.isEmpty()

        // Safe Undo snapshot needs the mode the app was in before this hide.
        val previousMode = withContext(Dispatchers.IO) {
            if (loadTimedHides(appContext).any { stored ->
                    stored.packageName.equals(record.packageName, ignoreCase = true)
                }
            ) ZeaHideMode.TIMED else ZeaHideMode.VISIBLE
        }

        // The lock is raised before any policy change so protection is never
        // applied while installs remain open.
        val installLockResult = withContext(Dispatchers.IO) {
            ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                context = appContext,
                privateAppCount = if (firstRecord) 1 else recordsBeforeAdd.size
            )
        }
        if (!installLockResult.success) {
            return ZeaHideOutcome(
                success = false,
                message = "${record.displayName} was not hidden because the Protection Install Lock could not be verified. ${installLockResult.message}"
            )
        }

        val protectedResult = withContext(Dispatchers.IO) {
            ZeaDeviceOwnerController.ensureProtectedState(
                context = appContext,
                packageName = record.packageName,
                requireStoredLauncherVerification = false
            )
        }
        if (!protectedResult.success) {
            val rollback = rollbackFailedPrivateAppAdd(
                context = appContext,
                target = record,
                recordsBeforeAdd = recordsBeforeAdd,
                pendingRecoveryBeforeAdd = pendingRecoveryBeforeAdd
            )

            return ZeaHideOutcome(
                success = false,
                message = "${record.displayName} was not hidden. ${protectedResult.message} ${rollback.message}"
            )
        }

        val saved = withContext(Dispatchers.IO) {
            val updated = loadPrivateApps(appContext)
                .filterNot { stored ->
                    stored.packageName.equals(record.packageName, ignoreCase = true)
                } + record

            savePrivateApps(appContext, updated)
        }
        if (!saved) {
            val rollback = rollbackFailedPrivateAppAdd(
                context = appContext,
                target = record,
                recordsBeforeAdd = recordsBeforeAdd,
                pendingRecoveryBeforeAdd = pendingRecoveryBeforeAdd
            )

            return ZeaHideOutcome(
                success = false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "${record.displayName} was not hidden because Zyro could not save its protection record. ${rollback.message}"
            )
        }

        var finalRecords = withContext(Dispatchers.IO) { loadPrivateApps(appContext) }
        var finalInstallLock = withContext(Dispatchers.IO) {
            ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                context = appContext,
                privateAppCount = finalRecords.size
            )
        }
        var finalVerification = ZeaPhase1Stability.verifyPackageState(
            context = appContext,
            packageName = record.packageName,
            expectedMode = ZeaHideMode.HIDDEN
        )

        // One bounded repair pass is allowed. A successful policy API call is
        // not enough: registry, both DPM bits, and the freshly reloaded catalog
        // must all converge before the UI may report success.
        if (!finalInstallLock.success || !finalVerification.success) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "hide final verification failed package=${record.packageName}; repairing once: ${finalVerification.message}; lock=${finalInstallLock.message}"
            )
            withContext(Dispatchers.IO) {
                ZeaDeviceOwnerController.ensureProtectedState(
                    context = appContext,
                    packageName = record.packageName,
                    requireStoredLauncherVerification = false
                )
                val current = loadPrivateApps(appContext)
                if (current.none { stored -> stored.packageName.equals(record.packageName, ignoreCase = true) }) {
                    savePrivateApps(appContext, current + record)
                }
                ZeaAppCatalog.invalidateCatalogCache()
            }
            finalRecords = withContext(Dispatchers.IO) { loadPrivateApps(appContext) }
            finalInstallLock = withContext(Dispatchers.IO) {
                ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                    context = appContext,
                    privateAppCount = finalRecords.size
                )
            }
            finalVerification = ZeaPhase1Stability.verifyPackageState(
                context = appContext,
                packageName = record.packageName,
                expectedMode = ZeaHideMode.HIDDEN
            )
        }

        if (!finalInstallLock.success || !finalVerification.success) {
            val rollback = rollbackFailedPrivateAppAdd(
                context = appContext,
                target = record,
                recordsBeforeAdd = recordsBeforeAdd,
                pendingRecoveryBeforeAdd = pendingRecoveryBeforeAdd
            )
            return ZeaHideOutcome(
                success = false,
                message = "${record.displayName} was not hidden because final state verification failed. ${finalVerification.message} ${finalInstallLock.message} ${rollback.message}"
            ).also { outcome ->
                recordHideOutcome(
                    appContext,
                    record.displayName,
                    record.packageName,
                    outcome,
                    ZeaActivityEventType.HIDE
                )
            }
        }

        return ZeaHideOutcome(
            success = true,
            message = "${record.displayName} is hidden and protected from uninstall."
        ).also { outcome ->
            recordHideOutcome(
                appContext,
                record.displayName,
                record.packageName,
                outcome,
                ZeaActivityEventType.HIDE,
                previousMode
            )
        }
    }

    suspend fun hideAppForTime(
        context: Context,
        app: ZeaManagedApp,
        request: ZeaTimedHideRequest
    ): ZeaHideOutcome {
        val appContext = context.applicationContext

        // Reject past-dated requests before any platform mutation so a
        // rejected timer can never strand the app hidden with no record.
        if (request.endEpochMillis <= System.currentTimeMillis()) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "timed hide rejected package=${app.packageName} end=${request.endEpochMillis} now=${System.currentTimeMillis()} label=${request.label}"
            )
            return ZeaHideOutcome(
                success = false,
                message = "${app.displayName} was not hidden because the selected time has already passed."
            )
        }

        // Re-timing an already-managed app (e.g. extend/reduce/change end)
        // must not re-run the hide transaction: hideApp would reject it as
        // "already managed". Only the timer record and alarm get replaced.
        val alreadyManaged = withContext(Dispatchers.IO) {
            loadPrivateApps(appContext).any { stored ->
                stored.packageName.equals(app.packageName, ignoreCase = true)
            } || ZeaLockMode.isBlocked(appContext, app.packageName)
        }
        if (!alreadyManaged) {
            val hidden = hideApp(context, app)
            if (!hidden.success) {
                return hidden
            }
        }

        // If the end time passed while the hide transaction was committing,
        // roll back instead of leaving an untimed hidden orphan behind. A
        // re-time of an already-managed app needs no rollback: nothing was
        // mutated yet, so the previous timer simply stays in effect.
        if (request.endEpochMillis <= System.currentTimeMillis()) {
            if (alreadyManaged) {
                return ZeaHideOutcome(
                    success = false,
                    message = "${app.displayName} timer was not changed because the selected time has already passed."
                )
            }
            val rollback = unhideApp(appContext, app.packageName)
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "timed hide rolled back package=${app.packageName} reason=end_time_already_passed rollbackSuccess=${rollback.success}"
            )
            return ZeaHideOutcome(
                success = false,
                message = "${app.displayName} was not hidden because the selected time has already passed. ${rollback.message}"
            )
        }

        val now = System.currentTimeMillis()

        // Capture the previous timer so a failed re-time can restore it
        // instead of unhiding an app the user never asked to release.
        val previousTimedRecord = if (alreadyManaged) {
            withContext(Dispatchers.IO) {
                loadTimedHides(appContext).firstOrNull { stored ->
                    stored.packageName.equals(app.packageName, ignoreCase = true)
                }
            }
        } else {
            null
        }

        val record = ZeaTimedHideRecord(
            packageName = app.packageName,
            displayName = app.displayName,
            hiddenAtEpochMillis = now,
            hiddenUntilEpochMillis = request.endEpochMillis
        )

        val saved = withContext(Dispatchers.IO) {
            val remaining = loadTimedHides(appContext).filterNot { stored ->
                stored.packageName.equals(record.packageName, ignoreCase = true)
            }
            saveTimedHides(appContext, remaining + record)
        }
        if (!saved) {
            if (alreadyManaged) {
                return ZeaHideOutcome(
                    success = false,
                    message = "${app.displayName} timer was not changed because Zyro could not save the new end time. The previous timer is still active."
                )
            }
            val rollback = unhideApp(appContext, app.packageName)
            return ZeaHideOutcome(
                success = false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "${app.displayName} was not hidden for ${request.label} because Zyro could not save the timer. ${rollback.message}"
            )
        }

        val alarmScheduled = withContext(Dispatchers.IO) {
            ZeaTimedHide.schedule(appContext, record)
        }
        if (!alarmScheduled) {
            if (alreadyManaged) {
                restorePreviousTimer(appContext, previousTimedRecord)
                return ZeaHideOutcome(
                    success = false,
                    message = "${app.displayName} timer was not changed because the restore alarm could not be scheduled. The previous timer is still active."
                )
            }
            val rolledBack = withContext(Dispatchers.IO) {
                rollbackUnfinishedTimedHide(appContext, app.packageName)
            }
            return ZeaHideOutcome(
                success = false,
                message = "${app.displayName} was not hidden for ${request.label} because the restore alarm could not be scheduled. Rollback verified=$rolledBack."
            )
        }

        if (ZeaLockMode.isLockMode(appContext)) {
            val timedBlockSaved = ZeaLockMode.blockUntil(
                appContext,
                app.packageName,
                request.endEpochMillis
            )
            if (!timedBlockSaved) {
                if (alreadyManaged) {
                    restorePreviousTimer(appContext, previousTimedRecord)
                    return ZeaHideOutcome(
                        success = false,
                        message = "${app.displayName} timer was not changed because the timed lock state could not be saved. The previous timer is still active."
                    )
                }
                val rolledBack = withContext(Dispatchers.IO) {
                    rollbackUnfinishedTimedHide(appContext, app.packageName)
                }
                return ZeaHideOutcome(
                    success = false,
                    message = "${app.displayName} was not locked for ${request.label} because the timed lock state could not be saved. Rollback verified=$rolledBack."
                )
            }
        }

        val modeMessage = if (ZeaLockMode.isLockMode(appContext)) {
            "${app.displayName} is locked for ${request.label}. It unlocks automatically when the time ends."
        } else {
            "${app.displayName} is hidden for ${request.label}. It will become visible again automatically when the time ends."
        }
        ZeaAppCatalog.invalidateCatalogCache()

        val timedVerification = ZeaPhase1Stability.verifyPackageState(
            context = appContext,
            packageName = app.packageName,
            expectedMode = ZeaHideMode.TIMED
        )
        if (!timedVerification.success) {
            if (alreadyManaged) {
                restorePreviousTimer(appContext, previousTimedRecord)
                return ZeaHideOutcome(
                    success = false,
                    message = "${app.displayName} timer change did not reach a verified final state. ${timedVerification.message} The previous timer is still active."
                ).also { outcome ->
                    recordHideOutcome(
                        appContext,
                        app.displayName,
                        app.packageName,
                        outcome,
                        ZeaActivityEventType.TIMED_HIDE
                    )
                }
            }
            val rolledBack = withContext(Dispatchers.IO) {
                rollbackUnfinishedTimedHide(appContext, app.packageName)
            }
            return ZeaHideOutcome(
                success = false,
                message = "${app.displayName} timed hide did not reach a verified final state. ${timedVerification.message} Rollback verified=$rolledBack."
            ).also { outcome ->
                recordHideOutcome(
                    appContext,
                    app.displayName,
                    app.packageName,
                    outcome,
                    ZeaActivityEventType.TIMED_HIDE
                )
            }
        }

        return ZeaHideOutcome(
            success = true,
            message = modeMessage
        ).also { outcome ->
            recordHideOutcome(
                appContext,
                app.displayName,
                app.packageName,
                outcome,
                ZeaActivityEventType.TIMED_HIDE,
                if (alreadyManaged) ZeaHideMode.TIMED else ZeaHideMode.VISIBLE,
                // The snapshot stores the PRIOR end time (when re-timing) so a
                // Safe Undo can restore the previous timer exactly, never the
                // just-applied end and never a permanent state.
                previousTimedRecord?.hiddenUntilEpochMillis ?: request.endEpochMillis
            )
        }
    }

    /** Restores a previously captured timer after a failed re-time attempt. */
    private suspend fun restorePreviousTimer(
        appContext: Context,
        previousTimedRecord: ZeaTimedHideRecord?
    ) {
        withContext(Dispatchers.IO) {
            val remaining = loadTimedHides(appContext).filterNot { stored ->
                previousTimedRecord != null &&
                        stored.packageName.equals(previousTimedRecord.packageName, ignoreCase = true)
            }
            val restored = if (previousTimedRecord != null) {
                remaining + previousTimedRecord
            } else {
                remaining
            }
            saveTimedHides(appContext, restored)
            if (previousTimedRecord != null) {
                ZeaTimedHide.schedule(appContext, previousTimedRecord)
            }
        }
    }

    /**
     * Releases an app: hiding and uninstall protection are lifted first and
     * only after Android confirms both are cleared does the registry record
     * get dropped. This order guarantees an interrupted release can never
     * strand an app in a hidden-but-unmanaged state.
     *
     * If the release cannot be confirmed the protection record stays in
     * place, so the app remains managed and visible inside Zea for a retry.
     */
    suspend fun unhideApp(
        context: Context,
        packageName: String
    ): ZeaHideOutcome {
        val appContext = context.applicationContext

        if (!ZeaDeviceOwnerController.isDeviceOwner(appContext)) {
            return unhideAppInLockMode(appContext, packageName)
        }

        val recordsBefore = withContext(Dispatchers.IO) { loadPrivateApps(appContext) }
        val target = recordsBefore.firstOrNull { stored ->
            stored.packageName.equals(packageName, ignoreCase = true)
        }
        // Safe Undo snapshot needs the mode the app was in before this release,
        // including the prior timer's end time so an undo can re-arm it.
        val previousTimedRecord = withContext(Dispatchers.IO) {
            loadTimedHides(appContext).firstOrNull { stored ->
                stored.packageName.equals(packageName, ignoreCase = true)
            }
        }
        val previousMode =
            if (previousTimedRecord != null) ZeaHideMode.TIMED else ZeaHideMode.HIDDEN
        val previousTimedEnd = previousTimedRecord?.hiddenUntilEpochMillis ?: 0L
        if (target == null) {
            // No record means this package was never released through the
            // normal path, yet it may still carry a leftover hidden state from
            // an interrupted operation. Attempt a direct recovery instead of
            // dropping the timed evidence blindly, so an app can never end up
            // hidden with no trace left behind.
            val recovered = withContext(Dispatchers.IO) {
                releasePolicyState(appContext, packageName)
            }
            if (recovered) {
                val timerCleared = withContext(Dispatchers.IO) {
                    clearTimedHide(appContext, packageName)
                }
                val remaining = withContext(Dispatchers.IO) { loadPrivateApps(appContext) }
                val lockResult = withContext(Dispatchers.IO) {
                    ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                        context = appContext,
                        privateAppCount = remaining.size
                    )
                }
                ZeaAppCatalog.invalidateCatalogCache()
                val verification = ZeaPhase1Stability.verifyPackageState(
                    context = appContext,
                    packageName = packageName,
                    expectedMode = ZeaHideMode.VISIBLE
                )
                if (timerCleared && lockResult.success && verification.success) {
                    return ZeaHideOutcome(
                        success = true,
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        message = "This app was not managed by Zyro, but a leftover hidden state was cleared and the app is visible again."
                    )
                }
                return ZeaHideOutcome(
                    success = false,
                    message = "A leftover policy state was released, but final recovery verification did not converge. ${verification.message} ${lockResult.message}"
                )
            }
            return ZeaHideOutcome(
                success = false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "This app is not currently managed by Zyro."
            )
        }

        val activePackage = ZeaDeviceOwnerController.activePrivatePackage(appContext)
        if (activePackage.equals(target.packageName, ignoreCase = true)) {
            return ZeaHideOutcome(
                success = false,
                message = "${target.displayName} still has an active private session. Return Home, wait for protection to restore, and try again."
            )
        }

        val released = withContext(Dispatchers.IO) {
            releasePolicyState(appContext, target.packageName)
        }

        if (!released) {
            val restored = withContext(Dispatchers.IO) {
                ZeaDeviceOwnerController.ensureProtectedState(
                    context = appContext,
                    packageName = target.packageName,
                    requireStoredLauncherVerification = false
                )
            }

            return ZeaHideOutcome(
                success = false,
                message = "${target.displayName} could not be unhidden, so its protection record was kept. ${restored.message}"
            )
        }

        val recordRemoved = withContext(Dispatchers.IO) {
            val updated = recordsBefore.filterNot { stored ->
                stored.packageName.equals(target.packageName, ignoreCase = true)
            }
            // One explicit, logged retry: a failed registry write must never
            // be silently absorbed, and the app must never stay managed
            // while already visible.
            val firstAttempt = savePrivateApps(appContext, updated)
            if (!firstAttempt) {
                Log.w(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "registry save retry package=${target.packageName} operation=unhide_record_removal"
                )
                savePrivateApps(appContext, updated)
            } else {
                true
            }
        }
        if (!recordRemoved) {
            // Fail closed immediately. The durable private/timed record is still
            // present, so restore platform protection rather than leaving a
            // visible-but-managed mismatch until some later reconciliation.
            val restored = withContext(Dispatchers.IO) {
                ZeaDeviceOwnerController.ensureProtectedState(
                    context = appContext,
                    packageName = target.packageName,
                    requireStoredLauncherVerification = false
                )
            }
            val timedStillPresent = withContext(Dispatchers.IO) {
                loadTimedHides(appContext).any { stored ->
                    stored.packageName.equals(target.packageName, ignoreCase = true)
                }
            }
            val expectedMode = if (timedStillPresent) ZeaHideMode.TIMED else ZeaHideMode.HIDDEN
            val restoredVerification = ZeaPhase1Stability.verifyPackageState(
                context = appContext,
                packageName = target.packageName,
                expectedMode = expectedMode
            )
            return ZeaHideOutcome(
                success = false,
                message = "${target.displayName} could not be released because its protection record could not be updated. Protection was restored=${restored.success && restoredVerification.success}. ${restoredVerification.message}"
            )
        }

        withContext(Dispatchers.IO) {
            clearTimedHide(appContext, target.packageName)
        }

        var remaining = withContext(Dispatchers.IO) { loadPrivateApps(appContext) }
        var lockResult = withContext(Dispatchers.IO) {
            ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                context = appContext,
                privateAppCount = remaining.size
            )
        }
        var finalVerification = ZeaPhase1Stability.verifyPackageState(
            context = appContext,
            packageName = target.packageName,
            expectedMode = ZeaHideMode.VISIBLE
        )

        if (!lockResult.success || !finalVerification.success) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "unhide final verification failed package=${target.packageName}; repairing once: ${finalVerification.message}; lock=${lockResult.message}"
            )
            withContext(Dispatchers.IO) {
                repairPartialVisibleState(appContext, target.packageName)
                syncBookkeepingToVerifiedVisibleState(appContext, target.packageName)
                ZeaAppCatalog.invalidateCatalogCache()
            }
            remaining = withContext(Dispatchers.IO) { loadPrivateApps(appContext) }
            lockResult = withContext(Dispatchers.IO) {
                ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                    context = appContext,
                    privateAppCount = remaining.size
                )
            }
            finalVerification = ZeaPhase1Stability.verifyPackageState(
                context = appContext,
                packageName = target.packageName,
                expectedMode = ZeaHideMode.VISIBLE
            )
        }

        if (!lockResult.success || !finalVerification.success) {
            return ZeaHideOutcome(
                success = false,
                message = "${target.displayName} reached a partial release state but final verification failed. ${finalVerification.message} ${lockResult.message}"
            ).also { outcome ->
                recordUnhideOutcome(appContext, target.displayName, target.packageName, outcome)
            }
        }

        val lockNote = if (remaining.isEmpty()) {
            " App installs and updates are allowed again."
        } else {
            ""
        }

        return ZeaHideOutcome(
            success = true,
            message = "${target.displayName} is visible again.$lockNote"
        ).also { outcome ->
            recordUnhideOutcome(
                appContext,
                target.displayName,
                target.packageName,
                outcome,
                previousMode,
                previousTimedEnd
            )
        }
    }

    /**
     * Converts a timed hide into a permanent hide: the expiry alarm is
     * cancelled AND the timed record is removed, so a later reboot or expiry
     * sweep can never resurrect the timer and release the app.
     */
    suspend fun convertTimedHideToPermanent(
        context: Context,
        packageName: String
    ): ZeaHideOutcome {
        val appContext = context.applicationContext
        val timedRecord = withContext(Dispatchers.IO) {
            loadTimedHides(appContext).firstOrNull { stored ->
                stored.packageName.equals(packageName, ignoreCase = true)
            }
        } ?: return ZeaHideOutcome(
            success = false,
            message = "This app does not have an active timer."
        )

        val cleared = withContext(Dispatchers.IO) {
            clearTimedHide(appContext, packageName)
        }
        if (!cleared) {
            return ZeaHideOutcome(
                success = false,
                message = "${timedRecord.displayName} timer could not be removed. It is still active."
            )
        }

        // Verify the app is still protected after dropping the timer record.
        val stillManaged = withContext(Dispatchers.IO) {
            loadPrivateApps(appContext).any { stored ->
                stored.packageName.equals(packageName, ignoreCase = true)
            } || ZeaLockMode.isBlocked(appContext, packageName)
        }
        if (!stillManaged) {
            // The timer is gone but the app is not managed; fail closed by
            // restoring the timer so the app cannot be stranded unprotected
            // without a record.
            withContext(Dispatchers.IO) {
                saveTimedHides(appContext, loadTimedHides(appContext) + timedRecord)
                ZeaTimedHide.schedule(appContext, timedRecord)
            }
            return ZeaHideOutcome(
                success = false,
                message = "${timedRecord.displayName} could not be converted to permanent because its protection record is missing. The timer was restored."
            )
        }

        ZeaAppCatalog.invalidateCatalogCache()
        return ZeaHideOutcome(
            success = true,
            message = "${timedRecord.displayName} timer cancelled; the app stays hidden permanently."
        ).also { outcome ->
            ZeaActivityLog.record(
                appContext,
                ZeaActivityEventType.TIMED_HIDE,
                timedRecord.displayName,
                outcome.message.take(200),
                ZeaActivityResult.SUCCESS
            )
        }
    }

    /**
     * App Lock mode hide: records the app and arms accessibility-based
     * enforcement. No Device Owner policy calls are made on this path.
     */
    private suspend fun hideAppInLockMode(
        appContext: Context,
        app: ZeaManagedApp
    ): ZeaHideOutcome = withContext(Dispatchers.IO) {
        val launchable = try {
            appContext.packageManager.getLaunchIntentForPackage(app.packageName) != null
        } catch (_: RuntimeException) {
            false
        }
        if (!launchable) {
            return@withContext ZeaHideOutcome(
                success = false,
                message = "${app.displayName} is not installed on this device."
            )
        }

        val recordsBefore = loadPrivateApps(appContext)
        if (recordsBefore.any { stored ->
                stored.packageName.equals(app.packageName, ignoreCase = true)
            }) {
            return@withContext ZeaHideOutcome(
                success = false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "${app.displayName} is already managed by Zyro."
            )
        }

        if (!ZeaLockMode.block(appContext, app.packageName)) {
            return@withContext ZeaHideOutcome(
                success = false,
                message = "${app.displayName} could not be locked. Please try again."
            )
        }

        val record = PrivateAppRecord(
            displayName = app.displayName,
            packageName = app.packageName,
            launcherActivityName = app.launcherActivityName
        )

        if (!savePrivateApps(appContext, recordsBefore + record)) {
            ZeaLockMode.unblock(appContext, app.packageName)
            return@withContext ZeaHideOutcome(
                success = false,
                message = "${app.displayName} could not be saved. Please try again."
            )
        }

        ZeaAppCatalog.invalidateCatalogCache()
        val verification = ZeaPhase1Stability.verifyPackageState(
            context = appContext,
            packageName = app.packageName,
            expectedMode = ZeaHideMode.HIDDEN
        )
        if (!verification.success) {
            savePrivateApps(appContext, recordsBefore)
            ZeaLockMode.unblock(appContext, app.packageName)
            ZeaLockMode.clearSessionAllow(appContext, app.packageName)
            ZeaAppCatalog.invalidateCatalogCache()
            return@withContext ZeaHideOutcome(
                success = false,
                message = "${app.displayName} lock did not reach a verified final state. ${verification.message}"
            )
        }

        ZeaHideOutcome(
            success = true,
            message = "${app.displayName} is locked. Opening it outside Zyro returns you to the home screen."
        )
    }

    /**
     * App Lock mode release: drops the record and removes the package from
     * accessibility enforcement in one pass.
     */
    private suspend fun unhideAppInLockMode(
        appContext: Context,
        packageName: String
    ): ZeaHideOutcome = withContext(Dispatchers.IO) {
        val recordsBefore = loadPrivateApps(appContext)
        val target = recordsBefore.firstOrNull { stored ->
            stored.packageName.equals(packageName, ignoreCase = true)
        }

        if (target == null) {
            val hadBlock = ZeaLockMode.blockedPackages(appContext).any { blocked ->
                blocked.equals(packageName, ignoreCase = true)
            }
            val hadTimer = loadTimedHides(appContext).any { stored ->
                stored.packageName.equals(packageName, ignoreCase = true)
            }
            if (!hadBlock && !hadTimer) {
                return@withContext ZeaHideOutcome(
                    success = false,
                    message = "This app is not currently managed by Zyro."
                )
            }

            val unblocked = ZeaLockMode.unblock(appContext, packageName)
            ZeaLockMode.clearSessionAllow(appContext, packageName)
            val timerCleared = clearTimedHide(appContext, packageName)
            ZeaAppCatalog.invalidateCatalogCache()
            val verification = ZeaPhase1Stability.verifyPackageState(
                context = appContext,
                packageName = packageName,
                expectedMode = ZeaHideMode.VISIBLE
            )
            return@withContext ZeaHideOutcome(
                success = unblocked && timerCleared && verification.success,
                message = if (unblocked && timerCleared && verification.success) {
                    "A leftover App Lock state was cleared and the app is visible again."
                } else {
                    "A leftover App Lock state could not be fully verified as released. ${verification.message}"
                }
            )
        }

        // Release enforcement first, but keep the registry row until that write
        // is confirmed. If registry removal fails, restore the block so the
        // durable record and enforcement layer remain fail-closed together.
        if (!ZeaLockMode.unblock(appContext, target.packageName)) {
            return@withContext ZeaHideOutcome(
                success = false,
                message = "${target.displayName} could not be unlocked. Its protection record was kept."
            )
        }
        ZeaLockMode.clearSessionAllow(appContext, target.packageName)

        val updated = recordsBefore.filterNot { stored ->
            stored.packageName.equals(target.packageName, ignoreCase = true)
        }
        if (!savePrivateApps(appContext, updated)) {
            // Preserve the original contract on rollback. A timed app must stay
            // timed; restoring it with block() would silently make it permanent.
            val existingTimer = loadTimedHides(appContext).firstOrNull { stored ->
                stored.packageName.equals(target.packageName, ignoreCase = true)
            }
            val restored = if (existingTimer != null) {
                ZeaLockMode.blockUntil(
                    appContext,
                    target.packageName,
                    existingTimer.hiddenUntilEpochMillis
                )
            } else {
                ZeaLockMode.block(appContext, target.packageName)
            }
            return@withContext ZeaHideOutcome(
                success = false,
                message = "${target.displayName} could not be released because its record could not be updated. Original lock contract restored=$restored."
            )
        }

        var timerCleared = clearTimedHide(appContext, target.packageName)
        ZeaAppCatalog.invalidateCatalogCache()
        var verification = ZeaPhase1Stability.verifyPackageState(
            context = appContext,
            packageName = target.packageName,
            expectedMode = ZeaHideMode.VISIBLE
        )

        if (!timerCleared || !verification.success) {
            ZeaLockMode.unblock(appContext, target.packageName)
            ZeaLockMode.clearSessionAllow(appContext, target.packageName)
            savePrivateApps(appContext, updated)
            timerCleared = clearTimedHide(appContext, target.packageName)
            ZeaAppCatalog.invalidateCatalogCache()
            verification = ZeaPhase1Stability.verifyPackageState(
                context = appContext,
                packageName = target.packageName,
                expectedMode = ZeaHideMode.VISIBLE
            )
        }

        if (!timerCleared || !verification.success) {
            return@withContext ZeaHideOutcome(
                success = false,
                message = "${target.displayName} reached a partial App Lock release state but final verification failed. ${verification.message}"
            )
        }

        ZeaHideOutcome(
            success = true,
            message = "${target.displayName} is unlocked and visible again."
        ).also { outcome ->
            recordUnhideOutcome(
                appContext,
                target.displayName,
                target.packageName,
                outcome,
                ZeaHideMode.HIDDEN
            )
        }
    }

    /**
     * Step-3 recovery reconciliation: the package's complete hide target state
     * (hidden + uninstall-blocked) was already VERIFIED on the platform, so
     * synchronize Zea bookkeeping with that truth WITHOUT re-applying any
     * Device Owner mutation. Idempotent: re-running never duplicates rows.
     */
    fun syncBookkeepingToVerifiedHiddenState(
        context: Context,
        packageName: String
    ): Boolean {
        val appContext = context.applicationContext
        return try {
            val info = try {
                appContext.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                )
            } catch (_: Exception) {
                null
            }
            val stored = if (info != null) adoptOrphanedHiddenApp(appContext, info) else false
            ZeaDeviceOwnerController.clearPendingRehidePackage(appContext, packageName)
            if (stored) {
                ZeaAppCatalog.invalidateCatalogCache()
            }
            stored
        } catch (error: RuntimeException) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "verified-hidden bookkeeping sync failed package=$packageName",
                error
            )
            false
        }
    }

    /**
     * Step-3 recovery reconciliation counterpart for a package whose complete
     * unhide target state (visible + unprotected) was already VERIFIED on the
     * platform: drop stale hidden/timed/pending bookkeeping without redundant
     * Device Owner writes. Idempotent.
     */
    fun syncBookkeepingToVerifiedVisibleState(
        context: Context,
        packageName: String
    ): Boolean {
        val appContext = context.applicationContext
        return try {
            val existing = loadPrivateApps(appContext)
            val registrySaved = if (existing.any { stored ->
                    stored.packageName.equals(packageName, ignoreCase = true)
                }
            ) {
                savePrivateApps(
                    appContext,
                    existing.filterNot { stored ->
                        stored.packageName.equals(packageName, ignoreCase = true)
                    }
                )
            } else {
                true
            }
            if (!registrySaved) {
                // Preserve timed/pending recovery evidence when the durable
                // registry cannot be updated. Callers can then fail closed and
                // retry without silently changing a temporary contract.
                return false
            }
            val timerCleared = clearTimedHide(appContext, packageName)
            if (!timerCleared) return false
            ZeaDeviceOwnerController.clearPendingRehidePackage(appContext, packageName)
            ZeaAppCatalog.invalidateCatalogCache()
            true
        } catch (error: RuntimeException) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "verified-visible bookkeeping sync failed package=$packageName",
                error
            )
            false
        }
    }

    /**
     * Established transaction policy repair for a partial hide target: the
     * production protected-state transaction completes whichever protection
     * bit is missing and verifies both. Fails closed on open install lock.
     */
    fun repairPartialHiddenState(context: Context, packageName: String): Boolean {
        return try {
            ZeaDeviceOwnerController.ensureProtectedState(context, packageName).success
        } catch (error: RuntimeException) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "partial hide repair failed package=$packageName",
                error
            )
            false
        }
    }

    /**
     * Established transaction policy repair for a partial unhide target:
     * releases remaining protection bits and verifies both are clear.
     */
    fun repairPartialVisibleState(context: Context, packageName: String): Boolean {
        return releasePolicyState(context, packageName)
    }

    /**
     * Fail-closed rollback for an interrupted timed-hide transaction whose
     * durable timer contract never landed: a temporary hide must not silently
     * become permanent, so the established policy releases the policy state
     * and clears any partial timer/registry evidence.
     */
    fun rollbackUnfinishedTimedHide(context: Context, packageName: String): Boolean {
        val released = if (ZeaLockMode.isLockMode(context)) {
            val unblocked = ZeaLockMode.unblock(context, packageName)
            ZeaLockMode.clearSessionAllow(context, packageName)
            unblocked
        } else {
            releasePolicyState(context, packageName)
        }
        val timerCleared = clearTimedHide(context, packageName)
        val existing = loadPrivateApps(context)
        val registryCleared = if (existing.any { stored ->
                stored.packageName.equals(packageName, ignoreCase = true)
            }
        ) {
            savePrivateApps(
                context,
                existing.filterNot { stored ->
                    stored.packageName.equals(packageName, ignoreCase = true)
                }
            )
        } else {
            true
        }
        ZeaAppCatalog.invalidateCatalogCache()
        return released && timerCleared && registryCleared
    }

    /**
     * Safety net so no app can ever be stranded hidden without a Zea record.
     *
     * Any installed package that Android reports as hidden while Zea holds no
     * private or timed record for it is an orphan from an interrupted older
     * flow. Such packages are adopted into the registry instead of released:
     * a device-owner-hidden app must never be un-hidden automatically, because
     * the hidden state is protection state. Adoption keeps them invisible to
     * launchers and manageable from Zea's own lists. Throttled because it
     * walks every installed package.
     */
    fun sweepOrphanedHiddenApps(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!ZeaDeviceOwnerController.isDeviceOwner(appContext)) return

        val now = System.currentTimeMillis()
        if (!force && now - lastOrphanSweepMillis < ORPHAN_SWEEP_MIN_INTERVAL_MILLIS) return
        lastOrphanSweepMillis = now

        try {
            val managed = loadPrivateApps(appContext)
                .mapTo(mutableSetOf()) { it.packageName.lowercase(Locale.ROOT) }
            val timed = loadTimedHides(appContext)
                .mapTo(mutableSetOf()) { it.packageName.lowercase(Locale.ROOT) }
            val sessionPackage = ZeaDeviceOwnerController.activePrivatePackage(appContext)

            val installed = try {
                appContext.packageManager.getInstalledApplications(
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                )
            } catch (_: RuntimeException) {
                emptyList()
            }

            var adopted = 0
            for (info in installed) {
                val pkg = info.packageName
                val key = pkg.lowercase(Locale.ROOT)
                if (key in managed || key in timed) continue
                if (sessionPackage != null && pkg.equals(sessionPackage, ignoreCase = true)) continue

                val orphaned = try {
                    ZeaDeviceOwnerController.isHidden(appContext, pkg) == true
                } catch (_: RuntimeException) {
                    false
                }
                if (!orphaned) continue

                val stored = adoptOrphanedHiddenApp(appContext, info)
                Log.i(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "orphan sweep package=$pkg adopted=$stored"
                )
                if (stored) adopted++
            }

            if (adopted > 0) {
                ZeaAppCatalog.invalidateCatalogCache()
                // History evidence for the recovery sweep result.
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    ZeaActivityLog.record(
                        appContext,
                        ZeaActivityEventType.RECOVERY,
                        "Orphan sweep",
                        "Adopted $adopted orphaned hidden app(s) into the registry",
                        ZeaActivityResult.SUCCESS
                    )
                }
            }
        } catch (error: RuntimeException) {
            Log.w(ZEA_DEVICE_OWNER_LOG_TAG, "orphan sweep aborted", error)
        }
    }

    private fun adoptOrphanedHiddenApp(
        context: Context,
        info: ApplicationInfo
    ): Boolean {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val displayName = try {
            info.loadLabel(packageManager).toString()
        } catch (_: RuntimeException) {
            ""
        }.trim().ifBlank { info.packageName }
        val record = PrivateAppRecord(
            displayName = displayName,
            packageName = info.packageName,
            launcherActivityName = resolveLauncherActivityName(packageManager, info.packageName)
        )
        val updated = loadPrivateApps(appContext)
            .filterNot { stored ->
                stored.packageName.equals(record.packageName, ignoreCase = true)
            } + record
        val saved = savePrivateApps(appContext, updated)
        if (!saved) return false

        // sanitizePrivateApps can drop a record whose launcher activity could
        // not be resolved; only report adoption when the record really stuck.
        return loadPrivateApps(appContext).any { stored ->
            stored.packageName.equals(info.packageName, ignoreCase = true)
        }
    }

    private fun resolveLauncherActivityName(
        packageManager: PackageManager,
        packageName: String
    ): String {
        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: RuntimeException) {
            null
        }
        launchIntent?.component?.className?.let { return it }

        // A package hidden at the Device Owner level resolves like an
        // uninstalled package unless the query explicitly opts back in, so
        // recovery reconciliation can still identify its launcher activity.
        val includeUnavailable = PackageManager.MATCH_UNINSTALLED_PACKAGES

        val launcherActivities = try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName),
                includeUnavailable
            )
        } catch (_: RuntimeException) {
            emptyList()
        }
        launcherActivities.firstOrNull()?.activityInfo?.name?.let { resolvedName ->
            if (resolvedName.isNotBlank()) return resolvedName
        }

        return try {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or includeUnavailable
            )
                .activities
                ?.firstOrNull { activity -> activity.name.contains('.') }
                ?.name ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Clears uninstall protection and the hidden flag, then verifies both at
     * the policy level. Never throws into callers: any firmware error becomes
     * a false result so callers keep their records and can retry safely.
     */
    private fun releasePolicyState(context: Context, packageName: String): Boolean {
        return try {
            ZeaDeviceOwnerController.clearPendingRehidePackage(context, packageName)

            ZeaDeviceOwnerController.setUninstallBlocked(
                context = context,
                packageName = packageName,
                blocked = false,
                requireStoredLauncherVerification = false
            )
            ZeaDeviceOwnerController.setHidden(
                context = context,
                packageName = packageName,
                hidden = false,
                requireStoredLauncherVerification = false
            )

            ZeaDeviceOwnerController.isHidden(context, packageName) == false &&
                    ZeaDeviceOwnerController.isUninstallBlocked(context, packageName) == false
        } catch (error: RuntimeException) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "policy release failed package=$packageName",
                error
            )
            false
        }
    }

    private fun clearTimedHide(context: Context, packageName: String): Boolean {
        ZeaTimedHide.cancel(context, packageName)
        val remaining = loadTimedHides(context).filterNot { stored ->
            stored.packageName.equals(packageName, ignoreCase = true)
        }
        return saveTimedHides(context, remaining)
    }

    @Volatile
    private var lastOrphanSweepMillis: Long = 0L

    private const val ORPHAN_SWEEP_MIN_INTERVAL_MILLIS: Long = 5L * 60L * 1000L
}
