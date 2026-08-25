package com.raomuhammadnoman.zea

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns all approved application launch behavior for Zea.
 *
 * Launching is intentionally separated from command parsing and installed-app
 * discovery. The launcher accepts only registry entries that have already
 * passed Zea's safety and user-allowlist checks.
 */
object ZeaAppLauncher {
    private const val MAX_RECORDED_ATTEMPTS = 48
    private const val MAX_TOTAL_LAUNCH_DURATION_MS = 3_000L
    private const val LOG_TAG = "ZeaLaunch"
    private const val ACTION_CREATE_NOTE_COMPAT = "android.intent.action.CREATE_NOTE"

    private val launchRequestInProgress = AtomicBoolean(false)
    private val operationStartedForDiagnostics = ThreadLocal<Long?>()

    suspend fun launchAppWithTimeout(
        context: Context,
        appKey: String,
        displayName: String = appKey,
        operationStartedElapsedRealtime: Long? = null
    ): LaunchResult {
        return launchWithTimeout(
            context = context,
            requestKey = appKey,
            displayName = displayName,
            operationStartedElapsedRealtime = operationStartedElapsedRealtime
        ) { applicationContext, deadlineElapsedRealtime ->
            launchAppInternal(
                context = applicationContext,
                appKey = appKey,
                deadlineElapsedRealtime = deadlineElapsedRealtime
            )
        }
    }

    suspend fun launchResolvedEntryWithTimeout(
        context: Context,
        entry: AppRegistryEntry,
        operationStartedElapsedRealtime: Long? = null
    ): LaunchResult {
        return launchWithTimeout(
            context = context,
            requestKey = entry.key,
            displayName = entry.displayName,
            operationStartedElapsedRealtime = operationStartedElapsedRealtime
        ) { applicationContext, deadlineElapsedRealtime ->
            launchRegistryEntryInternal(
                context = applicationContext,
                entry = entry,
                deadlineElapsedRealtime = deadlineElapsedRealtime,
                enforceAllowedApps = true
            )
        }
    }

    suspend fun launchLauncherResolvedEntryWithTimeout(
        context: Context,
        entry: AppRegistryEntry,
        operationStartedElapsedRealtime: Long? = null
    ): LaunchResult {
        return launchWithTimeout(
            context = context,
            requestKey = entry.key,
            displayName = entry.displayName,
            operationStartedElapsedRealtime = operationStartedElapsedRealtime
        ) { applicationContext, deadlineElapsedRealtime ->
            launchRegistryEntryInternal(
                context = applicationContext,
                entry = entry,
                deadlineElapsedRealtime = deadlineElapsedRealtime,
                enforceAllowedApps = false
            )
        }
    }

    suspend fun launchPrivateResolvedEntryWithTimeout(
        context: Context,
        entry: AppRegistryEntry,
        operationStartedElapsedRealtime: Long? = null
    ): LaunchResult {
        return launchWithTimeout(
            context = context,
            requestKey = entry.key,
            displayName = entry.displayName,
            operationStartedElapsedRealtime = operationStartedElapsedRealtime
        ) { applicationContext, deadlineElapsedRealtime ->
            launchRegistryEntryInternal(
                context = applicationContext,
                entry = entry,
                deadlineElapsedRealtime = deadlineElapsedRealtime,
                enforceAllowedApps = false
            )
        }
    }

    private suspend fun launchWithTimeout(
        context: Context,
        requestKey: String,
        displayName: String,
        operationStartedElapsedRealtime: Long?,
        block: (Context, Long) -> LaunchResult
    ): LaunchResult {
        val totalStarted = SystemClock.elapsedRealtime()

        if (!launchRequestInProgress.compareAndSet(false, true)) {
            Log.i(LOG_TAG, "launcher gate rejected appKey=$requestKey")
            return failureResult(
                message = "Another app launch request is already in progress.",
                reason = AppLaunchFailureReason.LAUNCH_FAILED
            )
        }

        Log.i(LOG_TAG, "launcher gate acquired appKey=$requestKey")

        val applicationContext = context.applicationContext
        val deadlineElapsedRealtime =
            SystemClock.elapsedRealtime() + MAX_TOTAL_LAUNCH_DURATION_MS

        return try {
            val result = withTimeoutOrNull(MAX_TOTAL_LAUNCH_DURATION_MS) {
                runInterruptible(Dispatchers.IO) {
                    operationStartedForDiagnostics.set(operationStartedElapsedRealtime)
                    try {
                        block(applicationContext, deadlineElapsedRealtime)
                    } finally {
                        operationStartedForDiagnostics.remove()
                    }
                }
            }

            if (result == null) {
                Log.w(
                    LOG_TAG,
                    "coroutine timeout requested appKey=$requestKey; a blocking Binder call may finish later"
                )
            }

            result ?: launchTimeoutResult(displayName)
        } finally {
            launchRequestInProgress.set(false)
            Log.i(
                LOG_TAG,
                "launcher total end appKey=$requestKey elapsedMs=${SystemClock.elapsedRealtime() - totalStarted}"
            )
        }
    }

