package com.raomuhammadnoman.zea

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

/**
 * Phase 2 (P1) - Diagnostics screen hosting:
 *  - the System Check engine (13 structured checks with repair actions)
 *  - Emergency Recovery / Safe Mode (9 PIN-gated recovery actions)
 *  - a live Protection Health summary shared with the Home dashboard card
 */
@Composable
fun ZeaDiagnosticsScreen(onBack: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Screenshot protection: recovery tools expose the security posture.
    DisposableEffect(activity) {
        val window = activity?.window
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    var healthReport by remember { mutableStateOf<ZeaProtectionHealthReport?>(null) }
    var checkReport by remember { mutableStateOf<ZeaSystemCheckReport?>(null) }
    var checksRunning by remember { mutableStateOf(false) }
    var recoveryUnlocked by remember { mutableStateOf(false) }
    var runningRecoveryAction by remember { mutableStateOf<ZeaRecoveryAction?>(null) }
    var confirmAction by remember { mutableStateOf<ZeaRecoveryAction?>(null) }
    var resultMessage by remember { mutableStateOf("") }
    var resultIsError by remember { mutableStateOf(false) }

    fun refreshHealth() {
        scope.launch {
            healthReport = ZeaProtectionHealth.evaluate(context)
        }
    }

    fun runChecks() {
        if (checksRunning) return
        checksRunning = true
        scope.launch {
            checkReport = ZeaSystemCheck.run(context)
            checksRunning = false
            refreshHealth()
        }
    }

    fun runRecovery(action: ZeaRecoveryAction) {
        if (runningRecoveryAction != null) return
        runningRecoveryAction = action
        resultMessage = ""
        scope.launch {
            val outcome = ZeaEmergencyRecovery.execute(context, action)
            resultIsError = !outcome.success
            resultMessage = outcome.message
            runningRecoveryAction = null
            // Recovery can change the posture; re-verify automatically.
            checkReport = ZeaSystemCheck.run(context)
            refreshHealth()
        }
    }

    LaunchedEffect(Unit) {
        refreshHealth()
        runChecks()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = ZeaIcons.Back,
                        contentDescription = "Back"
                    )
                }
                Text(
                    text = "Diagnostics & Recovery",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            ZeaHealthSummarySection(report = healthReport)

            Spacer(modifier = Modifier.height(18.dp))

            if (resultMessage.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (resultIsError) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = resultMessage,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            color = if (resultIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { resultMessage = "" }) {
                            Icon(
                                imageVector = ZeaIcons.Cancel,
                                contentDescription = "Dismiss"
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            ZeaSystemCheckSection(
                report = checkReport,
                running = checksRunning,
                onRun = { runChecks() },
                onRepair = { result ->
                    when (result.repair) {
                        ZeaRepairAction.OPEN_USAGE_ACCESS_SETTINGS ->
                            context.startActivity(
                                ZeaDeviceOwnerController.createUsageAccessSettingsIntent(context)
                            )
                        ZeaRepairAction.OPEN_ACCESSIBILITY_SETTINGS ->
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        ZeaRepairAction.OPEN_NOTIFICATION_SETTINGS ->
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        ZeaRepairAction.OPEN_EXACT_ALARM_SETTINGS ->
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        else -> {
                            scope.launch {
                                val outcome = ZeaSystemCheck.repair(context, result.repair)
                                resultIsError = !outcome.success
                                resultMessage = outcome.message
                                checkReport = ZeaSystemCheck.run(context)
                                refreshHealth()
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(18.dp))

            if (!recoveryUnlocked) {
                ZeaRecoveryGateSection(onUnlocked = { recoveryUnlocked = true })
            } else {
                ZeaRecoveryActionList(
                    runningAction = runningRecoveryAction,
                    onActionClick = { action -> confirmAction = action }
                )
            }
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(text = action.title) },
            text = {
                val explanation = if (action.destructive) {
                    "${action.description}\n\nThis is a destructive recovery action. Continue?"
                } else {
                    "${action.description}\n\nConfirm to run this recovery action."
                }
                Text(
                    text = explanation,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAction = null
                    runRecovery(action)
                }) {
                    Text(
                        text = "Continue",
                        color = if (action.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

@Composable
private fun ZeaHealthSummarySection(report: ZeaProtectionHealthReport?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ZeaIcons.Health,
                    contentDescription = null,
                    tint = if (report?.healthy != false) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Protection Health",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (report == null) {
                Text(
                    text = "Evaluating protection state…",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            } else {
                Text(
                    text = "Protected apps: ${report.protectedCount}  •  Timed hidden: ${report.timedCount}" +
                            (if (report.protectionPaused) "  •  PROTECTION PAUSED" else ""),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (report.healthy) {
                        "No protection issues detected."
                    } else {
                        "${report.issueCount} issue(s) detected: ${report.firstIssue?.title.orEmpty()}"
                    },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (report.healthy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

@Composable
private fun ZeaSystemCheckSection(
    report: ZeaSystemCheckReport?,
    running: Boolean,
    onRun: () -> Unit,
    onRepair: (ZeaSystemCheckResult) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "System Check",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (report == null) {
                        "13 structured checks of the protection pipeline."
                    } else {
                        "${report.passedCount} passed • ${report.failedCount} failed"
                    },
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Button(onClick = onRun, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = if (running) "Running" else "Run")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        report?.results?.forEach { result ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (result.status) {
                            ZeaCheckStatus.PASS -> ZeaIcons.Confirm
                            ZeaCheckStatus.FAIL -> ZeaIcons.Warning
                            ZeaCheckStatus.NOT_APPLICABLE -> ZeaIcons.Cancel
                        },
                        contentDescription = null,
                        tint = when (result.status) {
                            ZeaCheckStatus.PASS -> MaterialTheme.colorScheme.primary
                            ZeaCheckStatus.FAIL -> MaterialTheme.colorScheme.error
                            ZeaCheckStatus.NOT_APPLICABLE ->
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = result.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = result.detail,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (result.status == ZeaCheckStatus.FAIL &&
                        result.repair != ZeaRepairAction.NONE
                    ) {
                        TextButton(onClick = { onRepair(result) }) {
                            Text(text = "Repair", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZeaRecoveryGateSection(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var gateError by remember { mutableStateOf("") }
    var pinAttemptVersion by remember { mutableStateOf(0) }
    val lockout = rememberZeaPinLockout(context, pinAttemptVersion)
    val effectiveGateError = if (lockout.lockedOut) lockout.message else gateError

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Emergency Recovery",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "PIN required. Recovery tools can unhide or unprotect apps, so they never open without your Zyro PIN.",
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(10.dp))
    }

    ZeaPinEntryScreen(
        title = "Unlock Emergency Recovery",
        subtitle = "Verify your Zyro PIN to continue.",
        buttonLabel = "Unlock",
        showBackButton = false,
        errorText = effectiveGateError,
        currentPage = null,
        totalPages = 1,
        onBack = { gateError = "" },
        onSubmit = { enteredPin ->
            if (lockout.lockedOut) {
                gateError = lockout.message
            } else if (verifyAdminPin(context, enteredPin)) {
                ZeaPinLockout.recordSuccess(context)
                gateError = ""
                onUnlocked()
            } else {
                ZeaPinLockout.recordFailure(context)
                pinAttemptVersion++
                gateError = if (ZeaPinLockout.isLockedOut(context)) {
                    ZeaPinLockout.cooldownMessage(context)
                } else {
                    "Incorrect PIN. Please try again."
                }
            }
        },
        submitEnabled = !lockout.lockedOut
    )
}

@Composable
private fun ZeaRecoveryActionList(
    runningAction: ZeaRecoveryAction?,
    onActionClick: (ZeaRecoveryAction) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Emergency Recovery",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Safe recovery actions. Destructive actions ask for confirmation first.",
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(10.dp))

        ZeaRecoveryAction.entries.forEach { action ->
            val running = runningAction == action
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (action.destructive) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.06f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = action.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = action.description,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(
                            onClick = { onActionClick(action) },
                            enabled = runningAction == null
                        ) {
                            Text(text = "Run", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 2 (P1) - Protection Health dashboard card for the Home screen.
 * Shows live protected/timed counts, a healthy/warning headline, the first
 * detected issue, and a Fix Now shortcut that opens the matching system
 * settings page (or the full diagnostics screen when no direct fix exists).
 * Re-evaluates on every lifecycle resume, so revoking a permission in
 * Settings and coming back flips the card without a restart.
 */
@Composable
fun ZeaProtectionHealthCard(
    onOpenDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var report by remember { mutableStateOf<ZeaProtectionHealthReport?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    report = ZeaProtectionHealth.evaluate(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        scope.launch {
            report = ZeaProtectionHealth.evaluate(context)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val current = report ?: return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (current.healthy) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        },
        onClick = onOpenDiagnostics
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ZeaIcons.Health,
                    contentDescription = null,
                    tint = if (current.healthy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Protection Health",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (current.healthy) "Healthy" else "${current.issueCount} issue(s)",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (current.healthy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Protected: ${current.protectedCount}  •  Timed hidden: ${current.timedCount}" +
                        (if (current.protectionPaused) "  •  PAUSED" else ""),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )

            val issue = current.firstIssue
            if (issue != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${issue.title}: ${issue.detail}",
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = {
                        val fixIntent = ZeaProtectionHealth.buildFixIntent(context, issue)
                        if (fixIntent != null) {
                            context.startActivity(fixIntent)
                        } else {
                            onOpenDiagnostics()
                        }
                    }) {
                        Text(text = "Fix Now", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
