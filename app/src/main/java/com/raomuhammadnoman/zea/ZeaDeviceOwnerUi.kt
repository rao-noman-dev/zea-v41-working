package com.raomuhammadnoman.zea

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ZeaDeviceOwnerPanelSnapshot(
    val ownerState: ZeaDeviceOwnerUiState,
    val privateApps: List<PrivateAppRecord>,
    val hiddenStates: Map<String, Boolean?>,
    val uninstallBlockedStates: Map<String, Boolean?>
)

internal data class ZeaFailedAddRollbackResult(
    val rollbackVerified: Boolean,
    val failClosedRecoveryVerified: Boolean,
    val message: String
)

/**
 * Shared by this panel and the Apps screens so a failed hide is undone by one
 * audited implementation rather than two that could drift apart.
 */
internal suspend fun rollbackFailedPrivateAppAdd(
    context: Context,
    target: PrivateAppRecord,
    recordsBeforeAdd: List<PrivateAppRecord>,
    pendingRecoveryBeforeAdd: Set<String>
): ZeaFailedAddRollbackResult {
    val rollbackLockCount = when {
        recordsBeforeAdd.isNotEmpty() -> recordsBeforeAdd.size
        pendingRecoveryBeforeAdd.isNotEmpty() -> 1
        else -> 0
    }
    val expectedInstallLock = rollbackLockCount > 0

    val currentRecords = loadPrivateApps(context)
    val registryRestoreRequested = if (currentRecords == recordsBeforeAdd) {
        true
    } else {
        savePrivateApps(context, recordsBeforeAdd)
    }
    val recordsAfterRestore = loadPrivateApps(context)
    val targetRecordPresent = recordsAfterRestore.any { stored ->
        stored.packageName.equals(target.packageName, ignoreCase = true)
    }
    val registryRollbackVerified =
        registryRestoreRequested &&
            recordsAfterRestore == recordsBeforeAdd &&
            !targetRecordPresent

    ZeaDeviceOwnerController.clearPendingRehidePackage(context, target.packageName)
    ZeaDeviceOwnerController.setUninstallBlocked(
        context = context,
        packageName = target.packageName,
        blocked = false,
        requireStoredLauncherVerification = false
    )
    ZeaDeviceOwnerController.setHidden(
        context = context,
        packageName = target.packageName,
        hidden = false,
        requireStoredLauncherVerification = false
    )

    // Match the proven V33 restoration transaction's bounded stale-reconciliation defense.
    kotlinx.coroutines.delay(1000)
    var hiddenAfterRelease = ZeaDeviceOwnerController.isHidden(context, target.packageName)
    var uninstallBlockedAfterRelease = ZeaDeviceOwnerController.isUninstallBlocked(
        context,
        target.packageName
    )
    if (hiddenAfterRelease != false || uninstallBlockedAfterRelease != false) {
        ZeaDeviceOwnerController.clearPendingRehidePackage(context, target.packageName)
        ZeaDeviceOwnerController.setUninstallBlocked(
            context = context,
            packageName = target.packageName,
            blocked = false,
            requireStoredLauncherVerification = false
        )
        ZeaDeviceOwnerController.setHidden(
            context = context,
            packageName = target.packageName,
            hidden = false,
            requireStoredLauncherVerification = false
        )
        kotlinx.coroutines.delay(750)
        hiddenAfterRelease = ZeaDeviceOwnerController.isHidden(context, target.packageName)
        uninstallBlockedAfterRelease = ZeaDeviceOwnerController.isUninstallBlocked(
            context,
            target.packageName
        )
    }
    ZeaDeviceOwnerController.clearPendingRehidePackage(context, target.packageName)
    if (pendingRecoveryBeforeAdd.any { pending ->
            pending.equals(target.packageName, ignoreCase = true)
        }) {
        ZeaDeviceOwnerController.markPendingRehidePackage(context, target.packageName)
    }

    val targetReleaseVerified =
        hiddenAfterRelease == false && uninstallBlockedAfterRelease == false

    // The user-wide install lock may be rolled back only after both the registry
    // and package release are proven. Otherwise skip lock-clear and recover fail-closed.
    if (registryRollbackVerified && targetReleaseVerified) {
        val installLockRollback = ZeaDeviceOwnerController.reconcileProtectionInstallLock(
            context,
            rollbackLockCount
        )
        val installLockAfterRollback = ZeaDeviceOwnerController.queryProtectionInstallLock(context)
        val normalRollbackVerified =
            installLockRollback.success && installLockAfterRollback == expectedInstallLock
        if (normalRollbackVerified) {
            return ZeaFailedAddRollbackResult(
                rollbackVerified = true,
                failClosedRecoveryVerified = false,
                message =
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    "Zyro verified that the failed add transaction was fully rolled back to the previous private-app, emergency-recovery, and Protection Install Lock state."
            )
        }
    }

    val recoveredRecords = recordsBeforeAdd.filterNot { stored ->
        stored.packageName.equals(target.packageName, ignoreCase = true)
    } + target
    val recoveryRecordSaved = savePrivateApps(context, recoveredRecords)
    val recoveryRecordsAfterSave = loadPrivateApps(context)
    val recoveredRecordMatchCount = recoveryRecordsAfterSave.count { stored ->
        stored.packageName.equals(target.packageName, ignoreCase = true)
    }
    val recoveredOtherRecords = recoveryRecordsAfterSave.filterNot { stored ->
        stored.packageName.equals(target.packageName, ignoreCase = true)
    }
    val recoveryRecordVerified =
        recoveryRecordSaved &&
            recoveredRecordMatchCount == 1 &&
            recoveredOtherRecords == recordsBeforeAdd

    if (!recoveryRecordVerified) {
        // No persistent target record means normal count-based reconciliation could
        // incorrectly clear the lock on a first-add failure. Keep an emergency marker
        // and explicitly retain the user-wide install lock fail-closed instead.
        ZeaDeviceOwnerController.markPendingRehidePackage(context, target.packageName)
        ZeaDeviceOwnerController.setProtectionInstallLock(context, true)
        val emergencyInstallLockAfter = ZeaDeviceOwnerController.queryProtectionInstallLock(context)
        val emergencyInstallLockVerified = emergencyInstallLockAfter == true
        return ZeaFailedAddRollbackResult(
            rollbackVerified = false,
            failClosedRecoveryVerified = false,
            message = if (emergencyInstallLockVerified) {
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Zyro could not verify rollback or persist the fail-closed recovery record. The target remains in emergency recovery tracking and the Protection Install Lock remains active. Do not assume the target is visible or normally managed."
            } else {
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Zyro could not verify rollback or persist the fail-closed recovery record. The target remains in emergency recovery tracking, but the Protection Install Lock could not be verified active. Do not assume the target is visible or normally managed; reconcile Zyro before continuing."
            }
        )
    }

    val recoveryLockResult = ZeaDeviceOwnerController.reconcileProtectionInstallLock(
        context,
        recoveryRecordsAfterSave.size
    )
    val recoveryProtectionResult = ZeaDeviceOwnerController.ensureProtectedState(
        context = context,
        packageName = target.packageName,
        requireStoredLauncherVerification = false
    )

    val recoveryRecordsFinal = loadPrivateApps(context)
    val recoveryFinalRecordMatchCount = recoveryRecordsFinal.count { stored ->
        stored.packageName.equals(target.packageName, ignoreCase = true)
    }
    val recoveryOtherRecordsFinal = recoveryRecordsFinal.filterNot { stored ->
        stored.packageName.equals(target.packageName, ignoreCase = true)
    }
    val hiddenAfterRecovery = ZeaDeviceOwnerController.isHidden(context, target.packageName)
    val uninstallBlockedAfterRecovery = ZeaDeviceOwnerController.isUninstallBlocked(
        context,
        target.packageName
    )
    val installLockAfterRecovery = ZeaDeviceOwnerController.queryProtectionInstallLock(context)
    val failClosedRecoveryVerified =
        recoveryFinalRecordMatchCount == 1 &&
            recoveryOtherRecordsFinal == recordsBeforeAdd &&
            recoveryLockResult.success &&
            recoveryProtectionResult.success &&
            hiddenAfterRecovery == true &&
            uninstallBlockedAfterRecovery == true &&
            installLockAfterRecovery == true

    return if (failClosedRecoveryVerified) {
        ZeaFailedAddRollbackResult(
            rollbackVerified = false,
            failClosedRecoveryVerified = true,
            message =
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Rollback could not be fully verified, so Zyro restored the app record, Protection Install Lock, hidden state, and uninstall protection fail-closed."
        )
    } else {
        ZeaDeviceOwnerController.markPendingRehidePackage(context, target.packageName)
        if (installLockAfterRecovery != true) {
            ZeaDeviceOwnerController.setProtectionInstallLock(context, true)
        }
        ZeaFailedAddRollbackResult(
            rollbackVerified = false,
            failClosedRecoveryVerified = false,
            message =
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Add rollback and fail-closed recovery could not be fully verified. The target remains in emergency recovery tracking and Zyro retains the Protection Install Lock fail-closed where Android permits. Do not assume the app is visible or normally managed; review Zyro protection state before continuing."
        )
    }
}