    fun launchApp(
        context: Context,
        appKey: String
    ): LaunchResult {
        return launchAppInternal(
            context = context,
            appKey = appKey,
            deadlineElapsedRealtime = newLaunchDeadline()
        )
    }

    private fun launchAppInternal(
        context: Context,
        appKey: String,
        deadlineElapsedRealtime: Long
    ): LaunchResult {
        val normalizedKey = appKey.trim().lowercase()

        if (normalizedKey.isBlank()) {
            return failureResult(
                message = "An app key is required.",
                reason = AppLaunchFailureReason.LAUNCH_FAILED
            )
        }

        if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
            return launchTimeoutResult(appKey)
        }

        val resolutionStarted = SystemClock.elapsedRealtime()
        val entry = ZeaAppLookupCache.findByKey(context, normalizedKey)
            ?: run {
                Log.i(
                    LOG_TAG,
                    "cached target resolution appKey=$appKey found=false elapsedMs=${SystemClock.elapsedRealtime() - resolutionStarted}"
                )
                return failureResult(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    message = "This app is not configured in Zyro: $appKey",
                    reason = AppLaunchFailureReason.NOT_VISIBLE
                )
            }

        Log.i(
            LOG_TAG,
            "cached target resolution appKey=$appKey found=true elapsedMs=${SystemClock.elapsedRealtime() - resolutionStarted}"
        )

