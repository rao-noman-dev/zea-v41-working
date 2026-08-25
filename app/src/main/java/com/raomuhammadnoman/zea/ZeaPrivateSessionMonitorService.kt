package com.raomuhammadnoman.zea

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ZeaPrivateSessionMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    @Volatile
    private var protectedPackageName: String = ""

    @Volatile
    private var diagnosticSessionId: String = ""

    private val resolvedHomePackageName: String? by lazy {
        runCatching {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()
    }

    private val safetyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = protectedPackageName.ifBlank {
                ZeaDeviceOwnerController.activePrivatePackage(context)
            }
            if (packageName.isBlank()) return
            scope.launch {
                hideAndStop(packageName, intent.action.orEmpty().ifBlank { "safety broadcast" })
            }
        }
    }
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(safetyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(safetyReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty().trim()
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { packageName }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty().trim()

        if (packageName.isBlank() ||
            sessionId.isBlank() ||
            !ZeaDeviceOwnerController.isDeviceOwner(this) ||
            ZeaDeviceOwnerController.isProtectionPaused(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        protectedPackageName = packageName
        diagnosticSessionId = sessionId
        ZeaDeviceOwnerController.setActivePrivatePackage(this, packageName)

        startForeground(
            NOTIFICATION_ID,
            createSessionNotification(
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                title = "Zyro Private session active",
                text = "$displayName stays available until you lock the screen."
            )
        )

        val runtimePermissionBaseline =
            captureDangerousRuntimePermissionSnapshot(packageName)
        ZeaPrivateSessionDiagnosticLedger.record(
            context = this,
            sessionId = sessionId,
            eventCode = "RUNTIME_PERMISSION_BASELINE_CAPTURED",
            targetPackage = packageName,
            state = "querySucceeded=${runtimePermissionBaseline.querySucceeded};trackedDangerousPermissions=${runtimePermissionBaseline.grantStates.size};grantedCount=${runtimePermissionBaseline.grantStates.values.count { it }}",
            reason = "before_private_target_launch_dispatch"
        )

        monitorJob?.cancel()
        monitorJob = scope.launch {
            monitorPrivateSession(
                packageName = packageName,
                runtimePermissionBaseline = runtimePermissionBaseline
            )
        }

        val monitorReadySaved =
            ZeaDeviceOwnerController.markMonitorReady(this, sessionId, packageName)
        ZeaPrivateSessionDiagnosticLedger.record(
            context = this,
            sessionId = sessionId,
            eventCode = "MONITOR_READY",
            targetPackage = packageName,
            state = "saved=$monitorReadySaved",
            reason = "monitor_readiness_handshake"
        )
        if (!monitorReadySaved) {
            Log.e(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "monitor readiness handshake rejected package=$packageName session=$sessionId"
            )
            scope.launch { hideAndStop(packageName, "monitor readiness handshake rejected") }
            return START_NOT_STICKY
        }

        Log.i(
            ZEA_DEVICE_OWNER_LOG_TAG,
            "private session monitor ready package=$packageName session=$sessionId"
        )
        // A process death must not silently remove the component responsible
        // for re-hiding a temporarily visible private app. Ask Android to
        // redeliver this exact package/session intent when the service process
        // is recreated; persisted session state is revalidated on every start.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        ZeaPrivateSessionDiagnosticLedger.record(
            context = this,
            sessionId = diagnosticSessionId,
            eventCode = "MONITOR_SERVICE_DESTROYED",
            targetPackage = protectedPackageName,
            state = "protectedPackagePresent=${protectedPackageName.isNotBlank()}",
            reason = "service_on_destroy"
        )
        monitorJob?.cancel()
        bestEffortFailClosedRehide("monitor service destroyed")
        protectedPackageName = ""
        if (receiverRegistered) {
            try {
                unregisterReceiver(safetyReceiver)
            } catch (_: RuntimeException) {
                // Already removed by the framework.
            }
            receiverRegistered = false
        }
        scope.cancel()
        Log.i(ZEA_DEVICE_OWNER_LOG_TAG, "private session monitor stopped")
        super.onDestroy()
    }

    private suspend fun monitorPrivateSession(
        packageName: String,
        runtimePermissionBaseline: RuntimePermissionSnapshot
    ) {
        val diagnosticSession = diagnosticSessionId
        val serviceStarted = SystemClock.elapsedRealtime()
        var activeSessionStarted = 0L
        var hasObservedTargetForeground =
            ZeaDeviceOwnerController.isPrivateForegroundConfirmed(
                context = this,
                packageName = packageName
            )
        var firstForegroundCandidateSince = 0L
        var firstTargetEvidenceTimeoutDeferredForCandidate = false
        var outOfForegroundSince = 0L
        var unknownSince = 0L
        var temporarySystemSince = 0L
        var homeInteractionSince = 0L
        var permissionRecoverySince = 0L
        var permissionRelaunchAttempted = false
        var permissionRelaunchStarted = 0L
        var permissionGrantRecoveryCompleted = false
        var transitionSince = 0L
        var transitionFingerprint: TransitionFingerprint? = null
        var resolvedTransitionFingerprint: TransitionFingerprint? = null
        var evidenceAccumulator: UsageEvidenceAccumulator? = null
        var activeDispatchStateVerified = false

        if (hasObservedTargetForeground) {
            ZeaPrivateSessionDiagnosticLedger.record(
                context = this,
                sessionId = diagnosticSession,
                eventCode = "ACTIVE_MONITORING_STARTED",
                targetPackage = packageName,
                state = "source=stored_confirmation",
                reason = "monitor_session_entry"
            )
        }

        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()

            if (!ZeaDeviceOwnerController.isUsageAccessGranted(this)) {
                if (!hasObservedTargetForeground) {
                    val outcomeRecorded =
                        ZeaDeviceOwnerController.markPrivateForegroundFailed(
                            context = this,
                            packageName = packageName,
                            reason = "usage access unavailable or revoked"
                        )
                    hideAndStop(
                        initialPackageName = packageName,
                        reason = "usage access unavailable or revoked",
                        preservePrivateLaunchOutcome = outcomeRecorded
                    )
                } else {
                    hideAndStop(
                        packageName,
                        "usage access unavailable or revoked"
                    )
                }
                return
            }

            val dispatchElapsed =
                ZeaDeviceOwnerController.privateLaunchDispatchedElapsedRealtime(
                    context = this,
                    packageName = packageName
                )

            val dispatchWall =
                ZeaDeviceOwnerController.privateLaunchDispatchedWallClockMillis(
                    context = this,
                    packageName = packageName
                )

            if (dispatchElapsed == null || dispatchWall == null) {
                if (hasObservedTargetForeground) {
                    ZeaPrivateSessionDiagnosticLedger.record(
                        context = this,
                        sessionId = diagnosticSession,
                        eventCode = "ACTIVE_SESSION_STATE_LOST",
                        targetPackage = packageName,
                        state =
                            "dispatchElapsedPresent=${dispatchElapsed != null};" +
                                "dispatchWallPresent=${dispatchWall != null}",
                        reason = "confirmed_session_missing_monitor_timing_state"
                    )
                    hideAndStop(
                        packageName,
                        "active private session timing state was lost"
                    )
                    return
                }

                if (now - serviceStarted >= LAUNCH_DISPATCH_TIMEOUT_MILLIS) {
                    hideAndStop(
                        packageName,
                        "launch dispatch was not confirmed"
                    )
                    return
                }

                delay(POLL_INTERVAL_MILLIS)
                continue
            }

            if (
                dispatchElapsed > now ||
                dispatchWall > System.currentTimeMillis() +
                    WALL_CLOCK_FUTURE_TOLERANCE_MILLIS
            ) {
                val reason = "private launch timing state was inconsistent"
                val outcomeRecorded =
                    ZeaDeviceOwnerController.markPrivateForegroundFailed(
                        context = this,
                        packageName = packageName,
                        reason = reason
                    )
                hideAndStop(
                    initialPackageName = packageName,
                    reason = reason,
                    preservePrivateLaunchOutcome = outcomeRecorded
                )
                return
            }

            if (
                hasObservedTargetForeground &&
                !activeDispatchStateVerified
            ) {
                activeDispatchStateVerified = true
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = this,
                    sessionId = diagnosticSession,
                    eventCode = "ACTIVE_MONITOR_DISPATCH_STATE_RETAINED",
                    targetPackage = packageName,
                    state =
                        "dispatchElapsed=$dispatchElapsed;" +
                            "dispatchWall=$dispatchWall",
                    reason = "confirmed_session_monitor_lifetime_state"
                )
            }

            if (!hasObservedTargetForeground) {
                if (
                    ZeaDeviceOwnerController.isPrivateForegroundConfirmed(
                        context = this,
                        packageName = packageName
                    )
                ) {
                    hasObservedTargetForeground = true
                    activeSessionStarted = now
                    ZeaPrivateSessionDiagnosticLedger.record(
                        context = this,
                        sessionId = diagnosticSession,
                        eventCode = "ACTIVE_MONITORING_STARTED",
                        targetPackage = packageName,
                        state = "source=confirmation_transition",
                        reason = "target_foreground_confirmed"
                    )
                }
                else {
                    val foregroundPredicate =
                        hasConfirmedTargetForegroundSince(
                            targetPackage = packageName,
                            notBeforeTimestamp = dispatchWall
                        )

                    if (foregroundPredicate) {
                        if (firstForegroundCandidateSince == 0L) {
                            firstForegroundCandidateSince = now
                            ZeaPrivateSessionDiagnosticLedger.record(
                                context = this,
                                sessionId = diagnosticSession,
                                eventCode = "FOREGROUND_STABILITY_CANDIDATE_STARTED",
                                targetPackage = packageName,
                                state =
                                    "requiredMs=$FIRST_FOREGROUND_STABILITY_MILLIS",
                                reason = "usage_predicate_true"
                            )
                        }

                        if (
                            now - firstForegroundCandidateSince >=
                                FIRST_FOREGROUND_STABILITY_MILLIS
                        ) {
                            val confirmationSaved =
                                ZeaDeviceOwnerController.markPrivateForegroundConfirmed(
                                    context = this,
                                    packageName = packageName
                                )
                            ZeaPrivateSessionDiagnosticLedger.record(
                                context = this,
                                sessionId = diagnosticSession,
                                eventCode = "CONFIRMATION_SAVE_RESULT",
                                targetPackage = packageName,
                                state =
                                    "saved=$confirmationSaved;" +
                                        "stableMs=${now - firstForegroundCandidateSince}",
                                reason = "stable_usage_predicate_true"
                            )

                            if (!confirmationSaved) {
                                val reason =
                                    "private foreground confirmation state was inconsistent"
                                val outcomeRecorded =
                                    ZeaDeviceOwnerController.markPrivateForegroundFailed(
                                        context = this,
                                        packageName = packageName,
                                        reason = reason
                                    )
                                hideAndStop(
                                    initialPackageName = packageName,
                                    reason = reason,
                                    preservePrivateLaunchOutcome = outcomeRecorded
                                )
                                return
                            }

                            hasObservedTargetForeground = true
                            activeSessionStarted = now
                            ZeaPrivateSessionDiagnosticLedger.record(
                                context = this,
                                sessionId = diagnosticSession,
                                eventCode = "ACTIVE_MONITORING_STARTED",
                                targetPackage = packageName,
                                state = "source=stable_confirmation_transition",
                                reason = "target_foreground_stable"
                            )
                            outOfForegroundSince = 0L
                            unknownSince = 0L
                            temporarySystemSince = 0L
                            homeInteractionSince = 0L
                        }
                    } else if (firstForegroundCandidateSince != 0L) {
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "FOREGROUND_STABILITY_CANDIDATE_RESET",
                            targetPackage = packageName,
                            state =
                                "observedMs=${now - firstForegroundCandidateSince}",
                            reason = "usage_predicate_false_before_stable"
                        )
                        firstForegroundCandidateSince = 0L
                    }

                    if (
                        !hasObservedTargetForeground &&
                        firstForegroundCandidateSince != 0L &&
                        now - dispatchElapsed >=
                            FIRST_TARGET_EVIDENCE_TIMEOUT_MILLIS &&
                        !firstTargetEvidenceTimeoutDeferredForCandidate
                    ) {
                        firstTargetEvidenceTimeoutDeferredForCandidate = true
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "FIRST_TARGET_EVIDENCE_TIMEOUT_DEFERRED",
                            targetPackage = packageName,
                            state =
                                "candidateActive=true;" +
                                    "candidateMs=${now - firstForegroundCandidateSince};" +
                                    "timeoutMs=$FIRST_TARGET_EVIDENCE_TIMEOUT_MILLIS",
                            reason = "stability_candidate_active"
                        )
                    }

                    if (
                        !hasObservedTargetForeground &&
                        firstForegroundCandidateSince == 0L &&
                        now - dispatchElapsed >=
                            FIRST_TARGET_EVIDENCE_TIMEOUT_MILLIS
                    ) {
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "MONITOR_TIMEOUT",
                            targetPackage = packageName,
                            state =
                                "confirmationReached=false;candidateActive=false;" +
                                    "timeoutMs=$FIRST_TARGET_EVIDENCE_TIMEOUT_MILLIS",
                            reason = "first_target_evidence"
                        )
                        val reason = "target foreground confirmation timed out"
                        val outcomeRecorded =
                            ZeaDeviceOwnerController.markPrivateForegroundFailed(
                                context = this,
                                packageName = packageName,
                                reason = reason
                            )
                        hideAndStop(
                            initialPackageName = packageName,
                            reason = reason,
                            preservePrivateLaunchOutcome = outcomeRecorded
                        )
                        return
                    }
                }

                delay(POLL_INTERVAL_MILLIS)
                continue
            }

            if (activeSessionStarted <= 0L) {
                activeSessionStarted = now
            }

            if (now - activeSessionStarted >= MAX_SESSION_MILLIS) {
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = this,
                    sessionId = diagnosticSession,
                    eventCode = "MONITOR_TIMEOUT",
                    targetPackage = packageName,
                    state = "activeSession=true",
                    reason = "maximum_session_duration"
                )
                hideAndStop(
                    packageName,
                    "maximum session duration reached"
                )
                return
            }

            val activeAccumulator = evidenceAccumulator?.takeIf {
                it.targetPackage == packageName &&
                    it.notBeforeTimestamp == dispatchWall
            } ?: UsageEvidenceAccumulator(
                targetPackage = packageName,
                notBeforeTimestamp = dispatchWall,
                queryCursorTimestamp = dispatchWall
            ).also { evidenceAccumulator = it }

            val rawObservation = observeTarget(
                targetPackage = packageName,
                notBeforeTimestamp = dispatchWall,
                accumulator = activeAccumulator
            )
            val rawTransitionFingerprint =
                rawObservation.transitionFingerprint(packageName)
            var effectiveEvidenceState = rawObservation.evidenceState
            val shouldCheckRuntimePermissionDelta =
                !permissionGrantRecoveryCompleted &&
                    (
                        rawObservation.latestForegroundPackage ==
                            resolvedHomePackageName ||
                            rawObservation.latestForegroundPackage == packageName
                    )
            val runtimePermissionDelta = if (shouldCheckRuntimePermissionDelta) {
                detectRuntimePermissionDelta(
                    targetPackage = packageName,
                    baseline = runtimePermissionBaseline
                )
            } else {
                RuntimePermissionDelta(
                    querySucceeded = true,
                    newlyGranted = emptySet(),
                    currentlyGrantedCount = runtimePermissionBaseline.grantStates.values.count { it }
                )
            }
            if (
                rawObservation.latestForegroundPackage == packageName &&
                rawObservation.targetForeground == true &&
                runtimePermissionDelta.querySucceeded &&
                runtimePermissionDelta.newlyGranted.isNotEmpty()
            ) {
                permissionGrantRecoveryCompleted = true
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = this,
                    sessionId = diagnosticSession,
                    eventCode = "RUNTIME_PERMISSION_GRANT_COMPLETED_IN_TARGET",
                    targetPackage = packageName,
                    state = "newlyGrantedCount=${runtimePermissionDelta.newlyGranted.size};relaunchRequired=false",
                    reason = "target_resumed_without_home_recovery"
                )
            }
            if (
                !permissionGrantRecoveryCompleted &&
                rawObservation.latestForegroundPackage == resolvedHomePackageName &&
                rawObservation.targetForeground == false &&
                runtimePermissionDelta.querySucceeded &&
                runtimePermissionDelta.newlyGranted.isNotEmpty()
            ) {
                effectiveEvidenceState =
                    ForegroundEvidenceState.PERMISSION_GRANT_RECOVERY
            }

            if (
                rawObservation.evidenceState ==
                    ForegroundEvidenceState.TRANSITION_OR_INCONCLUSIVE &&
                rawTransitionFingerprint != null
            ) {
                when {
                    resolvedTransitionFingerprint == rawTransitionFingerprint -> {
                        effectiveEvidenceState = ForegroundEvidenceState.TARGET_ACTIVE
                    }

                    transitionFingerprint != rawTransitionFingerprint -> {
                        transitionFingerprint = rawTransitionFingerprint
                        transitionSince = now
                        resolvedTransitionFingerprint = null
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "FOREGROUND_TRANSITION_STARTED",
                            targetPackage = packageName,
                            state = "startElapsed=$now;latestForegroundPackage=${rawObservation.latestForegroundPackage};targetStateTime=${rawObservation.targetStateTimestamp};latestForegroundTime=${rawObservation.latestForegroundTimestamp}",
                            reason = "target_global_foreground_without_positive_non_target_departure"
                        )
                    }

                    now - transitionSince >= TRANSITION_EVIDENCE_GRACE_MILLIS -> {
                        resolvedTransitionFingerprint = rawTransitionFingerprint
                        transitionFingerprint = null
                        transitionSince = 0L
                        effectiveEvidenceState = ForegroundEvidenceState.TARGET_ACTIVE
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "FOREGROUND_TRANSITION_RESOLVED_TARGET_ACTIVE",
                            targetPackage = packageName,
                            state = "latestForegroundPackage=${rawObservation.latestForegroundPackage};targetStateTime=${rawObservation.targetStateTimestamp};latestForegroundTime=${rawObservation.latestForegroundTimestamp}",
                            reason = "same_session_ordered_evidence_no_newer_positive_non_target_foreground"
                        )
                    }
                }
            } else {
                transitionSince = 0L
                transitionFingerprint = null
                resolvedTransitionFingerprint = null
            }

            val observation = rawObservation.copy(
                evidenceState = effectiveEvidenceState,
                targetForeground = if (
                    effectiveEvidenceState == ForegroundEvidenceState.TARGET_ACTIVE
                ) {
                    true
                } else {
                    rawObservation.targetForeground
                }
            )
            val latestClassification = diagnosticPackageClass(
                targetPackage = packageName,
                candidatePackage = observation.latestForegroundPackage
            )
            ZeaPrivateSessionDiagnosticLedger.record(
                context = this,
                sessionId = diagnosticSession,
                eventCode = "LATEST_FOREGROUND_CLASSIFICATION",
                targetPackage = packageName,
                state = "rawEvidenceState=${rawObservation.evidenceState};effectiveEvidenceState=${observation.evidenceState};classification=$latestClassification;targetForeground=${observation.targetForeground};latestForegroundTime=${observation.latestForegroundTimestamp};targetStateTime=${observation.targetStateTimestamp};positiveNonTargetTime=${observation.latestPositiveNonTargetForegroundTimestamp};newlyGrantedRuntimePermissionCount=${runtimePermissionDelta.newlyGranted.size};permissionRelaunchAttempted=$permissionRelaunchAttempted;permissionGrantRecoveryCompleted=$permissionGrantRecoveryCompleted",
                reason = "active_observation"
            )
            ZeaPrivateSessionDiagnosticLedger.record(
                context = this,
                sessionId = diagnosticSession,
                eventCode = when (observation.evidenceState) {
                    ForegroundEvidenceState.TARGET_ACTIVE ->
                        "ACTIVE_OBSERVATION_TARGET"
                    ForegroundEvidenceState.DEFINITIVE_DEPARTURE ->
                        "ACTIVE_OBSERVATION_OTHER"
                    ForegroundEvidenceState.TRANSITION_OR_INCONCLUSIVE ->
                        "ACTIVE_OBSERVATION_TRANSITION"
                    ForegroundEvidenceState.TEMPORARY_SYSTEM ->
                        "ACTIVE_OBSERVATION_TEMPORARY_SYSTEM"
                    ForegroundEvidenceState.HOME_SYSTEM_INTERACTION_GRACE ->
                        "ACTIVE_OBSERVATION_HOME_SYSTEM_INTERACTION_GRACE"
                    ForegroundEvidenceState.PERMISSION_GRANT_RECOVERY ->
                        "ACTIVE_OBSERVATION_PERMISSION_GRANT_RECOVERY"
                    ForegroundEvidenceState.UNKNOWN ->
                        "ACTIVE_OBSERVATION_UNKNOWN"
                },
                targetPackage = packageName,
                state = "evidenceState=${observation.evidenceState};classification=$latestClassification",
                reason = "active_observation"
            )

            when (observation.evidenceState) {
                ForegroundEvidenceState.TARGET_ACTIVE -> {
                    if (
                        permissionRecoverySince > 0L ||
                        permissionRelaunchStarted > 0L
                    ) {
                        permissionGrantRecoveryCompleted = true
                    }
                    if (permissionRelaunchStarted > 0L) {
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "PERMISSION_RECOVERY_TARGET_RESUMED",
                            targetPackage = packageName,
                            state = "elapsedMs=${now - permissionRelaunchStarted};relaunchAttempted=$permissionRelaunchAttempted",
                            reason = "target_foreground_after_permission_grant_relaunch"
                        )
                        permissionRelaunchStarted = 0L
                    }
                    outOfForegroundSince = 0L
                    unknownSince = 0L
                    temporarySystemSince = 0L
                    homeInteractionSince = 0L
                    permissionRecoverySince = 0L
                }

                ForegroundEvidenceState.TEMPORARY_SYSTEM -> {
                    unknownSince = 0L
                    outOfForegroundSince = 0L
                    homeInteractionSince = 0L
                    permissionRecoverySince = 0L

                    // Diagnostic only now: system UI on top no longer ends the
                    // session. Screen-off/shutdown and the maximum session
                    // duration remain the enforced boundaries.
                    if (temporarySystemSince == 0L) {
                        temporarySystemSince = now
                    }
                }

                ForegroundEvidenceState.PERMISSION_GRANT_RECOVERY -> {
                    unknownSince = 0L
                    outOfForegroundSince = 0L
                    temporarySystemSince = 0L
                    homeInteractionSince = 0L

                    if (permissionRecoverySince == 0L) {
                        permissionRecoverySince = now
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "RUNTIME_PERMISSION_GRANT_DELTA_DETECTED",
                            targetPackage = packageName,
                            state = "newlyGrantedCount=${runtimePermissionDelta.newlyGranted.size};currentlyGrantedCount=${runtimePermissionDelta.currentlyGrantedCount};latestForegroundPackage=${observation.latestForegroundPackage};targetStateTime=${observation.targetStateTimestamp};latestForegroundTime=${observation.latestForegroundTimestamp}",
                            reason = "same_session_denied_to_granted_runtime_permission_transition"
                        )
                    }

                    if (
                        !permissionRelaunchAttempted &&
                        now - permissionRecoverySince >=
                            PERMISSION_RECOVERY_SETTLE_MILLIS
                    ) {
                        permissionRelaunchAttempted = true
                        permissionRelaunchStarted = now
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "PERMISSION_RECOVERY_RELAUNCH_REQUESTED",
                            targetPackage = packageName,
                            state = "attempt=1;newlyGrantedCount=${runtimePermissionDelta.newlyGranted.size}",
                            reason = "positive_runtime_permission_grant_delta_and_home_foreground"
                        )
                        val relaunchResult = relaunchProtectedTarget(packageName)
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "PERMISSION_RECOVERY_RELAUNCH_RESULT",
                            targetPackage = packageName,
                            state = "success=${relaunchResult.success};strategy=${relaunchResult.strategy};errorClass=${relaunchResult.errorClass.orEmpty()}",
                            reason = "bounded_single_target_relaunch"
                        )
                        if (!relaunchResult.success) {
                            // Abandon recovery but keep the session alive;
                            // screen-off and the maximum session duration
                            // still enforce the privacy boundary.
                            permissionGrantRecoveryCompleted = true
                        }
                    }

                    if (
                        permissionRelaunchStarted > 0L &&
                        now - permissionRelaunchStarted >=
                            PERMISSION_RELAUNCH_CONFIRMATION_TIMEOUT_MILLIS
                    ) {
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "PERMISSION_RECOVERY_RELAUNCH_TIMEOUT",
                            targetPackage = packageName,
                            state = "elapsedMs=${now - permissionRelaunchStarted};attempts=1",
                            reason = "target_did_not_resume_after_bounded_permission_relaunch"
                        )
                        permissionGrantRecoveryCompleted = true
                        permissionRelaunchStarted = 0L
                    }
                }

                ForegroundEvidenceState.HOME_SYSTEM_INTERACTION_GRACE -> {
                    unknownSince = 0L
                    outOfForegroundSince = 0L
                    temporarySystemSince = 0L
                    permissionRecoverySince = 0L

                    // Diagnostic only now: going home (even permanently) no
                    // longer ends the session. The recents slide stays alive
                    // until the screen locks or the maximum session duration
                    // is reached.
                    if (homeInteractionSince == 0L) {
                        homeInteractionSince = now
                        ZeaPrivateSessionDiagnosticLedger.record(
                            context = this,
                            sessionId = diagnosticSession,
                            eventCode = "HOME_SYSTEM_INTERACTION_GRACE_STARTED",
                            targetPackage = packageName,
                            state = "startElapsed=$now;latestForegroundTime=${observation.latestForegroundTimestamp};targetStateTime=${observation.targetStateTimestamp}",
                            reason = "rapid_home_handoff_after_target_pause"
                        )
                    }
                }

                ForegroundEvidenceState.TRANSITION_OR_INCONCLUSIVE -> {
                    unknownSince = 0L
                    outOfForegroundSince = 0L
                    temporarySystemSince = 0L
                    homeInteractionSince = 0L
                    permissionRecoverySince = 0L
                }

                ForegroundEvidenceState.UNKNOWN -> {
                    temporarySystemSince = 0L
                    outOfForegroundSince = 0L
                    homeInteractionSince = 0L
                    permissionRecoverySince = 0L

                    // Diagnostic only now: inconclusive usage evidence no
                    // longer fails the session closed. Fail-closed behavior
                    // remains for revoked usage access and lost timing state.
                    if (unknownSince == 0L) {
                        unknownSince = now
                    }
                }

                ForegroundEvidenceState.DEFINITIVE_DEPARTURE -> {
                    // The user left the protected app (home, recents slide,
                    // or another app). The session intentionally stays alive
                    // so the recents slide keeps working; the app is re-hidden
                    // when the screen turns off, the device shuts down, the
                    // maximum session duration elapses, or another private
                    // launch supersedes this one.
                    unknownSince = 0L
                    temporarySystemSince = 0L
                    homeInteractionSince = 0L
                    permissionRecoverySince = 0L
                    outOfForegroundSince = 0L
                }
            }

            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private enum class ForegroundEvidenceState {
        TARGET_ACTIVE,
        DEFINITIVE_DEPARTURE,
        TRANSITION_OR_INCONCLUSIVE,
        TEMPORARY_SYSTEM,
        HOME_SYSTEM_INTERACTION_GRACE,
        PERMISSION_GRANT_RECOVERY,
        UNKNOWN
    }

    private data class TransitionFingerprint(
        val latestForegroundTimestamp: Long,
        val targetStateTimestamp: Long
    )

    private data class RuntimePermissionSnapshot(
        val targetPackage: String,
        val grantStates: Map<String, Boolean>,
        val querySucceeded: Boolean,
        val capturedWallClockMillis: Long
    )

    private data class RuntimePermissionDelta(
        val querySucceeded: Boolean,
        val newlyGranted: Set<String>,
        val currentlyGrantedCount: Int
    )

    private data class TargetRelaunchResult(
        val success: Boolean,
        val strategy: String,
        val errorClass: String? = null
    )

    private data class ForegroundObservation(
        val evidenceState: ForegroundEvidenceState,
        val targetForeground: Boolean?,
        val latestForegroundPackage: String?,
        val latestForegroundTimestamp: Long,
        val targetStateTimestamp: Long,
        val latestPositiveNonTargetForegroundTimestamp: Long,
        val querySucceeded: Boolean
    )

    private data class UsageEvidenceAccumulator(
        val targetPackage: String,
        val notBeforeTimestamp: Long,
        var queryCursorTimestamp: Long,
        var latestForegroundPackage: String? = null,
        var latestForegroundTimestamp: Long = Long.MIN_VALUE,
        var latestTargetForegroundClassName: String? = null,
        var targetForeground: Boolean? = null,
        var targetStateTimestamp: Long = Long.MIN_VALUE,
        var latestPositiveNonTargetForegroundTimestamp: Long = Long.MIN_VALUE,
        val recentEventKeys: LinkedHashMap<String, Long> = linkedMapOf()
    )

    private fun ForegroundObservation.transitionFingerprint(
        targetPackage: String
    ): TransitionFingerprint? {
        if (evidenceState != ForegroundEvidenceState.TRANSITION_OR_INCONCLUSIVE) {
            return null
        }
        if (latestForegroundPackage != targetPackage) return null
        if (latestForegroundTimestamp == Long.MIN_VALUE) return null
        if (targetStateTimestamp == Long.MIN_VALUE) return null
        if (targetStateTimestamp <= latestForegroundTimestamp) return null
        return TransitionFingerprint(
            latestForegroundTimestamp = latestForegroundTimestamp,
            targetStateTimestamp = targetStateTimestamp
        )
    }

    private fun hasConfirmedTargetForegroundSince(
        targetPackage: String,
        notBeforeTimestamp: Long
    ): Boolean {
        val usageManager = getSystemService(UsageStatsManager::class.java)
        if (usageManager == null) {
            ZeaPrivateSessionDiagnosticLedger.record(
                context = this,
                sessionId = diagnosticSessionId,
                eventCode = "CONFIRMATION_PREDICATE_FALSE",
                targetPackage = targetPackage,
                state = "usageManagerAvailable=false",
                reason = "usage_manager_unavailable"
            )
            return false
        }

        val now = System.currentTimeMillis()

        if (
            notBeforeTimestamp <= 0L ||
            notBeforeTimestamp >
                now + WALL_CLOCK_FUTURE_TOLERANCE_MILLIS
        ) {
            ZeaPrivateSessionDiagnosticLedger.record(
                context = this,
                sessionId = diagnosticSessionId,
                eventCode = "CONFIRMATION_PREDICATE_FALSE",
                targetPackage = targetPackage,
                state = "notBeforeValid=false",
                reason = "invalid_dispatch_wall_clock"
            )
            return false
        }

        val queryStart = maxOf(
            now - EVENT_WINDOW_MILLIS,
            notBeforeTimestamp
        )

        ZeaPrivateSessionDiagnosticLedger.record(
            context = this,
            sessionId = diagnosticSessionId,
            eventCode = "USAGE_QUERY_STARTED",
            targetPackage = targetPackage,
            state = "queryStart=$queryStart;queryEnd=$now",
            reason = "confirmation_poll"
        )
        val events = try {
            usageManager.queryEvents(queryStart, now)
        } catch (_: RuntimeException) {
            ZeaPrivateSessionDiagnosticLedger.record(
                context = this,
                sessionId = diagnosticSessionId,
                eventCode = "CONFIRMATION_PREDICATE_FALSE",
                targetPackage = targetPackage,
                state = "querySucceeded=false",
                reason = "usage_query_runtime_exception"
            )
            return false
        }

        val event = UsageEvents.Event()
        var latestForegroundPackage: String? = null
        var latestForegroundTimestamp = Long.MIN_VALUE
        var latestTargetForegroundClassName: String? = null
        var targetForeground: Boolean? = null
        var targetStateTimestamp = Long.MIN_VALUE

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            if (event.timeStamp < notBeforeTimestamp) {
                continue
            }

            val isForegroundEvent =
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            event.eventType ==
                                UsageEvents.Event.ACTIVITY_RESUMED
                    )

            val isBackgroundEvent =
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                    (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            (
                                event.eventType ==
                                    UsageEvents.Event.ACTIVITY_PAUSED ||
                                    event.eventType ==
                                        UsageEvents.Event.ACTIVITY_STOPPED
                            )
                    )

            val staleTargetActivityBackground =
                isBackgroundEvent &&
                    shouldIgnoreStaleTargetActivityBackground(
                        targetPackage = targetPackage,
                        eventPackage = event.packageName,
                        eventClassName = event.className,
                        targetForeground = targetForeground,
                        latestForegroundPackage = latestForegroundPackage,
                        latestTargetForegroundClassName =
                            latestTargetForegroundClassName
                    )

            if (isForegroundEvent || isBackgroundEvent) {
                val observedClass = diagnosticPackageClass(
                    targetPackage = targetPackage,
                    candidatePackage = event.packageName
                )
                if (observedClass != "other") {
                    ZeaPrivateSessionDiagnosticLedger.record(
                        context = this,
                        sessionId = diagnosticSessionId,
                        eventCode = when {
                            staleTargetActivityBackground ->
                                "TARGET_BACKGROUND_EVENT_IGNORED_STALE_ACTIVITY"
                            observedClass == "target" && isForegroundEvent ->
                                "TARGET_FOREGROUND_EVENT"
                            observedClass == "target" ->
                                "TARGET_BACKGROUND_EVENT"
                            isForegroundEvent -> "RELEVANT_FOREGROUND_EVENT"
                            else -> "RELEVANT_BACKGROUND_EVENT"
                        },
                        targetPackage = targetPackage,
                        state =
                            "observedClass=$observedClass;eventType=${event.eventType};" +
                                "eventTime=${event.timeStamp};eventClass=${event.className.orEmpty()};" +
                                "activeTargetClass=${latestTargetForegroundClassName.orEmpty()}",
                        reason = "confirmation_usage_event"
                    )
                }
            }

            if (
                isForegroundEvent &&
                event.timeStamp >= latestForegroundTimestamp
            ) {
                latestForegroundTimestamp = event.timeStamp
                latestForegroundPackage = event.packageName
            }

            if (
                event.packageName == targetPackage &&
                isForegroundEvent &&
                event.timeStamp >= targetStateTimestamp
            ) {
                targetStateTimestamp = event.timeStamp
                targetForeground = true
                latestTargetForegroundClassName =
                    event.className?.takeIf { it.isNotBlank() }
            } else if (
                event.packageName == targetPackage &&
                isBackgroundEvent &&
                !staleTargetActivityBackground &&
                event.timeStamp >= targetStateTimestamp
            ) {
                targetStateTimestamp = event.timeStamp
                targetForeground = false
            }
        }

        val latestClassification = diagnosticPackageClass(
            targetPackage = targetPackage,
            candidatePackage = latestForegroundPackage
        )
        ZeaPrivateSessionDiagnosticLedger.record(
            context = this,
            sessionId = diagnosticSessionId,
            eventCode = "LATEST_FOREGROUND_CLASSIFICATION",
            targetPackage = targetPackage,
            state = "classification=$latestClassification;targetForeground=$targetForeground;targetStateTime=$targetStateTimestamp;latestTime=$latestForegroundTimestamp",
            reason = "confirmation_usage_query"
        )

        val confirmed = targetForeground == true &&
            targetStateTimestamp >= notBeforeTimestamp &&
            latestForegroundPackage == targetPackage &&
            latestForegroundTimestamp >= notBeforeTimestamp

        ZeaPrivateSessionDiagnosticLedger.record(
            context = this,
            sessionId = diagnosticSessionId,
            eventCode = if (confirmed) {
                "CONFIRMATION_PREDICATE_TRUE"
            } else {
                "CONFIRMATION_PREDICATE_FALSE"
            },
            targetPackage = targetPackage,
            state = "confirmed=$confirmed",
            reason = "confirmation_usage_predicate"
        )
        return confirmed
    }

    private fun diagnosticPackageClass(
        targetPackage: String,
        candidatePackage: String?
    ): String {
        if (candidatePackage.isNullOrBlank()) return "unknown"
        if (candidatePackage == targetPackage) return "target"
        if (candidatePackage == packageName) return "zea"
        if (candidatePackage in TEMPORARY_SYSTEM_PACKAGES) return "temporary_system"

        return if (candidatePackage == resolvedHomePackageName) "home" else "other"
    }

    private fun shouldIgnoreStaleTargetActivityBackground(
        targetPackage: String,
        eventPackage: String?,
        eventClassName: String?,
        targetForeground: Boolean?,
        latestForegroundPackage: String?,
        latestTargetForegroundClassName: String?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (eventPackage != targetPackage) return false
        if (targetForeground != true) return false
        if (latestForegroundPackage != targetPackage) return false

        val backgroundClass = eventClassName?.trim().orEmpty()
        val activeForegroundClass =
            latestTargetForegroundClassName?.trim().orEmpty()
        if (backgroundClass.isEmpty() || activeForegroundClass.isEmpty()) {
            return false
        }

        return backgroundClass != activeForegroundClass
    }

    private fun classifyForegroundEvidence(
        targetPackage: String,
        latestForegroundPackage: String?,
        targetForeground: Boolean?,
        latestForegroundTimestamp: Long,
        targetStateTimestamp: Long,
        latestPositiveNonTargetForegroundTimestamp: Long,
        notBeforeTimestamp: Long,
        querySucceeded: Boolean
    ): ForegroundEvidenceState {
        if (!querySucceeded) return ForegroundEvidenceState.UNKNOWN
        if (
            latestForegroundPackage.isNullOrBlank() ||
            latestForegroundTimestamp < notBeforeTimestamp
        ) {
            return ForegroundEvidenceState.UNKNOWN
        }

        if (latestForegroundPackage == targetPackage) {
            return if (
                targetForeground == false &&
                targetStateTimestamp > latestForegroundTimestamp
            ) {
                ForegroundEvidenceState.TRANSITION_OR_INCONCLUSIVE
            } else {
                ForegroundEvidenceState.TARGET_ACTIVE
            }
        }

        if (latestForegroundPackage in TEMPORARY_SYSTEM_PACKAGES) {
            return ForegroundEvidenceState.TEMPORARY_SYSTEM
        }

        val targetHomeHandoffDeltaMillis = when {
            targetStateTimestamp == Long.MIN_VALUE -> Long.MAX_VALUE
            latestForegroundTimestamp >= targetStateTimestamp ->
                latestForegroundTimestamp - targetStateTimestamp
            else -> targetStateTimestamp - latestForegroundTimestamp
        }
        if (
            latestForegroundPackage == resolvedHomePackageName &&
            targetForeground == false &&
            targetStateTimestamp >= notBeforeTimestamp &&
            targetHomeHandoffDeltaMillis <=
                RAPID_HOME_HANDOFF_WINDOW_MILLIS
        ) {
            return ForegroundEvidenceState.HOME_SYSTEM_INTERACTION_GRACE
        }

        if (
            !latestForegroundPackage.isNullOrBlank() &&
            latestForegroundPackage != targetPackage &&
            latestPositiveNonTargetForegroundTimestamp >= notBeforeTimestamp &&
            latestPositiveNonTargetForegroundTimestamp == latestForegroundTimestamp
        ) {
            return ForegroundEvidenceState.DEFINITIVE_DEPARTURE
        }

        if (targetForeground == false) {
            return ForegroundEvidenceState.UNKNOWN
        }

        if (targetForeground == true) {
            return ForegroundEvidenceState.TARGET_ACTIVE
        }

        return ForegroundEvidenceState.UNKNOWN
    }

    private fun observeTarget(
        targetPackage: String,
        notBeforeTimestamp: Long,
        accumulator: UsageEvidenceAccumulator
    ): ForegroundObservation {
        if (
            accumulator.targetPackage != targetPackage ||
            accumulator.notBeforeTimestamp != notBeforeTimestamp
        ) {
            return ForegroundObservation(
                evidenceState = ForegroundEvidenceState.UNKNOWN,
                targetForeground = null,
                latestForegroundPackage = null,
                latestForegroundTimestamp = Long.MIN_VALUE,
                targetStateTimestamp = Long.MIN_VALUE,
                latestPositiveNonTargetForegroundTimestamp = Long.MIN_VALUE,
                querySucceeded = false
            )
        }

        val usageManager = getSystemService(UsageStatsManager::class.java)
            ?: return accumulator.toObservation(querySucceeded = false)
        val now = System.currentTimeMillis()
        val queryStart = maxOf(
            notBeforeTimestamp,
            accumulator.queryCursorTimestamp - ACTIVE_QUERY_OVERLAP_MILLIS
        )
        ZeaPrivateSessionDiagnosticLedger.record(
            context = this,
            sessionId = diagnosticSessionId,
            eventCode = "SESSION_SCOPED_USAGE_QUERY",
            targetPackage = targetPackage,
            state = "queryStart=$queryStart;queryEnd=$now;cursor=${accumulator.queryCursorTimestamp};overlapMs=$ACTIVE_QUERY_OVERLAP_MILLIS;orderedAccumulator=true",
            reason = "active_monitoring_bounded_ordered_evidence"
        )
        val events = try {
            usageManager.queryEvents(queryStart, now)
        } catch (_: RuntimeException) {
            return accumulator.toObservation(querySucceeded = false)
        }

        val pruneBefore = queryStart - ACTIVE_QUERY_OVERLAP_MILLIS
        val keyIterator = accumulator.recentEventKeys.entries.iterator()
        while (keyIterator.hasNext()) {
            if (keyIterator.next().value < pruneBefore) {
                keyIterator.remove()
            }
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp < notBeforeTimestamp) continue

            val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            val isBackgroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                        event.eventType == UsageEvents.Event.ACTIVITY_STOPPED))
            if (!isForegroundEvent && !isBackgroundEvent) continue

            val eventKey = buildString {
                append(event.timeStamp)
                append('|')
                append(event.eventType)
                append('|')
                append(event.packageName.orEmpty())
                append('|')
                append(event.className.orEmpty())
            }
            if (accumulator.recentEventKeys.containsKey(eventKey)) continue
            accumulator.recentEventKeys[eventKey] = event.timeStamp

            val staleTargetActivityBackground =
                isBackgroundEvent &&
                    shouldIgnoreStaleTargetActivityBackground(
                        targetPackage = targetPackage,
                        eventPackage = event.packageName,
                        eventClassName = event.className,
                        targetForeground = accumulator.targetForeground,
                        latestForegroundPackage =
                            accumulator.latestForegroundPackage,
                        latestTargetForegroundClassName =
                            accumulator.latestTargetForegroundClassName
                    )

            if (staleTargetActivityBackground) {
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = this,
                    sessionId = diagnosticSessionId,
                    eventCode =
                        "TARGET_BACKGROUND_EVENT_IGNORED_STALE_ACTIVITY",
                    targetPackage = targetPackage,
                    state =
                        "eventType=${event.eventType};eventTime=${event.timeStamp};" +
                            "eventClass=${event.className.orEmpty()};" +
                            "activeTargetClass=${accumulator.latestTargetForegroundClassName.orEmpty()}",
                    reason = "active_monitoring_same_package_activity_handoff"
                )
            }

            if (isForegroundEvent && event.timeStamp >= accumulator.latestForegroundTimestamp) {
                accumulator.latestForegroundTimestamp = event.timeStamp
                accumulator.latestForegroundPackage = event.packageName
            }
            if (
                isForegroundEvent &&
                event.packageName != targetPackage &&
                event.timeStamp >= accumulator.latestPositiveNonTargetForegroundTimestamp
            ) {
                accumulator.latestPositiveNonTargetForegroundTimestamp = event.timeStamp
            }
            if (
                event.packageName == targetPackage &&
                isForegroundEvent &&
                event.timeStamp >= accumulator.targetStateTimestamp
            ) {
                accumulator.targetStateTimestamp = event.timeStamp
                accumulator.targetForeground = true
                accumulator.latestTargetForegroundClassName =
                    event.className?.takeIf { it.isNotBlank() }
            } else if (
                event.packageName == targetPackage &&
                isBackgroundEvent &&
                !staleTargetActivityBackground &&
                event.timeStamp >= accumulator.targetStateTimestamp
            ) {
                accumulator.targetStateTimestamp = event.timeStamp
                accumulator.targetForeground = false
            }
        }

        accumulator.queryCursorTimestamp = maxOf(
            accumulator.queryCursorTimestamp,
            now
        )
        while (accumulator.recentEventKeys.size > MAX_RECENT_EVENT_KEYS) {
            val iterator = accumulator.recentEventKeys.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            } else {
                break
            }
        }

        return accumulator.toObservation(querySucceeded = true)
    }

    private fun UsageEvidenceAccumulator.toObservation(
        querySucceeded: Boolean
    ): ForegroundObservation {
        val evidenceState = classifyForegroundEvidence(
            targetPackage = targetPackage,
            latestForegroundPackage = latestForegroundPackage,
            targetForeground = targetForeground,
            latestForegroundTimestamp = latestForegroundTimestamp,
            targetStateTimestamp = targetStateTimestamp,
            latestPositiveNonTargetForegroundTimestamp =
                latestPositiveNonTargetForegroundTimestamp,
            notBeforeTimestamp = notBeforeTimestamp,
            querySucceeded = querySucceeded
        )
        return ForegroundObservation(
            evidenceState = evidenceState,
            targetForeground = targetForeground,
            latestForegroundPackage = latestForegroundPackage,
            latestForegroundTimestamp = latestForegroundTimestamp,
            targetStateTimestamp = targetStateTimestamp,
            latestPositiveNonTargetForegroundTimestamp =
                latestPositiveNonTargetForegroundTimestamp,
            querySucceeded = querySucceeded
        )
    }

    @Suppress("DEPRECATION")
    private fun captureDangerousRuntimePermissionSnapshot(
        targetPackage: String
    ): RuntimePermissionSnapshot {
        val capturedAt = System.currentTimeMillis()
        return try {
            val packageInfo = packageManager.getPackageInfo(
                targetPackage,
                PackageManager.GET_PERMISSIONS
            )
            val requestedPermissions = packageInfo.requestedPermissions.orEmpty()
            val requestedFlags = packageInfo.requestedPermissionsFlags ?: IntArray(0)
            val grantStates = linkedMapOf<String, Boolean>()

            requestedPermissions.forEachIndexed { index, permissionName ->
                val permissionInfo = try {
                    packageManager.getPermissionInfo(permissionName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
                if (permissionInfo == null) return@forEachIndexed

                val protection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    permissionInfo.protection
                } else {
                    permissionInfo.protectionLevel and
                        PermissionInfo.PROTECTION_MASK_BASE
                }
                if (protection != PermissionInfo.PROTECTION_DANGEROUS) {
                    return@forEachIndexed
                }

                val flags = requestedFlags.getOrNull(index) ?: 0
                grantStates[permissionName] =
                    flags and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }

            RuntimePermissionSnapshot(
                targetPackage = targetPackage,
                grantStates = grantStates.toMap(),
                querySucceeded = true,
                capturedWallClockMillis = capturedAt
            )
        } catch (_: PackageManager.NameNotFoundException) {
            RuntimePermissionSnapshot(
                targetPackage = targetPackage,
                grantStates = emptyMap(),
                querySucceeded = false,
                capturedWallClockMillis = capturedAt
            )
        } catch (_: RuntimeException) {
            RuntimePermissionSnapshot(
                targetPackage = targetPackage,
                grantStates = emptyMap(),
                querySucceeded = false,
                capturedWallClockMillis = capturedAt
            )
        }
    }

    private fun detectRuntimePermissionDelta(
        targetPackage: String,
        baseline: RuntimePermissionSnapshot
    ): RuntimePermissionDelta {
        if (
            !baseline.querySucceeded ||
            baseline.targetPackage != targetPackage
        ) {
            return RuntimePermissionDelta(
                querySucceeded = false,
                newlyGranted = emptySet(),
                currentlyGrantedCount = 0
            )
        }

        val current = captureDangerousRuntimePermissionSnapshot(targetPackage)
        if (!current.querySucceeded) {
            return RuntimePermissionDelta(
                querySucceeded = false,
                newlyGranted = emptySet(),
                currentlyGrantedCount = 0
            )
        }

        val newlyGranted = baseline.grantStates
            .filter { (permissionName, grantedAtStart) ->
                !grantedAtStart && current.grantStates[permissionName] == true
            }
            .keys
            .toSet()

        return RuntimePermissionDelta(
            querySucceeded = true,
            newlyGranted = newlyGranted,
            currentlyGrantedCount = current.grantStates.values.count { it }
        )
    }

    private fun relaunchProtectedTarget(
        targetPackage: String
    ): TargetRelaunchResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return try {
                val intentSender =
                    packageManager.getLaunchIntentSenderForPackage(targetPackage)
                val options = ActivityOptions.makeBasic().apply {
                    if (Build.VERSION.SDK_INT >= 36) {
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        )
                    } else if (Build.VERSION.SDK_INT >= 34) {
                        @Suppress("DEPRECATION")
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setPendingIntentBackgroundActivityLaunchAllowed(true)
                    }
                }
                startIntentSender(
                    intentSender,
                    null,
                    0,
                    0,
                    0,
                    options.toBundle()
                )
                TargetRelaunchResult(
                    success = true,
                    strategy = "launch_intent_sender_bounded_permission_recovery"
                )
            } catch (error: IntentSender.SendIntentException) {
                TargetRelaunchResult(
                    success = false,
                    strategy = "launch_intent_sender_bounded_permission_recovery",
                    errorClass = error.javaClass.name
                )
            } catch (error: RuntimeException) {
                TargetRelaunchResult(
                    success = false,
                    strategy = "launch_intent_sender_bounded_permission_recovery",
                    errorClass = error.javaClass.name
                )
            }
        }

        val launchIntent = packageManager
            .getLaunchIntentForPackage(targetPackage)
            ?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            ?: return TargetRelaunchResult(
                success = false,
                strategy = "legacy_launch_intent",
                errorClass = "launch_intent_unavailable"
            )

        return try {
            startActivity(launchIntent)
            TargetRelaunchResult(
                success = true,
                strategy = "legacy_launch_intent"
            )
        } catch (error: RuntimeException) {
            TargetRelaunchResult(
                success = false,
                strategy = "legacy_launch_intent",
                errorClass = error.javaClass.name
            )
        }
    }

    private fun bestEffortFailClosedRehide(reason: String) {
        val packageName = protectedPackageName.ifBlank {
            ZeaDeviceOwnerController.activePrivatePackage(this)
        }
        if (packageName.isBlank() ||
            !ZeaDeviceOwnerController.isDeviceOwner(this) ||
            ZeaDeviceOwnerController.isProtectionPaused(this)
        ) {
            return
        }

        // A newer private-launch session may have superseded this one while
        // the service was being torn down. In that case the newer session
        // owns the shared state, and hiding the old package here could race
        // with the new session's own unhide.
        val currentActivePackage =
            ZeaDeviceOwnerController.activePrivatePackage(this)
        if (currentActivePackage.isNotBlank() && currentActivePackage != packageName) {
            Log.i(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "fail-closed re-hide skipped package=$packageName reason=superseded_by_new_session"
            )
            return
        }

        val result = ZeaDeviceOwnerController.ensureProtectedState(this, packageName)
        Log.i(
            ZEA_DEVICE_OWNER_LOG_TAG,
            "fail-closed re-hide package=$packageName success=${result.success} reason=$reason"
        )
        if (result.success) {
            val preservePrivateLaunchOutcome =
                ZeaDeviceOwnerController.isPrivateForegroundConfirmed(
                    context = this,
                    packageName = packageName
                )
            ZeaDeviceOwnerController.clearPendingRehidePackage(this, packageName)
            ZeaDeviceOwnerController.clearActivePrivatePackage(this)
            ZeaDeviceOwnerController.clearMonitorSession(
                context = this,
                preservePrivateLaunchOutcome = preservePrivateLaunchOutcome
            )
            protectedPackageName = ""
        } else {
            ZeaDeviceOwnerController.markPendingRehidePackage(this, packageName)
        }
    }

    private suspend fun hideAndStop(
        initialPackageName: String,
        reason: String,
        preservePrivateLaunchOutcome: Boolean = false
    ) {
        val effectivePreservePrivateLaunchOutcome =
            preservePrivateLaunchOutcome ||
                ZeaDeviceOwnerController.isPrivateForegroundConfirmed(
                    context = this,
                    packageName = initialPackageName
                )
        ZeaPrivateSessionDiagnosticLedger.record(
            context = this,
            sessionId = diagnosticSessionId,
            eventCode = "FAIL_CLOSED_REHIDE",
            targetPackage = initialPackageName,
            state =
                "preserveOutcome=$effectivePreservePrivateLaunchOutcome",
            reason = reason
        )
        var packageName = initialPackageName
        var attempt = 0

        while (scope.isActive) {
            if (ZeaDeviceOwnerController.isProtectionPaused(this)) {
                Log.i(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "re-hide retry stopped because protection is paused package=$packageName"
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            ZeaPrivateSessionDiagnosticLedger.record(
                context = this,
                sessionId = diagnosticSessionId,
                eventCode = "REHIDE_REQUESTED",
                targetPackage = packageName,
                state = "requestedHidden=true;attempt=${attempt + 1}",
                reason = reason
            )
            val result = ZeaDeviceOwnerController.ensureProtectedState(this, packageName)
            ZeaPrivateSessionDiagnosticLedger.record(
                context = this,
                sessionId = diagnosticSessionId,
                eventCode = "REHIDE_RESULT",
                targetPackage = packageName,
                state = "success=${result.success};hidden=${result.hidden};uninstallBlocked=${result.uninstallBlocked};attempt=${attempt + 1}",
                reason = reason
            )
            Log.i(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "automatic re-hide package=$packageName success=${result.success} " +
                    "attempt=${attempt + 1} reason=$reason"
            )

            if (result.success) {
                ZeaDeviceOwnerController.clearPendingRehidePackage(this, packageName)
                val nextPackage = ZeaDeviceOwnerController.pendingRehidePackages(this).firstOrNull()
                if (nextPackage == null) {
                    ZeaDeviceOwnerController.clearActivePrivatePackage(this)
                    ZeaDeviceOwnerController.clearMonitorSession(
                        context = this,
                        preservePrivateLaunchOutcome =
                            effectivePreservePrivateLaunchOutcome
                    )
                    protectedPackageName = ""
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }

                packageName = nextPackage
                protectedPackageName = nextPackage
                ZeaDeviceOwnerController.setActivePrivatePackage(this, nextPackage)
                attempt = 0
                updateRecoveryNotification(nextPackage, attempt, "continuing pending recovery")
                continue
            }

            ZeaDeviceOwnerController.markPendingRehidePackage(this, packageName)
            attempt += 1
            updateRecoveryNotification(packageName, attempt, result.message)
            delay(
                if (attempt <= QUICK_RETRY_COUNT) {
                    QUICK_REHIDE_RETRY_MILLIS
                } else {
                    PERSISTENT_REHIDE_RETRY_MILLIS
                }
            )
        }
    }

    private fun updateRecoveryNotification(packageName: String, attempt: Int, detail: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID,
            createSessionNotification(
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                title = "Zyro fail-closed recovery active",
                text = "$packageName is not yet confirmed hidden. Retry $attempt: $detail"
            )
        )
    }

    private fun createSessionNotification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Zyro Private sessions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Visible safety monitor used while a private app is temporarily unhidden or requires fail-closed recovery."
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "zea_private_session"
        private const val NOTIFICATION_ID = 7008
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_DISPLAY_NAME = "display_name"
        private const val EXTRA_SESSION_ID = "session_id"

        private const val LAUNCH_DISPATCH_TIMEOUT_MILLIS = 10_000L
        private const val FIRST_TARGET_EVIDENCE_TIMEOUT_MILLIS = 30_000L
        private const val FIRST_FOREGROUND_STABILITY_MILLIS = 1_500L
        private const val WALL_CLOCK_FUTURE_TOLERANCE_MILLIS = 2_000L
        private const val OUT_OF_FOREGROUND_DEBOUNCE_MILLIS = 1_500L
        private const val TRANSITION_EVIDENCE_GRACE_MILLIS = 2_800L
        private const val RAPID_HOME_HANDOFF_WINDOW_MILLIS = 2_000L
        private const val HOME_SYSTEM_INTERACTION_GRACE_MILLIS = 8_000L
        private const val PERMISSION_RECOVERY_SETTLE_MILLIS = 500L
        private const val PERMISSION_RELAUNCH_CONFIRMATION_TIMEOUT_MILLIS = 7_000L
        private const val UNKNOWN_STATE_TIMEOUT_MILLIS = 3_500L
        private const val TEMPORARY_SYSTEM_TIMEOUT_MILLIS = 30_000L
        private const val POLL_INTERVAL_MILLIS = 400L
        private const val EVENT_WINDOW_MILLIS = 15_000L
        private const val ACTIVE_QUERY_OVERLAP_MILLIS = 2_100L
        private const val MAX_RECENT_EVENT_KEYS = 512
        private const val MAX_SESSION_MILLIS = 6L * 60L * 60L * 1_000L
        private const val QUICK_RETRY_COUNT = 5
        private const val QUICK_REHIDE_RETRY_MILLIS = 2_000L
        private const val PERSISTENT_REHIDE_RETRY_MILLIS = 15_000L

        private val TEMPORARY_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )

        fun start(
            context: Context,
            packageName: String,
            displayName: String,
            sessionId: String
        ): Boolean {
            val intent = Intent(context, ZeaPrivateSessionMonitorService::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (error: RuntimeException) {
                Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "monitor service start failed", error)
                false
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ZeaPrivateSessionMonitorService::class.java))
            } catch (_: RuntimeException) {
                // Best-effort cleanup. Persistent recovery state is intentionally not erased here.
            }
        }
    }
}