@Composable
internal fun ZeaDeviceOwnerPrivateAppsPanel() {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()
    val operationGate = remember { AtomicBoolean(false) }
    val lifecycleResumeRefreshArmed = remember { AtomicBoolean(false) }

    var ownerState by remember { mutableStateOf(ZeaDeviceOwnerController.readUiState(context)) }
    var privateApps by remember { mutableStateOf<List<PrivateAppRecord>>(emptyList()) }
    var hiddenStates by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    var uninstallBlockedStates by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    var appInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var operationInProgress by remember { mutableStateOf(false) }
    var pendingFirstProtectionName by remember { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        statusMessage = if (granted) {
            "Notification permission granted for the visible private-session safety monitor."
        } else {
            "Notification permission was not granted. Android may still show the foreground-service task indicator."
        }
    }

    suspend fun reloadState(reconcile: Boolean = false) {
        val snapshot = withContext(Dispatchers.IO) {
            if (reconcile && ZeaDeviceOwnerController.isDeviceOwner(context)) {
                ZeaDeviceOwnerController.reconcileHiddenState(context, "manual UI refresh")
            }
            ZeaPrivateAppLookupCache.invalidate("device owner UI refresh")
            val apps = loadPrivateApps(context)
            val hidden = apps.associate { app ->
                app.packageName to ZeaDeviceOwnerController.isHidden(context, app.packageName)
            }
            val uninstallBlocked = apps.associate { app ->
                app.packageName to ZeaDeviceOwnerController.isUninstallBlocked(
                    context,
                    app.packageName
                )
            }
            ZeaDeviceOwnerPanelSnapshot(
                ownerState = ZeaDeviceOwnerController.readUiState(context),
                privateApps = apps,
                hiddenStates = hidden,
                uninstallBlockedStates = uninstallBlocked
            )
        }
        ownerState = snapshot.ownerState
        privateApps = snapshot.privateApps
        hiddenStates = snapshot.hiddenStates
        uninstallBlockedStates = snapshot.uninstallBlockedStates
    }

    fun runOperation(block: suspend () -> Unit) {
        if (!operationGate.compareAndSet(false, true)) return
        operationInProgress = true
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                statusMessage = "The Device Owner operation failed safely."
            } finally {
                operationGate.set(false)
                operationInProgress = false
            }
        }
    }

    fun startAddOrRecover(requestedName: String, firstProtectionConfirmed: Boolean) {
        runOperation {
                        ownerState = ZeaDeviceOwnerController.readUiState(context)
                        if (!ownerState.isDeviceOwner) {
                            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                            statusMessage = "Add-and-hide is disabled until Zyro is Device Owner on a dedicated test device."
                            return@runOperation
                        }
                        if (ownerState.protectionPaused) {
                            val requestedSafety =
                                ZeaSafetyPolicy.evaluateRequestedAppName(requestedName)
                            if (!requestedSafety.allowed) {
                                statusMessage = requestedSafety.message
                                return@runOperation
                            }

                            val normalizedRequestedName =
                                normalizePrivateAppName(requestedName)
                            val registryMatches = zeaAppRegistry.filter { entry ->
                                entry.packageName != null &&
                                    sequenceOf(entry.key, entry.displayName)
                                        .plus(entry.aliases.asSequence())
                                        .map(::normalizePrivateAppName)
                                        .any { name ->
                                            name == normalizedRequestedName
                                        }
                            }
                            if (registryMatches.size != 1) {
                                statusMessage =
                                    "Paused recovery requires one exact configured app name."
                                return@runOperation
                            }

                            val registryEntry = registryMatches.single()
                            val registrySafety =
                                ZeaSafetyPolicy.evaluateRegistryEntry(registryEntry)
                            if (!registrySafety.allowed) {
                                statusMessage = registrySafety.message
                                return@runOperation
                            }

                            val installedPackages = withContext(Dispatchers.IO) {
                                (sequenceOf(registryEntry.packageName)
                                    .plus(registryEntry.alternatePackageNames.asSequence())
                                    .filterNotNull()
                                    .map(String::trim)
                                    .filter(String::isNotBlank)
                                    .distinct()
                                    .filter { packageName ->
                                        ZeaDeviceOwnerController.isPackageInstalled(
                                            context,
                                            packageName
                                        )
                                    }
                                    .toList())
                            }
                            if (installedPackages.size != 1) {
                                statusMessage =
                                    "Paused recovery could not identify exactly one installed package."
                                return@runOperation
                            }

                            val recoveryPackage = installedPackages.single()
                            val alreadyStored = withContext(Dispatchers.IO) {
                                loadPrivateApps(context).any { stored ->
                                    stored.packageName.equals(
                                        recoveryPackage,
                                        ignoreCase = true
                                    )
                                }
                            }
                            if (alreadyStored) {
                                statusMessage =
                                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                    "The app is already stored in Zyro Private Apps."
                                reloadState()
                                return@runOperation
                            }

                            val unhideResult = withContext(Dispatchers.IO) {
                                ZeaDeviceOwnerController.setHidden(
                                    context = context,
                                    packageName = recoveryPackage,
                                    hidden = false,
                                    requireStoredLauncherVerification = false
                                )
                            }
                            if (!unhideResult.success) {
                                statusMessage =
                                    "Paused recovery could not make the app visible. ${unhideResult.message}"
                                return@runOperation
                            }

                            val recoveredResolution = withContext(Dispatchers.IO) {
                                ZeaInstalledApps.resolveAllowedApp(
                                    context,
                                    requestedName
                                )
                            }
                            val recoveredApp = recoveredResolution.selectedApp
                            if (
                                recoveredResolution.status !=
                                    AllowedAppResolutionStatus.RESOLVED ||
                                recoveredApp == null ||
                                !recoveredApp.packageName.equals(
                                    recoveryPackage,
                                    ignoreCase = true
                                )
                            ) {
                                statusMessage =
                                    "The app is visible again, but launcher verification failed. Protection remains paused."
                                reloadState()
                                return@runOperation
                            }

                            val recoveredRecord = recoveredApp.toPrivateAppRecord()
                            val recoveryValidation = withContext(Dispatchers.IO) {
                                ZeaDeviceOwnerController.validatePrivateApp(
                                    context,
                                    recoveredRecord
                                )
                            }
                            if (recoveryValidation != null) {
                                statusMessage = recoveryValidation
                                reloadState()
                                return@runOperation
                            }

                            val recoveryRecordsBefore = withContext(Dispatchers.IO) {
                                loadPrivateApps(context)
                            }
                            val recoveryIsFirstRecord = recoveryRecordsBefore.isEmpty()
                            if (recoveryIsFirstRecord && !firstProtectionConfirmed) {
                                statusMessage =
                                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                    "Recovery into Zyro requires explicit Protection Install Lock confirmation because this would create the first private record."
                                reloadState()
                                return@runOperation
                            }
                            val recoveryInstallLock = withContext(Dispatchers.IO) {
                                ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                    context,
                                    if (recoveryIsFirstRecord) 1 else recoveryRecordsBefore.size
                                )
                            }
                            if (!recoveryInstallLock.success) {
                                statusMessage =
                                    "Recovery stopped because the Protection Install Lock could not be verified. ${recoveryInstallLock.message}"
                                reloadState()
                                return@runOperation
                            }

                            val recoverySaved = withContext(Dispatchers.IO) {
                                val updated = loadPrivateApps(context)
                                    .filterNot { stored ->
                                        stored.packageName.equals(
                                            recoveredRecord.packageName,
                                            ignoreCase = true
                                        )
                                    } + recoveredRecord
                                savePrivateApps(context, updated)
                            }
                            if (!recoverySaved) {
                                val recoveryRollbackVerified = withContext(Dispatchers.IO) {
                                    val pendingRecovery =
                                        ZeaDeviceOwnerController.pendingRehidePackages(context)
                                    val rollbackLockCount = when {
                                        recoveryRecordsBefore.isNotEmpty() -> recoveryRecordsBefore.size
                                        pendingRecovery.isNotEmpty() -> 1
                                        else -> 0
                                    }
                                    val expectedLock = rollbackLockCount > 0
                                    val lockRollback =
                                        ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                            context,
                                            rollbackLockCount
                                        )
                                    val lockAfterRollback =
                                        ZeaDeviceOwnerController.queryProtectionInstallLock(context)
                                    val targetRecordAbsent = loadPrivateApps(context).none { stored ->
                                        stored.packageName.equals(
                                            recoveredRecord.packageName,
                                            ignoreCase = true
                                        )
                                    }
                                    lockRollback.success &&
                                        lockAfterRollback == expectedLock &&
                                        targetRecordAbsent
                                }
                                statusMessage = if (recoveryRollbackVerified) {
                                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                    "The app is visible, but its Zyro record could not be restored. Zyro verified the Protection Install Lock returned to the pre-recovery state, including any emergency recovery lock requirement; protection remains paused."
                                } else {
                                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                    "The app is visible, but its Zyro record could not be restored and the Protection Install Lock rollback could not be verified. Protection remains paused; reconcile Zyro before continuing."
                                }
                                reloadState()
                                return@runOperation
                            }

                            val recoveryFinalRecords = withContext(Dispatchers.IO) { loadPrivateApps(context) }
                            val recoveryFinalLock = withContext(Dispatchers.IO) {
                                ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                    context,
                                    recoveryFinalRecords.size
                                )
                            }
                            if (!recoveryFinalLock.success) {
                                statusMessage =
                                    "The app record was recovered, but the Protection Install Lock is not verified. Protection remains paused and requires reconciliation."
                                reloadState()
                                return@runOperation
                            }

                            appInput = ""
                            statusMessage =
                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                "${recoveredRecord.displayName} was recovered into Zyro. Protection Install Lock is active; protection remains paused until Resume Protection restores hidden-at-rest state."
                            reloadState()
                            return@runOperation
                        }

                        val resolution = withContext(Dispatchers.IO) {
                            ZeaInstalledApps.resolveAllowedApp(context, requestedName)
                        }
                        val selectedApp = resolution.selectedApp
                        if (resolution.status != AllowedAppResolutionStatus.RESOLVED || selectedApp == null) {
                            statusMessage = resolution.message.ifBlank {
                                "The requested app could not be verified as launchable."
                            }
                            return@runOperation
                        }

                        val record = selectedApp.toPrivateAppRecord()
                        val validation = withContext(Dispatchers.IO) {
                            ZeaDeviceOwnerController.validatePrivateApp(context, record)
                        }
                        if (validation != null) {
                            statusMessage = validation
                            return@runOperation
                        }

                        val recordsBeforeAdd = withContext(Dispatchers.IO) {
                            loadPrivateApps(context)
                        }
                        val pendingRecoveryBeforeAdd = withContext(Dispatchers.IO) {
                            ZeaDeviceOwnerController.pendingRehidePackages(context)
                        }
                        val targetAlreadyStored = recordsBeforeAdd.any { stored ->
                            stored.packageName.equals(record.packageName, ignoreCase = true)
                        }
                        if (targetAlreadyStored) {
                            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                            statusMessage = "${record.displayName} is already stored in Zyro Private Apps."
                            reloadState()
                            return@runOperation
                        }
                        val firstPrivateRecord = recordsBeforeAdd.isEmpty()
                        if (firstPrivateRecord && !firstProtectionConfirmed) {
                            statusMessage =
                                "The first private app requires explicit Protection Install Lock confirmation before protection can begin."
                            reloadState()
                            return@runOperation
                        }
                        val installLockResult = withContext(Dispatchers.IO) {
                            ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                context = context,
                                privateAppCount = if (firstPrivateRecord) 1 else recordsBeforeAdd.size
                            )
                        }
                        if (!installLockResult.success) {
                            statusMessage =
                                "The app was not added because the Protection Install Lock could not be verified. ${installLockResult.message}"
                            reloadState()
                            return@runOperation
                        }

                        val protectedResult = withContext(Dispatchers.IO) {
                            ZeaDeviceOwnerController.ensureProtectedState(
                                context = context,
                                packageName = record.packageName,
                                requireStoredLauncherVerification = false
                            )
                        }
                        if (!protectedResult.success) {
                            val rollbackResult = withContext(Dispatchers.IO) {
                                rollbackFailedPrivateAppAdd(
                                    context = context,
                                    target = record,
                                    recordsBeforeAdd = recordsBeforeAdd,
                                    pendingRecoveryBeforeAdd = pendingRecoveryBeforeAdd
                                )
                            }
                            statusMessage =
                                "The app was not added because complete hidden-at-rest, uninstall protection, and install-lock protection failed. ${protectedResult.message} ${rollbackResult.message}"
                            reloadState()
                            return@runOperation
                        }

                        val saved = withContext(Dispatchers.IO) {
                            val updated = loadPrivateApps(context)
                                .filterNot { stored ->
                                    stored.packageName.equals(record.packageName, ignoreCase = true)
                                } + record
                            savePrivateApps(context, updated)
                        }
                        if (!saved) {
                            val rollbackResult = withContext(Dispatchers.IO) {
                                rollbackFailedPrivateAppAdd(
                                    context = context,
                                    target = record,
                                    recordsBeforeAdd = recordsBeforeAdd,
                                    pendingRecoveryBeforeAdd = pendingRecoveryBeforeAdd
                                )
                            }
                            statusMessage =
                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                "The Zyro record could not be saved. ${rollbackResult.message}"
                            reloadState()
                            return@runOperation
                        }

                        val finalRecords = withContext(Dispatchers.IO) { loadPrivateApps(context) }
                        val finalInstallLock = withContext(Dispatchers.IO) {
                            ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                context,
                                finalRecords.size
                            )
                        }
                        if (!finalInstallLock.success || finalRecords.isEmpty()) {
                            val rollbackResult = withContext(Dispatchers.IO) {
                                rollbackFailedPrivateAppAdd(
                                    context = context,
                                    target = record,
                                    recordsBeforeAdd = recordsBeforeAdd,
                                    pendingRecoveryBeforeAdd = pendingRecoveryBeforeAdd
                                )
                            }
                            statusMessage =
                                "The app was not added because the final Protection Install Lock verification failed. ${rollbackResult.message}"
                            reloadState()
                            return@runOperation
                        }

                        appInput = ""
                        statusMessage = "${record.displayName} was added, hidden from Home/app drawer, protected from uninstall, and the Protection Install Lock is active."
                        reloadState()
                    
        }
    }


    LaunchedEffect(Unit) {
        val shouldReconcile = ZeaDeviceOwnerController.isDeviceOwner(context) &&
                !ZeaDeviceOwnerController.isProtectionPaused(context)
        reloadState(reconcile = shouldReconcile)
        statusMessage = if (privateApps.isEmpty()) {
            "No Device Owner private apps have been configured."
        } else {
            "Review actual hidden and uninstall-protection state before running a private command."
        }
        lifecycleResumeRefreshArmed.set(true)
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && lifecycleResumeRefreshArmed.get()) {
                scope.launch {
                    reloadState(reconcile = false)
                }
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
        }
    }


    if (pendingFirstProtectionName != null) {
        val pendingName = pendingFirstProtectionName.orEmpty()
        AlertDialog(
            onDismissRequest = {
                if (!operationInProgress) pendingFirstProtectionName = null
            },
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            title = { Text("Enable Zyro Protection Install Lock?") },
            text = {
                Text(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    "While at least one app is protected by Zyro, Android app installs and app updates for this user are blocked, including Zyro updates. Remove all private apps and resolve any emergency recovery entries to restore normal installs and updates."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingFirstProtectionName = null
                        startAddOrRecover(
                            requestedName = pendingName,
                            firstProtectionConfirmed = true
                        )
                    },
                    enabled = !operationInProgress
                ) {
                    Text("Enable Protection")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingFirstProtectionName = null },
                    enabled = !operationInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFF4F0FF),
        border = BorderStroke(1.dp, Color(0xFFD8CCFF))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Private Apps — Device Owner",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                text = "Mission 008 Alternative A keeps the original primary-user app installed, hides it with Android Device Owner policy, and blocks uninstall package-specifically. App data remains in place. Zyro is never registered as the Home launcher.",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Device Owner: ${if (ownerState.isDeviceOwner) "Active" else "Not provisioned"} | Usage Access: ${if (ownerState.usageAccessGranted) "Granted" else "Required"} | Protection: ${if (ownerState.protectionPaused) "Paused" else "Active"}",
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Protection Install Lock: ${when (ownerState.protectionInstallLockActive) {
                    true -> "Active"
                    false -> "Inactive"
                    null -> "Verification required"
                }}",
                fontWeight = FontWeight.SemiBold
            )
            Text(
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                text = "When at least one app is protected, this lock blocks Android app installs and updates for this user, including Zyro updates. Remove all private apps and resolve any emergency recovery entries to restore normal installs and updates.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(ownerState.message, style = MaterialTheme.typography.bodySmall)

            if (ownerState.isDeviceOwner && !ownerState.usageAccessGranted) {
                Button(
                    onClick = {
                        try {
                            activity.startActivity(
                                ZeaDeviceOwnerController.createUsageAccessSettingsIntent(context)
                            )
                        } catch (_: ActivityNotFoundException) {
                            statusMessage = "Android Usage Access settings are unavailable on this firmware."
                        }
                    },
                    enabled = !operationInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    Text("Open Zyro Usage Access")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                OutlinedButton(
                    onClick = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    enabled = !operationInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Allow Safety-Monitor Notification")
                }
            }

            if (privateApps.isEmpty()) {
                Text("No private apps have been added.")
            } else {
                privateApps.forEach { privateApp ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(privateApp.displayName, fontWeight = FontWeight.Bold)
                            Text(privateApp.packageName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = "Hidden at rest: ${when (hiddenStates[privateApp.packageName]) {
                                    true -> "Yes"
                                    false -> "No"
                                    null -> "Unknown"
                                }}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Uninstall blocked: ${when (uninstallBlockedStates[privateApp.packageName]) {
                                    true -> "Yes"
                                    false -> "No"
                                    null -> "Unknown"
                                }}",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        runOperation {
                                            val result = ZeaDeviceOwnerController.launchPrivateApp(
                                                context,
                                                privateApp
                                            )
                                            statusMessage = result.message
                                            if (result.success) {
                                                activity.finishAndRemoveTask()
                                            } else {
                                                reloadState()
                                            }
                                        }
                                    },
                                    enabled = !operationInProgress &&
                                            ownerState.isDeviceOwner &&
                                            ownerState.usageAccessGranted &&
                                            !ownerState.protectionPaused,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Open / Test")
                                }

                                OutlinedButton(
                                    onClick = {
                                        runOperation {
                                            val result = withContext(Dispatchers.IO) {
                                                ZeaDeviceOwnerController.ensureProtectedState(
                                                    context,
                                                    privateApp.packageName
                                                )
                                            }
                                            statusMessage = result.message
                                            reloadState()
                                        }
                                    },
                                    enabled = !operationInProgress &&
                                            ownerState.isDeviceOwner &&
                                            !ownerState.protectionPaused,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Hide Now")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    runOperation {
                                        val recordPresentBefore = withContext(Dispatchers.IO) {
                                            loadPrivateApps(context).any { stored ->
                                                stored.packageName.equals(
                                                    privateApp.packageName,
                                                    ignoreCase = true
                                                )
                                            }
                                        }
                                        if (!recordPresentBefore) {
                                            statusMessage =
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "Unhide-only stopped because the Zyro protection record is missing."
                                            return@runOperation
                                        }

                                        val installLock = withContext(Dispatchers.IO) {
                                            ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                                context,
                                                loadPrivateApps(context).size
                                            )
                                        }
                                        if (!installLock.success) {
                                            statusMessage =
                                                "Unhide-only stopped because the Protection Install Lock is not verified. ${installLock.message}"
                                            reloadState()
                                            return@runOperation
                                        }

                                        val uninstallProtection = withContext(Dispatchers.IO) {
                                            ZeaDeviceOwnerController.setUninstallBlocked(
                                                context = context,
                                                packageName = privateApp.packageName,
                                                blocked = true
                                            )
                                        }
                                        if (!uninstallProtection.success) {
                                            statusMessage =
                                                "Unhide-only stopped because uninstall protection was not confirmed. ${uninstallProtection.message}"
                                            return@runOperation
                                        }

                                        val result = withContext(Dispatchers.IO) {
                                            // Arming first matters: setHidden(false)
                                            // itself fires the visibility broadcast the
                                            // safety handler listens for.
                                            ZeaDeviceOwnerController.armManualVisibilityWindow(
                                                context,
                                                privateApp.packageName
                                            )
                                            ZeaDeviceOwnerController.setHidden(
                                                context,
                                                privateApp.packageName,
                                                false
                                            )
                                        }
                                        if (!result.success) {
                                            statusMessage =
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "Unhide-only stopped because Zyro could not safely unhide the app. ${result.message}"
                                            return@runOperation
                                        }

                                        val postState = withContext(Dispatchers.IO) {
                                            val hidden = ZeaDeviceOwnerController.isHidden(
                                                context,
                                                privateApp.packageName
                                            )
                                            val recordPresent = loadPrivateApps(context).any { stored ->
                                                stored.packageName.equals(
                                                    privateApp.packageName,
                                                    ignoreCase = true
                                                )
                                            }
                                            val uninstallBlocked =
                                                ZeaDeviceOwnerController.isUninstallBlocked(
                                                    context,
                                                    privateApp.packageName
                                                )
                                            Triple(hidden, recordPresent, uninstallBlocked)
                                        }

                                        statusMessage = when {
                                            postState.first != false ->
                                                "Unhide-only could not verify the package as visible."
                                            !postState.second ->
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "Unhide-only verified visibility, but the Zyro protection record is missing."
                                            postState.third != true ->
                                                "Unhide-only verified visibility, but package-specific uninstall protection is not active."
                                            else ->
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "${privateApp.displayName} is visible, remains in Zyro Private Apps, and is protected from uninstall."
                                        }
                                        reloadState()
                                    }
                                },
                                enabled = !operationInProgress && ownerState.isDeviceOwner,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                Text("Unhide Only - Keep in Zyro")
                            }

                            OutlinedButton(
                                onClick = {
                                    runOperation {
                                        val activePackage = withContext(Dispatchers.IO) {
                                            ZeaDeviceOwnerController.activePrivatePackage(context)
                                        }
                                        if (activePackage.equals(privateApp.packageName, ignoreCase = true)) {
                                            statusMessage =
                                                "Removal stopped because this app still has an active private session. Return Home, wait for protection to restore, and try again."
                                            reloadState()
                                            return@runOperation
                                        }
                                        
                                        // Point 4 revised generic restoration transaction.
                                        // Remove the Zea protection record before changing Android DPM state so package lifecycle
                                        // callbacks cannot legitimately treat this package as protected while it is being released.
                                        val recordRemoved = withContext(Dispatchers.IO) {
                                            val currentRecords = loadPrivateApps(context)
                                            val targetPresent = currentRecords.any { stored ->
                                                stored.packageName.equals(privateApp.packageName, ignoreCase = true)
                                            }
                                            if (!targetPresent) {
                                                false
                                            } else {
                                                val updated = currentRecords.filterNot { stored ->
                                                    stored.packageName.equals(privateApp.packageName, ignoreCase = true)
                                                }
                                                savePrivateApps(context, updated)
                                            }
                                        }
                                        
                                        if (!recordRemoved) {
                                            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                            statusMessage = "Removal stopped because the Zyro protection record could not be removed."
                                            reloadState()
                                            return@runOperation
                                        }
                                        
                                        val releaseVerified = withContext(Dispatchers.IO) {
                                            ZeaDeviceOwnerController.clearPendingRehidePackage(
                                                context,
                                                privateApp.packageName
                                            )
                                        
                                            val firstUnblock = ZeaDeviceOwnerController.setUninstallBlocked(
                                                context = context,
                                                packageName = privateApp.packageName,
                                                blocked = false,
                                                requireStoredLauncherVerification = false
                                            )
                                            val firstUnhide = ZeaDeviceOwnerController.setHidden(
                                                context = context,
                                                packageName = privateApp.packageName,
                                                hidden = false,
                                                requireStoredLauncherVerification = false
                                            )
                                        
                                            // Allow any already-dispatched package lifecycle reconciliation to finish.
                                            kotlinx.coroutines.delay(1000)
                                        
                                            var hiddenAfter = ZeaDeviceOwnerController.isHidden(
                                                context,
                                                privateApp.packageName
                                            )
                                            var uninstallBlockedAfter = ZeaDeviceOwnerController.isUninstallBlocked(
                                                context,
                                                privateApp.packageName
                                            )
                                        
                                            // One bounded final release pass handles a stale reconciliation that began before
                                            // the record was removed. This is not an open-ended retry.
                                            if (hiddenAfter != false || uninstallBlockedAfter != false) {
                                                ZeaDeviceOwnerController.clearPendingRehidePackage(
                                                    context,
                                                    privateApp.packageName
                                                )
                                                ZeaDeviceOwnerController.setUninstallBlocked(
                                                    context = context,
                                                    packageName = privateApp.packageName,
                                                    blocked = false,
                                                    requireStoredLauncherVerification = false
                                                )
                                                ZeaDeviceOwnerController.setHidden(
                                                    context = context,
                                                    packageName = privateApp.packageName,
                                                    hidden = false,
                                                    requireStoredLauncherVerification = false
                                                )
                                                kotlinx.coroutines.delay(750)
                                        
                                                hiddenAfter = ZeaDeviceOwnerController.isHidden(
                                                    context,
                                                    privateApp.packageName
                                                )
                                                uninstallBlockedAfter = ZeaDeviceOwnerController.isUninstallBlocked(
                                                    context,
                                                    privateApp.packageName
                                                )
                                            }
                                        
                                            ZeaDeviceOwnerController.clearPendingRehidePackage(
                                                context,
                                                privateApp.packageName
                                            )
                                        
                                            val recordStillPresent = loadPrivateApps(context).any { stored ->
                                                stored.packageName.equals(privateApp.packageName, ignoreCase = true)
                                            }
                                        
                                            firstUnblock.success &&
                                                firstUnhide.success &&
                                                hiddenAfter == false &&
                                                uninstallBlockedAfter == false &&
                                                !recordStillPresent
                                        }
                                        
                                        if (!releaseVerified) {
                                            val recoveryVerified = withContext(Dispatchers.IO) {
                                                val currentRecords = loadPrivateApps(context)
                                                val restoredRecords = currentRecords.filterNot { stored ->
                                                    stored.packageName.equals(privateApp.packageName, ignoreCase = true)
                                                } + privateApp
                                        
                                                val recordRestored = savePrivateApps(context, restoredRecords)
                                                val installLockRestored = if (recordRestored) {
                                                    ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                                        context,
                                                        restoredRecords.size
                                                    ).success
                                                } else {
                                                    false
                                                }
                                                val protectionRestored = if (recordRestored && installLockRestored) {
                                                    ZeaDeviceOwnerController.ensureProtectedState(
                                                        context = context,
                                                        packageName = privateApp.packageName,
                                                        requireStoredLauncherVerification = false
                                                    ).success
                                                } else {
                                                    false
                                                }
                                        
                                                val hiddenRecovered = ZeaDeviceOwnerController.isHidden(
                                                    context,
                                                    privateApp.packageName
                                                )
                                                val uninstallRecovered = ZeaDeviceOwnerController.isUninstallBlocked(
                                                    context,
                                                    privateApp.packageName
                                                )
                                        
                                                recordRestored &&
                                                    installLockRestored &&
                                                    protectionRestored &&
                                                    hiddenRecovered == true &&
                                                    uninstallRecovered == true
                                            }
                                        
                                            statusMessage = if (recoveryVerified) {
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "Removal could not be verified, so Zyro restored the app to its previous protected state."
                                            } else {
                                                "Removal could not be verified, and fail-closed recovery could not be confirmed."
                                            }
                                            reloadState()
                                            return@runOperation
                                        }
                                        
                                        val remainingRecords = withContext(Dispatchers.IO) {
                                            loadPrivateApps(context)
                                        }
                                        val pendingRecoveryAfterRemoval = withContext(Dispatchers.IO) {
                                            ZeaDeviceOwnerController.pendingRehidePackages(context)
                                        }
                                        val postRemovalLockCount = when {
                                            remainingRecords.isNotEmpty() -> remainingRecords.size
                                            pendingRecoveryAfterRemoval.isNotEmpty() -> 1
                                            else -> 0
                                        }
                                        val installLockReconciled = withContext(Dispatchers.IO) {
                                            ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                                context,
                                                postRemovalLockCount
                                            )
                                        }
                                        if (!installLockReconciled.success) {
                                            val recoveryVerified = withContext(Dispatchers.IO) {
                                                val restoredRecords = remainingRecords.filterNot { stored ->
                                                    stored.packageName.equals(privateApp.packageName, ignoreCase = true)
                                                } + privateApp
                                                val recordRestored = savePrivateApps(context, restoredRecords)
                                                val lockRestored = if (recordRestored) {
                                                    ZeaDeviceOwnerController.reconcileProtectionInstallLock(
                                                        context,
                                                        restoredRecords.size
                                                    ).success
                                                } else {
                                                    false
                                                }
                                                val protectionRestored = if (recordRestored && lockRestored) {
                                                    ZeaDeviceOwnerController.ensureProtectedState(
                                                        context = context,
                                                        packageName = privateApp.packageName,
                                                        requireStoredLauncherVerification = false
                                                    ).success
                                                } else {
                                                    false
                                                }
                                                val hiddenRecovered = ZeaDeviceOwnerController.isHidden(
                                                    context,
                                                    privateApp.packageName
                                                )
                                                val uninstallRecovered = ZeaDeviceOwnerController.isUninstallBlocked(
                                                    context,
                                                    privateApp.packageName
                                                )
                                                recordRestored && lockRestored && protectionRestored &&
                                                    hiddenRecovered == true && uninstallRecovered == true
                                            }
                                            statusMessage = if (recoveryVerified) {
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "Removal released the app, but the Protection Install Lock transition could not be verified, so Zyro restored the previous protected state."
                                            } else {
                                                "Removal could not complete because the Protection Install Lock transition failed, and fail-closed recovery could not be fully confirmed."
                                            }
                                            reloadState()
                                            return@runOperation
                                        }

                                        statusMessage = when {
                                            remainingRecords.isEmpty() && pendingRecoveryAfterRemoval.isEmpty() ->
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "${privateApp.displayName} is visible, uninstall protection is cleared, the app was removed from Zyro Private Apps, and the Protection Install Lock is inactive because no private apps or emergency recovery entries remain."
                                            remainingRecords.isEmpty() ->
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "${privateApp.displayName} is visible and removed from Zyro. Protection Install Lock remains active because emergency recovery entries still require fail-closed protection."
                                            else ->
                                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                                "${privateApp.displayName} is visible and removed from Zyro. Protection Install Lock remains active because ${remainingRecords.size} private app(s) remain."
                                        }
                                        reloadState()
                                    }
                                },
                                enabled = !operationInProgress && ownerState.isDeviceOwner,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                Text("Unhide and Remove from Zyro")
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = appInput,
                onValueChange = { appInput = it },
                label = { Text("Add Private App") },
                placeholder = { Text("Enter the exact installed app name") },
                enabled = !operationInProgress,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val requestedName = appInput.trim()
                    if (requestedName.isBlank()) {
                        statusMessage = "Enter an installed app name first."
                        return@Button
                    }
                    if (privateApps.isEmpty()) {
                        pendingFirstProtectionName = requestedName
                        return@Button
                    }
                    startAddOrRecover(
                        requestedName = requestedName,
                        firstProtectionConfirmed = false
                    )
                },
                enabled = !operationInProgress && ownerState.isDeviceOwner,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        operationInProgress -> "Working..."
                        ownerState.protectionPaused ->
                            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                            "Recover Hidden App into Zyro"
                        else -> "Add and Hide App"
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        runOperation {
                            val result = withContext(Dispatchers.IO) {
                                ZeaDeviceOwnerController.reconcileHiddenState(
                                    context,
                                    "manual reconcile"
                                )
                            }
                            statusMessage = result.message
                            reloadState()
                        }
                    },
                    enabled = !operationInProgress &&
                        ownerState.isDeviceOwner &&
                        !ownerState.protectionPaused,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reconcile")
                }

                OutlinedButton(
                    onClick = {
                        runOperation {
                            val result = withContext(Dispatchers.IO) {
                                ZeaDeviceOwnerController.unhideAllAndPause(context)
                            }
                            statusMessage = result.message
                            reloadState()
                        }
                    },
                    enabled = !operationInProgress &&
                        ownerState.isDeviceOwner &&
                        !ownerState.protectionPaused,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Emergency Unhide + Pause")
                }
            }

            if (ownerState.isDeviceOwner && ownerState.protectionPaused) {
                Button(
                    onClick = {
                        runOperation {
                            val result = withContext(Dispatchers.IO) {
                                ZeaDeviceOwnerController.resumeProtection(context)
                            }
                            statusMessage = result.message
                            reloadState()
                        }
                    },
                    enabled = !operationInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Resume Protection")
                }
            }

            if (statusMessage.isNotBlank()) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