        return launchRegistryEntryInternal(
            context = context,
            entry = entry,
            deadlineElapsedRealtime = deadlineElapsedRealtime,
            enforceAllowedApps = true
        )
    }

    fun launchRegistryEntry(
        context: Context,
        entry: AppRegistryEntry
    ): LaunchResult {
        return launchRegistryEntryInternal(
            context = context,
            entry = entry,
            deadlineElapsedRealtime = newLaunchDeadline(),
            enforceAllowedApps = true
        )
    }

    private fun launchRegistryEntryInternal(
        context: Context,
        entry: AppRegistryEntry,
        deadlineElapsedRealtime: Long,
        enforceAllowedApps: Boolean
    ): LaunchResult {
        val applicationContext = context.applicationContext

        if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
            return launchTimeoutResult(entry.displayName)
        }

        val policyResult = ZeaSafetyPolicy.evaluateRegistryEntry(entry)

        if (!policyResult.allowed) {
            return failureResult(
                message = policyResult.message,
                reason = AppLaunchFailureReason.BLOCKED
            )
        }

        if (isAppBlockedBySettings(applicationContext, entry)) {
            return failureResult(
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "${entry.displayName} is blocked by Zyro settings.",
                reason = AppLaunchFailureReason.BLOCKED
            )
        }

        if (enforceAllowedApps && !isAppAllowedBySettings(applicationContext, entry)) {
            return failureResult(
                message = "${entry.displayName} is not in the Allowed Apps list.",
                reason = AppLaunchFailureReason.NOT_ALLOWED
            )
        }

        val systemAction = entry.systemAction
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)

        if (systemAction != null) {
            return launchSystemActionInternal(
                context = applicationContext,
                systemAction = systemAction,
                displayName = entry.displayName,
                deadlineElapsedRealtime = deadlineElapsedRealtime
            )
        }

        val packageNames = buildPackageCandidateList(entry)

        if (packageNames.isEmpty()) {
            return failureResult(
                message = "No approved package is configured for ${entry.displayName}.",
                reason = AppLaunchFailureReason.NO_COMPATIBLE_HANDLER
            )
        }

        return launchPackageCandidates(
            context = applicationContext,
            displayName = entry.displayName,
            packageNames = packageNames,
            configuredActivity = entry.launcherActivityName,
            configuredActivityPackage = entry.packageName,
            deadlineElapsedRealtime = deadlineElapsedRealtime
        )
    }

    fun launchSystemAction(
        context: Context,
        systemAction: String,
        displayName: String
    ): LaunchResult {
        return launchSystemActionInternal(
            context = context,
            systemAction = systemAction,
            displayName = displayName,
            deadlineElapsedRealtime = newLaunchDeadline()
        )
    }

    private fun launchSystemActionInternal(
        context: Context,
        systemAction: String,
        displayName: String,
        deadlineElapsedRealtime: Long
    ): LaunchResult {
        val normalizedAction = systemAction.trim().lowercase()

        if (!ZeaSafetyPolicy.isAllowedSystemAction(normalizedAction)) {
            return failureResult(
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "The requested system action is not approved by Zyro.",
                reason = AppLaunchFailureReason.BLOCKED
            )
        }

        val plan = systemLaunchPlan(normalizedAction)
            ?: return failureResult(
                message = "No launch plan is configured for $displayName.",
                reason = AppLaunchFailureReason.NO_COMPATIBLE_HANDLER
            )

        val attempts = mutableListOf<LaunchAttempt>()

        fun attemptPackagePhase(): LaunchResult? {
            val packageResult = launchPackageCandidates(
                context = context,
                displayName = displayName,
                packageNames = plan.packageNames,
                configuredActivity = null,
                configuredActivityPackage = null,
                deadlineElapsedRealtime = deadlineElapsedRealtime
            )

            attempts += packageResult.attempts

            return packageResult.takeIf(LaunchResult::success)?.copy(
                attempts = attempts.take(MAX_RECORDED_ATTEMPTS)
            )
        }

        if (plan.preferPackages) {
            attemptPackagePhase()?.let { return it }
        }

        for (intentFactory in plan.intentFactories) {
            if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
                return launchTimeoutResult(displayName, attempts)
            }

            val intent = intentFactory().prepareForExternalLaunch()
            val outcome = attemptStartActivity(
                context = context,
                method = AppLaunchMethod.SYSTEM_ACTION,
                detail = "$displayName system intent",
                intent = intent
            )

            attempts += outcome.attempt

            if (outcome.success) {
                return LaunchResult(
                    success = true,
                    message = "$displayName opened successfully.",
                    method = AppLaunchMethod.SYSTEM_ACTION,
                    attempts = attempts.take(MAX_RECORDED_ATTEMPTS)
                )
            }
        }

        if (!plan.preferPackages) {
            attemptPackagePhase()?.let { return it }
        }

        val securityRejected = attempts.any { attempt ->
            attempt.detail.startsWith(SECURITY_DETAIL_PREFIX)
        }

        return failureResult(
            message = if (securityRejected) {
                "$displayName could not be opened because Android or the device vendor rejected the launch request."
            } else {
                "$displayName could not be opened because no compatible activity was available."
            },
            reason = if (securityRejected) {
                AppLaunchFailureReason.SECURITY_REJECTED
            } else {
                AppLaunchFailureReason.NO_COMPATIBLE_HANDLER
            },
            attempts = attempts
        )
    }

    private fun buildPackageCandidateList(entry: AppRegistryEntry): List<String> {
        return sanitizePackageNames(
            sequenceOf(entry.packageName)
                .plus(entry.alternatePackageNames.asSequence())
        )
    }

    private fun sanitizePackageNames(
        packageNames: Sequence<String?>
    ): List<String> {
        return packageNames
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(ZeaSafetyPolicy::isValidPackageName)
            .distinct()
            .toList()
    }

    private fun launchPackageCandidates(
        context: Context,
        displayName: String,
        packageNames: List<String>,
        configuredActivity: String?,
        configuredActivityPackage: String?,
        deadlineElapsedRealtime: Long
    ): LaunchResult {
        val attempts = mutableListOf<LaunchAttempt>()
        var packageObserved = false
        var disabledPackageObserved = false
        var securityRejected = false
        var runtimeFailureObserved = false

        for (packageName in sanitizePackageNames(packageNames.asSequence())) {
            if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
                return launchTimeoutResult(displayName, attempts)
            }

            val preflightStarted = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "package preflight start package=$packageName")
            val packageState = inspectPackageState(
                packageManager = context.packageManager,
                packageName = packageName
            )
            Log.i(
                LOG_TAG,
                "package preflight end package=$packageName visible=${packageState.visible} disabled=${packageState.disabled} elapsedMs=${SystemClock.elapsedRealtime() - preflightStarted}"
            )

            packageObserved = packageObserved || packageState.visible
            disabledPackageObserved = disabledPackageObserved || packageState.disabled

            if (!packageState.visible || packageState.disabled) {
                Log.i(
                    LOG_TAG,
                    "package preflight skipped launch strategies package=$packageName visible=${packageState.visible} disabled=${packageState.disabled}"
                )
                continue
            }

            if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
                return launchTimeoutResult(displayName, attempts)
            }

            val activityName = configuredActivity
                ?.takeIf { activity ->
                    configuredActivityPackage == packageName &&
                            ZeaSafetyPolicy.isValidLauncherActivityName(
                                packageName = packageName,
                                launcherActivityName = activity
                            )
                }

            val outcome = launchSinglePackage(
                context = context,
                packageName = packageName,
                configuredActivity = activityName,
                deadlineElapsedRealtime = deadlineElapsedRealtime
            )

            attempts += outcome.attempts
            securityRejected = securityRejected || outcome.securityRejected
            runtimeFailureObserved = runtimeFailureObserved || outcome.runtimeFailureObserved

            if (outcome.timedOut) {
                return launchTimeoutResult(displayName, attempts)
            }

            if (outcome.successfulMethod != null) {
                return LaunchResult(
                    success = true,
                    message = "$displayName opened successfully.",
                    method = outcome.successfulMethod,
                    attempts = attempts.take(MAX_RECORDED_ATTEMPTS)
                )
            }
        }

        val reason = when {
            securityRejected -> AppLaunchFailureReason.SECURITY_REJECTED
            disabledPackageObserved -> AppLaunchFailureReason.NOT_VISIBLE
            packageObserved -> AppLaunchFailureReason.NO_COMPATIBLE_HANDLER
            runtimeFailureObserved -> AppLaunchFailureReason.LAUNCH_FAILED
            else -> AppLaunchFailureReason.NOT_VISIBLE
        }

        val message = when (reason) {
            AppLaunchFailureReason.SECURITY_REJECTED ->
                "$displayName could not be opened because Android or the device vendor rejected every approved launch method."

            AppLaunchFailureReason.NO_COMPATIBLE_HANDLER ->
                "$displayName is present, but no enabled launch activity was available."

            AppLaunchFailureReason.LAUNCH_FAILED ->
                "$displayName could not be opened because the device returned an unexpected launch failure."

            else ->
                "$displayName could not be opened. It may be hidden, disabled, inside a locked private profile, or not installed."
        }

        return failureResult(
            message = message,
            reason = reason,
            attempts = attempts
        )
    }

    private fun launchSinglePackage(
        context: Context,
        packageName: String,
        configuredActivity: String?,
        deadlineElapsedRealtime: Long
    ): PackageLaunchOutcome {
        val attempts = mutableListOf<LaunchAttempt>()
        var securityRejected = false
        var runtimeFailureObserved = false

        fun record(outcome: StartOutcome): AppLaunchMethod? {
            attempts += outcome.attempt
            securityRejected = securityRejected || outcome.securityRejected
            runtimeFailureObserved = runtimeFailureObserved || outcome.runtimeFailure
            return outcome.attempt.method.takeIf { outcome.success }
        }

        fun timedOutOutcome(): PackageLaunchOutcome {
            return PackageLaunchOutcome(
                attempts = attempts,
                successfulMethod = null,
                securityRejected = securityRejected,
                runtimeFailureObserved = runtimeFailureObserved,
                timedOut = true
            )
        }

        if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
            return timedOutOutcome()
        }

        val normalLaunchOutcome = runLaunchStrategy(
            strategy = "packageLaunchIntent",
            packageName = packageName
        ) {
            attemptPackageLaunchIntent(
                context = context,
                packageName = packageName
            )
        }

        record(normalLaunchOutcome)?.let { method ->
            return PackageLaunchOutcome.success(method, attempts)
        }

        if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
            return timedOutOutcome()
        }

        configuredActivity?.let { activityName ->
            val componentName = ComponentName(
                packageName,
                fullyQualifiedActivityName(packageName, activityName)
            )

            record(
                runLaunchStrategy(
                    strategy = "configuredExplicitComponent",
                    packageName = packageName
                ) {
                    attemptExplicitComponentLaunch(
                        context = context,
                        componentName = componentName,
                        detail = "$packageName configured launcher component"
                    )
                }
            )?.let { method ->
                return PackageLaunchOutcome.success(method, attempts)
            }
        }

        if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
            return timedOutOutcome()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intentSenderOutcome = runLaunchStrategy(
                strategy = "packageIntentSender",
                packageName = packageName
            ) {
                attemptLaunchIntentSender(
                    context = context,
                    packageName = packageName
                )
            }

            record(intentSenderOutcome)?.let { method ->
                return PackageLaunchOutcome.success(method, attempts)
            }
        }

        for (intent in packageScopedMainIntents(packageName)) {
            if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
                return timedOutOutcome()
            }

            val successfulMethod = record(
                runLaunchStrategy(
                    strategy = "packageScopedMainIntent",
                    packageName = packageName
                ) {
                    attemptStartActivity(
                        context = context,
                        method = AppLaunchMethod.MAIN_LAUNCHER_QUERY,
                        detail = "$packageName package-scoped main intent",
                        intent = intent
                    )
                }
            )

            if (successfulMethod != null) {
                return PackageLaunchOutcome.success(successfulMethod, attempts)
            }
        }

        if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
            return timedOutOutcome()
        }

        for (
            componentName in queryEnabledLauncherComponents(
                packageManager = context.packageManager,
                packageName = packageName
            )
        ) {
            if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
                return timedOutOutcome()
            }

            val successfulMethod = record(
                runLaunchStrategy(
                    strategy = "queriedExplicitComponent",
                    packageName = packageName
                ) {
                    attemptExplicitComponentLaunch(
                        context = context,
                        componentName = componentName,
                        detail = "$packageName queried launcher component"
                    )
                }
            )

            if (successfulMethod != null) {
                return PackageLaunchOutcome.success(successfulMethod, attempts)
            }
        }

        if (isLaunchBudgetExpired(deadlineElapsedRealtime)) {
            return timedOutOutcome()
        }

        val launcherAppsOutcome = runLaunchStrategy(
            strategy = "launcherApps",
            packageName = packageName
        ) {
            attemptLauncherAppsLaunch(
                context = context,
                packageName = packageName
            )
        }

        record(launcherAppsOutcome)?.let { method ->
            return PackageLaunchOutcome.success(method, attempts)
        }

        return PackageLaunchOutcome(
            attempts = attempts,
            successfulMethod = null,
            securityRejected = securityRejected,
            runtimeFailureObserved = runtimeFailureObserved,
            timedOut = false
        )
    }

    private inline fun runLaunchStrategy(
        strategy: String,
        packageName: String,
        block: () -> StartOutcome
    ): StartOutcome {
        val started = SystemClock.elapsedRealtime()
        Log.i(LOG_TAG, "strategy start name=$strategy package=$packageName")
        val outcome = block()
        Log.i(
            LOG_TAG,
            "strategy end name=$strategy package=$packageName success=${outcome.success} elapsedMs=${SystemClock.elapsedRealtime() - started}"
        )
        return outcome
    }

    private fun attemptLaunchIntentSender(
        context: Context,
        packageName: String
    ): StartOutcome {
        val method = AppLaunchMethod.PACKAGE_INTENT_SENDER

        return try {
            val sender = context.packageManager
                .getLaunchIntentSenderForPackage(packageName)

            sender.sendIntent(context, 0, null, null, null)

            StartOutcome.success(
                method = method,
                detail = "$packageName front-door IntentSender"
            )
        } catch (_: IntentSender.SendIntentException) {
            StartOutcome.notAvailable(
                method = method,
                detail = "$packageName front-door IntentSender was unavailable"
            )
        } catch (_: SecurityException) {
            StartOutcome.securityRejected(
                method = method,
                detail = "$packageName front-door IntentSender"
            )
        } catch (_: RuntimeException) {
            StartOutcome.runtimeFailure(
                method = method,
                detail = "$packageName front-door IntentSender"
            )
        }
    }

    private fun attemptPackageLaunchIntent(
        context: Context,
        packageName: String
    ): StartOutcome {
        val method = AppLaunchMethod.PACKAGE_LAUNCH_INTENT

        return try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(packageName)
                ?: return StartOutcome.notAvailable(
                    method = method,
                    detail = "$packageName launch intent was not visible"
                )

            attemptStartActivity(
                context = context,
                method = method,
                detail = "$packageName package launch intent",
                intent = launchIntent.prepareForExternalLaunch()
            )
        } catch (_: SecurityException) {
            StartOutcome.securityRejected(
                method = method,
                detail = "$packageName package launch intent lookup"
            )
        } catch (_: RuntimeException) {
            StartOutcome.runtimeFailure(
                method = method,
                detail = "$packageName package launch intent lookup"
            )
        }
    }

    private fun attemptExplicitComponentLaunch(
        context: Context,
        componentName: ComponentName,
        detail: String
    ): StartOutcome {
        return attemptStartActivity(
            context = context,
            method = AppLaunchMethod.EXPLICIT_LAUNCHER_COMPONENT,
            detail = detail,
            intent = Intent.makeMainActivity(componentName)
                .prepareForExternalLaunch()
        )
    }

    private fun attemptStartActivity(
        context: Context,
        method: AppLaunchMethod,
        detail: String,
        intent: Intent
    ): StartOutcome {
        val started = SystemClock.elapsedRealtime()
        operationStartedForDiagnostics.get()?.let { operationStarted ->
            Log.i(
                LOG_TAG,
                "total time before startActivity elapsedMs=${SystemClock.elapsedRealtime() - operationStarted} method=$method"
            )
        }
        Log.i(LOG_TAG, "startActivity call start method=$method detail=$detail")
        return try {
            context.startActivity(intent)
            Log.i(
                LOG_TAG,
                "startActivity call end method=$method success=true elapsedMs=${SystemClock.elapsedRealtime() - started}"
            )
            StartOutcome.success(method, detail)
        } catch (_: ActivityNotFoundException) {
            Log.i(
                LOG_TAG,
                "startActivity call end method=$method success=false reason=notFound elapsedMs=${SystemClock.elapsedRealtime() - started}"
            )
            StartOutcome.notAvailable(method, "$detail had no matching activity")
        } catch (_: SecurityException) {
            Log.i(
                LOG_TAG,
                "startActivity call end method=$method success=false reason=security elapsedMs=${SystemClock.elapsedRealtime() - started}"
            )
            StartOutcome.securityRejected(method, detail)
        } catch (_: RuntimeException) {
            Log.i(
                LOG_TAG,
                "startActivity call end method=$method success=false reason=runtime elapsedMs=${SystemClock.elapsedRealtime() - started}"
            )
            StartOutcome.runtimeFailure(method, detail)
        }
    }

    private fun attemptLauncherAppsLaunch(
        context: Context,
        packageName: String
    ): StartOutcome {
        val method = AppLaunchMethod.EXPLICIT_LAUNCHER_COMPONENT

        return try {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
                ?: return StartOutcome.notAvailable(
                    method = method,
                    detail = "$packageName LauncherApps service was unavailable"
                )

            val activity = launcherApps
                .getActivityList(packageName, Process.myUserHandle())
                .asSequence()
                .filter(::isLauncherActivityEnabled)
                .sortedBy { launcherActivity ->
                    launcherActivity.componentName.className
                }
                .firstOrNull()
                ?: return StartOutcome.notAvailable(
                    method = method,
                    detail = "$packageName had no LauncherApps activity"
                )

            launcherApps.startMainActivity(
                activity.componentName,
                Process.myUserHandle(),
                null,
                null
            )

            StartOutcome.success(
                method = method,
                detail = "$packageName LauncherApps main activity"
            )
        } catch (_: ActivityNotFoundException) {
            StartOutcome.notAvailable(
                method = method,
                detail = "$packageName LauncherApps activity was unavailable"
            )
        } catch (_: SecurityException) {
            StartOutcome.securityRejected(
                method = method,
                detail = "$packageName LauncherApps activity"
            )
        } catch (_: IllegalStateException) {
            StartOutcome.notAvailable(
                method = method,
                detail = "$packageName LauncherApps profile was unavailable"
            )
        } catch (_: RuntimeException) {
            StartOutcome.runtimeFailure(
                method = method,
                detail = "$packageName LauncherApps activity"
            )
        }
    }

    private fun inspectPackageState(
        packageManager: PackageManager,
        packageName: String
    ): PackageState {
        return try {
            val applicationInfo = getApplicationInfoCompat(
                packageManager = packageManager,
                packageName = packageName
            )

            PackageState(
                visible = true,
                disabled = !applicationInfo.enabled
            )
        } catch (_: PackageManager.NameNotFoundException) {
            PackageState(visible = false, disabled = false)
        } catch (_: SecurityException) {
            PackageState(visible = false, disabled = false)
        } catch (_: RuntimeException) {
            PackageState(visible = false, disabled = false)
        }
    }

    private fun queryEnabledLauncherComponents(
        packageManager: PackageManager,
        packageName: String
    ): List<ComponentName> {
        return packageScopedMainIntents(packageName)
            .asSequence()
            .flatMap { intent ->
                queryIntentActivitiesCompat(packageManager, intent).asSequence()
            }
            .mapNotNull(ResolveInfo::activityInfo)
            .filter(::isActivityEnabledAndExported)
            .map { activityInfo ->
                ComponentName(activityInfo.packageName, activityInfo.name)
            }
            .distinct()
            .sortedWith(
                compareBy<ComponentName>(
                    { component -> component.packageName },
                    { component -> component.className }
                )
            )
            .toList()
    }

    private fun packageScopedMainIntents(packageName: String): List<Intent> {
        return listOf(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_INFO)
                .setPackage(packageName)
                .prepareForExternalLaunch(),
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
                .prepareForExternalLaunch()
        )
    }

    private fun isActivityEnabledAndExported(activityInfo: ActivityInfo): Boolean {
        return activityInfo.enabled &&
                activityInfo.exported &&
                activityInfo.applicationInfo.enabled
    }

    private fun isLauncherActivityEnabled(activityInfo: LauncherActivityInfo): Boolean {
        val applicationInfo = activityInfo.applicationInfo
        return applicationInfo.enabled
    }

    private fun fullyQualifiedActivityName(
        packageName: String,
        activityName: String
    ): String {
        val cleanActivityName = activityName.trim()

        return if (cleanActivityName.startsWith('.')) {
            packageName + cleanActivityName
        } else {
            cleanActivityName
        }
    }

    private fun Intent.prepareForExternalLaunch(): Intent {
        return addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
    }

    @Suppress("DEPRECATION")
    private fun queryIntentActivitiesCompat(
        packageManager: PackageManager,
        intent: Intent
    ): List<ResolveInfo> {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DIRECT_BOOT_AWARE or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(flags.toLong())
            )
        } else {
            packageManager.queryIntentActivities(intent, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun getApplicationInfoCompat(
        packageManager: PackageManager,
        packageName: String
    ): ApplicationInfo {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(flags.toLong())
            )
        } else {
            packageManager.getApplicationInfo(packageName, flags)
        }
    }

    private fun systemLaunchPlan(systemAction: String): SystemLaunchPlan? {
        return when (systemAction) {
            "settings" -> SystemLaunchPlan(
                preferPackages = false,
                packageNames = listOf("com.android.settings"),
                intentFactories = listOf(
                    { Intent(Settings.ACTION_SETTINGS) }
                )
            )

            "camera" -> SystemLaunchPlan(
                preferPackages = false,
                packageNames = listOf(
                    "com.android.camera",
                    "com.android.camera2",
                    "com.google.android.GoogleCamera",
                    "com.vivo.camera"
                ),
                intentFactories = listOf(
                    { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }
                )
            )

            "dialer" -> SystemLaunchPlan(
                preferPackages = false,
                packageNames = listOf(
                    "com.google.android.dialer",
                    "com.android.dialer",
                    "com.android.contacts"
                ),
                intentFactories = listOf(
                    { Intent(Intent.ACTION_DIAL) }
                )
            )

            "messages" -> SystemLaunchPlan(
                preferPackages = true,
                packageNames = listOf(
                    "com.google.android.apps.messaging",
                    "com.android.mms",
                    "com.android.messaging",
                    "com.vivo.mms"
                ),
                intentFactories = listOf(
                    { mainSelectorIntent(Intent.CATEGORY_APP_MESSAGING) }
                )
            )

            "contacts" -> SystemLaunchPlan(
                preferPackages = true,
                packageNames = listOf(
                    "com.google.android.contacts",
                    "com.android.contacts",
                    "com.vivo.contacts"
                ),
                intentFactories = listOf(
                    { mainSelectorIntent(Intent.CATEGORY_APP_CONTACTS) }
                )
            )

            "gallery" -> SystemLaunchPlan(
                preferPackages = true,
                packageNames = listOf(
                    "com.vivo.gallery",
                    "com.google.android.apps.photos",
                    "com.android.gallery3d"
                ),
                intentFactories = listOf(
                    { mainSelectorIntent(Intent.CATEGORY_APP_GALLERY) }
                )
            )

            "calendar" -> SystemLaunchPlan(
                preferPackages = true,
                packageNames = listOf(
                    "com.google.android.calendar",
                    "com.android.calendar",
                    "com.vivo.calendar"
                ),
                intentFactories = listOf(
                    { mainSelectorIntent(Intent.CATEGORY_APP_CALENDAR) }
                )
            )

            "calculator" -> SystemLaunchPlan(
                preferPackages = true,
                packageNames = listOf(
                    "com.vivo.calculator",
                    "com.android.bbkcalculator",
                    "com.android.calculator2",
                    "com.google.android.calculator"
                ),
                intentFactories = listOf(
                    { mainSelectorIntent(Intent.CATEGORY_APP_CALCULATOR) }
                )
            )

            "clock" -> SystemLaunchPlan(
                preferPackages = true,
                packageNames = listOf(
                    "com.android.deskclock",
                    "com.google.android.deskclock",
                    "com.vivo.alarmclock",
                    "com.android.alarmclock"
                ),
                intentFactories = listOf(
                    { Intent(AlarmClock.ACTION_SHOW_ALARMS) }
                )
            )

            "recorder" -> SystemLaunchPlan(
                preferPackages = true,
                packageNames = listOf(
                    "com.vivo.soundrecorder",
                    "com.android.soundrecorder",
                    "com.google.android.apps.recorder"
                ),
                intentFactories = listOf(
                    { Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION) }
                )
            )

            "notes" -> SystemLaunchPlan(
                preferPackages = true,
                packageNames = listOf(
                    "com.vivo.notes",
                    "com.google.android.keep",
                    "com.samsung.android.app.notes",
                    "com.miui.notes",
                    "com.coloros.note"
                ),
                intentFactories = listOf(
                    { Intent(ACTION_CREATE_NOTE_COMPAT) }
                )
            )

            else -> null
        }
    }

    private fun mainSelectorIntent(category: String): Intent {
        return Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            category
        )
    }

    private fun newLaunchDeadline(): Long {
        return SystemClock.elapsedRealtime() + MAX_TOTAL_LAUNCH_DURATION_MS
    }

    private fun isLaunchBudgetExpired(deadlineElapsedRealtime: Long): Boolean {
        return Thread.currentThread().isInterrupted ||
                (deadlineElapsedRealtime != Long.MAX_VALUE &&
                        SystemClock.elapsedRealtime() >= deadlineElapsedRealtime)
    }

    private fun launchTimeoutResult(
        displayName: String,
        attempts: List<LaunchAttempt> = emptyList()
    ): LaunchResult {
        return failureResult(
            message = "$displayName could not be opened within 3 seconds. It may be hidden or unavailable.",
            reason = AppLaunchFailureReason.LAUNCH_FAILED,
            attempts = attempts
        )
    }

    private fun failureResult(
        message: String,
        reason: AppLaunchFailureReason,
        attempts: List<LaunchAttempt> = emptyList()
    ): LaunchResult {
        return LaunchResult(
            success = false,
            message = message,
            failureReason = reason,
            attempts = attempts.take(MAX_RECORDED_ATTEMPTS)
        )
    }

    private data class PackageState(
        val visible: Boolean,
        val disabled: Boolean
    )

    private data class PackageLaunchOutcome(
        val attempts: List<LaunchAttempt>,
        val successfulMethod: AppLaunchMethod?,
        val securityRejected: Boolean,
        val runtimeFailureObserved: Boolean,
        val timedOut: Boolean
    ) {
        companion object {
            fun success(
                method: AppLaunchMethod,
                attempts: List<LaunchAttempt>
            ): PackageLaunchOutcome {
                return PackageLaunchOutcome(
                    attempts = attempts,
                    successfulMethod = method,
                    securityRejected = false,
                    runtimeFailureObserved = false,
                    timedOut = false
                )
            }
        }
    }

    private data class StartOutcome(
        val attempt: LaunchAttempt,
        val success: Boolean,
        val securityRejected: Boolean,
        val runtimeFailure: Boolean
    ) {
        companion object {
            fun success(
                method: AppLaunchMethod,
                detail: String
            ): StartOutcome {
                return StartOutcome(
                    attempt = LaunchAttempt(method, true, detail),
                    success = true,
                    securityRejected = false,
                    runtimeFailure = false
                )
            }

            fun notAvailable(
                method: AppLaunchMethod,
                detail: String
            ): StartOutcome {
                return StartOutcome(
                    attempt = LaunchAttempt(method, false, detail),
                    success = false,
                    securityRejected = false,
                    runtimeFailure = false
                )
            }

            fun securityRejected(
                method: AppLaunchMethod,
                detail: String
            ): StartOutcome {
                return failed(
                    method = method,
                    detail = "$SECURITY_DETAIL_PREFIX$detail",
                    securityRejected = true,
                    runtimeFailure = false
                )
            }

            fun runtimeFailure(
                method: AppLaunchMethod,
                detail: String
            ): StartOutcome {
                return failed(
                    method = method,
                    detail = "$RUNTIME_DETAIL_PREFIX$detail",
                    securityRejected = false,
                    runtimeFailure = true
                )
            }

            private fun failed(
                method: AppLaunchMethod,
                detail: String,
                securityRejected: Boolean,
                runtimeFailure: Boolean
            ): StartOutcome {
                return StartOutcome(
                    attempt = LaunchAttempt(method, false, detail),
                    success = false,
                    securityRejected = securityRejected,
                    runtimeFailure = runtimeFailure
                )
            }
        }
    }

    private data class SystemLaunchPlan(
        val preferPackages: Boolean,
        val packageNames: List<String>,
        val intentFactories: List<() -> Intent>
    )

    private const val SECURITY_DETAIL_PREFIX = "Security rejected: "
    private const val RUNTIME_DETAIL_PREFIX = "Runtime failure: "
}
