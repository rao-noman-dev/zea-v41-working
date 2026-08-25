package com.raomuhammadnoman.zea

import android.content.Context
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase-1 stability coordinator.
 *
 * This is deliberately small and infrastructure-only: it does not add a new
 * product feature. It centralizes the reliability contract used by refreshes
 * and state-changing transactions so every caller observes the same verified
 * Registry / Device Owner / catalog state before reporting success.
 */
data class ZeaPackageStateVerification(
    val success: Boolean,
    val packageName: String,
    val expectedMode: ZeaHideMode,
    val registryPresent: Boolean,
    val timedRecordPresent: Boolean,
    val hidden: Boolean?,
    val uninstallBlocked: Boolean?,
    val lockModeBlocked: Boolean?,
    val lockModeStored: Boolean?,
    val lockModeTimedUntil: Long?,
    val catalogMode: ZeaHideMode?,
    val issues: List<String>
) {
    val message: String
        get() = if (success) {
            "$packageName verified as ${expectedMode.name.lowercase(Locale.ROOT)}."
        } else {
            "$packageName verification failed: ${issues.joinToString("; ")}"
        }
}

data class ZeaPhase1RefreshResult(
    val success: Boolean,
    val duplicateSkipped: Boolean,
    val apps: List<ZeaManagedApp>,
    val issues: List<String>,
    val message: String
)

object ZeaPhase1Stability {
    private const val TAG = ZEA_DEVICE_OWNER_LOG_TAG
    private val refreshRunning = AtomicBoolean(false)

    /**
     * Full Phase-1 refresh pipeline. A process-wide gate prevents refresh jobs
     * on different screens from stacking policy work on top of one another.
     */
    suspend fun refresh(
        context: Context,
        reason: String
    ): ZeaPhase1RefreshResult {
        val appContext = context.applicationContext
        if (!refreshRunning.compareAndSet(false, true)) {
            return ZeaPhase1RefreshResult(
                success = true,
                duplicateSkipped = true,
                apps = emptyList(),
                issues = emptyList(),
                message = "A stability refresh is already running."
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                ZeaTimedHide.restoreExpiredHides(appContext)
                reconcileDurableStorage(appContext)

                var reconcileSuccess = true
                var reconcileMessage = ""
                if (ZeaDeviceOwnerController.isDeviceOwner(appContext)) {
                    val reconciliation = ZeaDeviceOwnerController.reconcileHiddenState(
                        appContext,
                        "phase1_refresh:$reason"
                    )
                    reconcileSuccess = reconciliation.success
                    reconcileMessage = reconciliation.message
                }

                ZeaAppCatalog.invalidateCatalogCache()
                val apps = ZeaAppCatalog.loadManagedApps(appContext)
                val issues = verifyGlobalState(appContext, apps).toMutableList()
                if (!reconcileSuccess) {
                    issues.add(reconcileMessage.ifBlank { "Protected-state reconciliation failed." })
                }

                val success = issues.isEmpty()
                ZeaPhase1RefreshResult(
                    success = success,
                    duplicateSkipped = false,
                    apps = apps,
                    issues = issues,
                    message = if (success) {
                        "Phase-1 refresh completed and state was re-verified."
                    } else {
                        "Phase-1 refresh completed with unresolved state: ${issues.joinToString("; ")}"
                    }
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Phase-1 refresh failed reason=$reason", error)
            ZeaPhase1RefreshResult(
                success = false,
                duplicateSkipped = false,
                apps = emptyList(),
                issues = listOf(error.message ?: error::class.java.simpleName),
                message = "Phase-1 refresh failed safely."
            )
        } finally {
            refreshRunning.set(false)
        }
    }

    /**
     * Re-query the complete transaction target after every Hide/Unhide.
     * For Device Owner mode both policy bits are mandatory. The catalog is
     * invalidated before reading so a success can never come from a stale list.
     */
    suspend fun verifyPackageState(
        context: Context,
        packageName: String,
        expectedMode: ZeaHideMode
    ): ZeaPackageStateVerification = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val normalized = packageName.trim()
        val registryPresent = loadPrivateApps(appContext).any { record ->
            record.packageName.equals(normalized, ignoreCase = true)
        }
        val timedRecord = loadTimedHides(appContext).firstOrNull { record ->
            record.packageName.equals(normalized, ignoreCase = true)
        }
        val timedRecordPresent = timedRecord != null

        val owner = ZeaDeviceOwnerController.isDeviceOwner(appContext)
        val hidden = if (owner) ZeaDeviceOwnerController.isHidden(appContext, normalized) else null
        val uninstallBlocked = if (owner) {
            ZeaDeviceOwnerController.isUninstallBlocked(appContext, normalized)
        } else {
            null
        }
        val lockModeBlocked = if (!owner) {
            ZeaLockMode.isBlocked(appContext, normalized)
        } else {
            null
        }
        val lockModeStored = if (!owner) {
            ZeaLockMode.blockedPackages(appContext).any { blocked ->
                blocked.equals(normalized, ignoreCase = true)
            }
        } else {
            null
        }
        val lockModeTimedUntil = if (!owner) {
            ZeaLockMode.timedUnlockAtEpochMillis(appContext, normalized)
        } else {
            null
        }

        ZeaAppCatalog.invalidateCatalogCache()
        val catalogMode = ZeaAppCatalog.loadManagedApps(appContext)
            .firstOrNull { app -> app.packageName.equals(normalized, ignoreCase = true) }
            ?.hideMode

        val issues = mutableListOf<String>()
        when (expectedMode) {
            ZeaHideMode.VISIBLE -> {
                if (registryPresent) issues.add("private registry still contains the app")
                if (timedRecordPresent) issues.add("timed-hide record still exists")
                if (owner && hidden != false) issues.add("Device Owner hidden state is $hidden, expected false")
                if (owner && uninstallBlocked != false) {
                    issues.add("uninstall-block state is $uninstallBlocked, expected false")
                }
                if (!owner && lockModeBlocked != false) {
                    issues.add("App Lock blocked state is $lockModeBlocked, expected false")
                }
                if (!owner && lockModeStored != false) {
                    issues.add("App Lock durable block is $lockModeStored, expected false")
                }
                if (!owner && lockModeTimedUntil != 0L) {
                    issues.add("App Lock timed deadline is $lockModeTimedUntil, expected 0")
                }
                if (catalogMode != ZeaHideMode.VISIBLE) {
                    issues.add("catalog mode is $catalogMode, expected VISIBLE")
                }
            }

            ZeaHideMode.HIDDEN -> {
                if (!registryPresent) issues.add("private registry record is missing")
                if (timedRecordPresent) issues.add("unexpected timed-hide record exists")
                if (owner && hidden != true) issues.add("Device Owner hidden state is $hidden, expected true")
                if (owner && uninstallBlocked != true) {
                    issues.add("uninstall-block state is $uninstallBlocked, expected true")
                }
                if (!owner && lockModeBlocked != true) {
                    issues.add("App Lock blocked state is $lockModeBlocked, expected true")
                }
                if (!owner && lockModeStored != true) {
                    issues.add("App Lock durable block is $lockModeStored, expected true")
                }
                if (!owner && lockModeTimedUntil != 0L) {
                    issues.add("App Lock permanent hide has timed deadline=$lockModeTimedUntil")
                }
                if (catalogMode != ZeaHideMode.HIDDEN) {
                    issues.add("catalog mode is $catalogMode, expected HIDDEN")
                }
            }

            ZeaHideMode.TIMED -> {
                if (!registryPresent) issues.add("private registry record is missing")
                if (!timedRecordPresent) issues.add("timed-hide record is missing")
                if (owner && hidden != true) issues.add("Device Owner hidden state is $hidden, expected true")
                if (owner && uninstallBlocked != true) {
                    issues.add("uninstall-block state is $uninstallBlocked, expected true")
                }
                if (!owner && lockModeBlocked != true) {
                    issues.add("App Lock blocked state is $lockModeBlocked, expected true")
                }
                if (!owner && lockModeStored != true) {
                    issues.add("App Lock durable block is $lockModeStored, expected true")
                }
                if (!owner && timedRecord != null && lockModeTimedUntil != timedRecord.hiddenUntilEpochMillis) {
                    issues.add(
                        "App Lock timed deadline=$lockModeTimedUntil expected=${timedRecord.hiddenUntilEpochMillis}"
                    )
                }
                if (catalogMode != ZeaHideMode.TIMED) {
                    issues.add("catalog mode is $catalogMode, expected TIMED")
                }
            }
        }

        ZeaPackageStateVerification(
            success = issues.isEmpty(),
            packageName = normalized,
            expectedMode = expectedMode,
            registryPresent = registryPresent,
            timedRecordPresent = timedRecordPresent,
            hidden = hidden,
            uninstallBlocked = uninstallBlocked,
            lockModeBlocked = lockModeBlocked,
            lockModeStored = lockModeStored,
            lockModeTimedUntil = lockModeTimedUntil,
            catalogMode = catalogMode,
            issues = issues
        )
    }

    /**
     * Remove durable rows that can no longer represent a real target. A timed
     * row with an installed target is never discarded merely because another
     * row is missing: if the policy state is protected we first reconstruct the
     * private record, otherwise we only remove a timer proven stale/visible.
     */
    private fun reconcileDurableStorage(context: Context) {
        var privateRecords = loadPrivateApps(context)
        val stalePrivate = privateRecords.filterNot { record ->
            ZeaDeviceOwnerController.isPackageInstalled(context, record.packageName)
        }
        if (stalePrivate.isNotEmpty()) {
            val staleKeys = stalePrivate.mapTo(mutableSetOf()) {
                it.packageName.lowercase(Locale.ROOT)
            }
            val kept = privateRecords.filterNot {
                it.packageName.lowercase(Locale.ROOT) in staleKeys
            }
            if (savePrivateApps(context, kept)) {
                stalePrivate.forEach { record ->
                    ZeaDeviceOwnerController.clearPendingRehidePackage(context, record.packageName)
                    ZeaTimedHide.cancel(context, record.packageName)
                }
                val timers = loadTimedHides(context).filterNot {
                    it.packageName.lowercase(Locale.ROOT) in staleKeys
                }
                saveTimedHides(context, timers)
                privateRecords = kept
                Log.i(TAG, "removed ${stalePrivate.size} stale private record(s) for uninstalled packages")
            } else {
                Log.w(TAG, "could not persist stale private-record cleanup")
            }
        }

        if (!ZeaDeviceOwnerController.isDeviceOwner(context)) {
            reconcileLockModeStorage(context, privateRecords)
            return
        }

        val privateKeys = privateRecords
            .mapTo(mutableSetOf()) { it.packageName.lowercase(Locale.ROOT) }
        val timers = loadTimedHides(context)
        val keptTimers = mutableListOf<ZeaTimedHideRecord>()
        var timerChanged = false

        timers.forEach { timer ->
            val key = timer.packageName.lowercase(Locale.ROOT)
            if (!ZeaDeviceOwnerController.isPackageInstalled(context, timer.packageName)) {
                ZeaTimedHide.cancel(context, timer.packageName)
                timerChanged = true
                return@forEach
            }

            if (key in privateKeys) {
                keptTimers.add(timer)
                return@forEach
            }

            val hidden = ZeaDeviceOwnerController.isHidden(context, timer.packageName)
            val blocked = ZeaDeviceOwnerController.isUninstallBlocked(context, timer.packageName)
            when {
                hidden == true && blocked == true -> {
                    if (ZeaAppHideService.syncBookkeepingToVerifiedHiddenState(context, timer.packageName)) {
                        privateKeys.add(key)
                        keptTimers.add(timer)
                    } else {
                        // Keep the timer: dropping it would turn a temporary
                        // hidden state into a permanent hidden orphan.
                        keptTimers.add(timer)
                        Log.w(TAG, "timer/private registry repair deferred package=${timer.packageName}")
                    }
                }

                hidden == false && blocked == false -> {
                    ZeaTimedHide.cancel(context, timer.packageName)
                    timerChanged = true
                    Log.i(TAG, "removed stale timer for already-visible package=${timer.packageName}")
                }

                else -> {
                    keptTimers.add(timer)
                    Log.w(
                        TAG,
                        "kept unverifiable/partial timed row package=${timer.packageName} hidden=$hidden blocked=$blocked"
                    )
                }
            }
        }

        if (timerChanged || keptTimers.size != timers.size) {
            saveTimedHides(context, keptTimers)
        }
    }

    /**
     * Standard/App-Lock mode has no DPM hidden bit, but it still has the same
     * durable registry contract. Reconcile the accessibility block set and
     * timed rows so an interrupted write cannot leave an untracked permanent
     * block or a registry row with no enforcement.
     */
    private fun reconcileLockModeStorage(
        context: Context,
        privateRecords: List<PrivateAppRecord>
    ) {
        val privateKeys = privateRecords
            .mapTo(mutableSetOf()) { it.packageName.lowercase(Locale.ROOT) }
        val timers = loadTimedHides(context)
        val keptTimers = mutableListOf<ZeaTimedHideRecord>()
        var timerChanged = false

        timers.forEach { timer ->
            val key = timer.packageName.lowercase(Locale.ROOT)
            when {
                !ZeaDeviceOwnerController.isPackageInstalled(context, timer.packageName) -> {
                    ZeaTimedHide.cancel(context, timer.packageName)
                    ZeaLockMode.unblock(context, timer.packageName)
                    ZeaLockMode.clearSessionAllow(context, timer.packageName)
                    timerChanged = true
                }

                key !in privateKeys -> {
                    // Timer without a durable managed-app row cannot be trusted.
                    // Release it; never turn a temporary lock into a permanent one.
                    ZeaTimedHide.cancel(context, timer.packageName)
                    ZeaLockMode.unblock(context, timer.packageName)
                    ZeaLockMode.clearSessionAllow(context, timer.packageName)
                    timerChanged = true
                    Log.w(TAG, "released App Lock timer without private record package=${timer.packageName}")
                }

                else -> {
                    keptTimers.add(timer)
                }
            }
        }

        if (timerChanged || keptTimers.size != timers.size) {
            saveTimedHides(context, keptTimers)
        }

        val timerByPackage = keptTimers.associateBy {
            it.packageName.lowercase(Locale.ROOT)
        }
        privateRecords.forEach { record ->
            val timer = timerByPackage[record.packageName.lowercase(Locale.ROOT)]
            if (timer != null) {
                // Preserve the original timed contract even when the deadline
                // has already passed and cleanup is being retried. Writing a
                // permanent block here would silently convert a temporary lock.
                ZeaLockMode.blockUntil(context, record.packageName, timer.hiddenUntilEpochMillis)
            } else {
                ZeaLockMode.block(context, record.packageName)
            }
        }

        ZeaLockMode.blockedPackages(context)
            .filterNot { blocked -> blocked.lowercase(Locale.ROOT) in privateKeys }
            .forEach { orphan ->
                ZeaLockMode.unblock(context, orphan)
                ZeaLockMode.clearSessionAllow(context, orphan)
                Log.w(TAG, "released untracked App Lock block package=$orphan")
            }
    }

    private fun verifyGlobalState(
        context: Context,
        apps: List<ZeaManagedApp>
    ): List<String> {
        val issues = mutableListOf<String>()
        val privateRecords = loadPrivateApps(context)
        val timedRecords = loadTimedHides(context)
        val privateKeys = privateRecords.mapTo(mutableSetOf()) {
            it.packageName.lowercase(Locale.ROOT)
        }
        val timedKeys = timedRecords.mapTo(mutableSetOf()) {
            it.packageName.lowercase(Locale.ROOT)
        }
        val catalogByPackage = apps.associateBy { it.packageName.lowercase(Locale.ROOT) }

        privateRecords.forEach { record ->
            if (!ZeaDeviceOwnerController.isPackageInstalled(context, record.packageName)) {
                issues.add("stale private record:${record.packageName}")
            }
        }
        timedRecords.forEach { timer ->
            if (timer.packageName.lowercase(Locale.ROOT) !in privateKeys) {
                issues.add("timer without private record:${timer.packageName}")
            }
            if (timer.hiddenUntilEpochMillis <= System.currentTimeMillis()) {
                issues.add("expired timer still unresolved:${timer.packageName}")
            }
        }

        if (ZeaDeviceOwnerController.isDeviceOwner(context)) {
            val paused = ZeaDeviceOwnerController.isProtectionPaused(context)
            if (!paused) {
                privateRecords.forEach { record ->
                    val hidden = ZeaDeviceOwnerController.isHidden(context, record.packageName)
                    val blocked = ZeaDeviceOwnerController.isUninstallBlocked(context, record.packageName)
                    if (hidden != true) issues.add("not hidden:${record.packageName}:$hidden")
                    if (blocked != true) issues.add("not uninstall-blocked:${record.packageName}:$blocked")
                    val expectedMode = if (record.packageName.lowercase(Locale.ROOT) in timedKeys) {
                        ZeaHideMode.TIMED
                    } else {
                        ZeaHideMode.HIDDEN
                    }
                    if (catalogByPackage[record.packageName.lowercase(Locale.ROOT)]?.hideMode != expectedMode) {
                        issues.add("catalog mismatch:${record.packageName}:expected=$expectedMode")
                    }
                }
            }

            val pending = ZeaDeviceOwnerController.pendingRehidePackages(context)
            if (!paused && pending.isNotEmpty()) {
                issues.add("pending re-hide remains:${pending.joinToString(",")}")
            }
            val expectedLock = privateRecords.isNotEmpty() || pending.isNotEmpty()
            val actualLock = ZeaDeviceOwnerController.queryProtectionInstallLock(context)
            if (actualLock != expectedLock) {
                issues.add("install lock=$actualLock expected=$expectedLock")
            }
        } else {
            privateRecords.forEach { record ->
                val key = record.packageName.lowercase(Locale.ROOT)
                val durableBlocked = ZeaLockMode.blockedPackages(context).any { blocked ->
                    blocked.equals(record.packageName, ignoreCase = true)
                }
                if (!durableBlocked || !ZeaLockMode.isBlocked(context, record.packageName)) {
                    issues.add("App Lock not enforcing:${record.packageName}")
                }
                val expectedMode = if (key in timedKeys) {
                    ZeaHideMode.TIMED
                } else {
                    ZeaHideMode.HIDDEN
                }
                val timedDeadline = ZeaLockMode.timedUnlockAtEpochMillis(context, record.packageName)
                val timer = timedRecords.firstOrNull { it.packageName.equals(record.packageName, ignoreCase = true) }
                if (expectedMode == ZeaHideMode.TIMED && timer != null && timedDeadline != timer.hiddenUntilEpochMillis) {
                    issues.add(
                        "App Lock timer mismatch:${record.packageName}:stored=$timedDeadline expected=${timer.hiddenUntilEpochMillis}"
                    )
                }
                if (expectedMode == ZeaHideMode.HIDDEN && timedDeadline != 0L) {
                    issues.add("App Lock permanent record has timer:${record.packageName}:$timedDeadline")
                }
                if (catalogByPackage[key]?.hideMode != expectedMode) {
                    issues.add("catalog mismatch:${record.packageName}:expected=$expectedMode")
                }
            }
            ZeaLockMode.blockedPackages(context)
                .filterNot { blocked -> blocked.lowercase(Locale.ROOT) in privateKeys }
                .forEach { orphan -> issues.add("untracked App Lock block:$orphan") }
        }

        val ownerPaused = ZeaDeviceOwnerController.isDeviceOwner(context) &&
            ZeaDeviceOwnerController.isProtectionPaused(context)
        if (!ownerPaused) {
            issues.addAll(
                zeaPhase1CountIssues(
                    ZeaPhase1CountSnapshot(
                        visible = apps.count { it.hideMode == ZeaHideMode.VISIBLE },
                        hidden = apps.count { it.hideMode == ZeaHideMode.HIDDEN },
                        timed = apps.count { it.hideMode == ZeaHideMode.TIMED },
                        registryProtected = privateRecords.size,
                        timerRecords = timedRecords.size
                    )
                )
            )
        }

        return issues.distinct()
    }
}
