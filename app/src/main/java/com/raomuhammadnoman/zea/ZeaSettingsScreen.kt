package com.raomuhammadnoman.zea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

/**
 * Zyro Settings. Opens with the same style of search bar as All Apps /
 * Hidden Apps and hosts security-related option categories.
 *
 * Security & Privacy -> App Security & Unlock offers:
 *  1. Change PIN            (current PIN -> new PIN -> confirm new PIN)
 *  2. Enable Fingerprint    (PIN-gated; uses the device's ALREADY-enrolled
 *                            Android biometrics via BiometricPrompt - Zyro
 *                            never enrolls anything itself)
 *  3. Disable Security      (PIN-gated + explicit confirmation; turns off
 *                            every user-facing Zyro authentication lock)
 *
 * All switches persist through app restarts and device reboots via
 * ZeaSecurityState, the single central source for lock behavior. The
 * Developer Controls key is completely independent of this screen.
 */
private enum class ZeaSettingsPinFlow {
    NONE,
    CURRENT_PIN,
    NEW_PIN,
    CONFIRM_NEW_PIN,
    PIN_UPDATED
}

private enum class ZeaSecurityPendingAction {
    NONE,
    ENABLE_FINGERPRINT,
    DISABLE_SECURITY
}

@Composable
fun ZeaSettingsScreen(onBack: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity
    val context = LocalContext.current

    // Change-PIN / gated-action flow state machine.
    var pinFlow by remember { mutableStateOf(ZeaSettingsPinFlow.NONE) }

    // Screenshot protection: this screen exposes the security posture. The
    // PIN-flow overlays render a white canvas, so status-bar glyphs flip to
    // dark while one is showing.
    DisposableEffect(activity, pinFlow) {
        val window = activity?.window
        val whiteCanvas = pinFlow != ZeaSettingsPinFlow.NONE
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        val insetsController = window?.decorView?.let {
            androidx.core.view.WindowCompat.getInsetsController(window, it)
        }
        insetsController?.isAppearanceLightStatusBars = whiteCanvas
        onDispose {
            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
            insetsController?.isAppearanceLightStatusBars = false
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var showSecurityDetail by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    var pendingAction by remember { mutableStateOf(ZeaSecurityPendingAction.NONE) }
    var pinFlowError by remember { mutableStateOf("") }
    var pendingNewPin by remember { mutableStateOf("") }

    // Disable-security confirmation dialog.
    var showDisableConfirmDialog by remember { mutableStateOf(false) }

    // Transient result banners inside the detail view.
    var infoBannerText by remember { mutableStateOf("") }
    var infoBannerIsError by remember { mutableStateOf(false) }

    fun closeAllInternal() {
        pinFlow = ZeaSettingsPinFlow.NONE
        pendingAction = ZeaSecurityPendingAction.NONE
        pinFlowError = ""
        pendingNewPin = ""
        showDisableConfirmDialog = false
        showSecurityDetail = false
        infoBannerText = ""
        infoBannerIsError = false
    }

    BackHandler(enabled = true) {
        when {
            pinFlow != ZeaSettingsPinFlow.NONE && pinFlow != ZeaSettingsPinFlow.PIN_UPDATED -> {
                pinFlow = ZeaSettingsPinFlow.NONE
                pendingAction = ZeaSecurityPendingAction.NONE
                pinFlowError = ""
            }
            showDisableConfirmDialog -> showDisableConfirmDialog = false
            showSecurityDetail -> {
                showSecurityDetail = false
                infoBannerText = ""
                infoBannerIsError = false
            }
            else -> onBack()
        }
    }

    // Full-screen PIN flow overlays (Change PIN steps and the PIN check that
    // gates fingerprint enable / security disable).
    when (pinFlow) {
        ZeaSettingsPinFlow.CURRENT_PIN -> {
            var pinAttemptVersion by remember { mutableStateOf(0) }
            val lockout = rememberZeaPinLockout(context, pinAttemptVersion)
            val effectivePinFlowError = if (lockout.lockedOut) lockout.message else pinFlowError
            ZeaPinEntryScreen(
                title = "Enter your current Zyro PIN",
                subtitle = "Verify your identity to continue.",
                buttonLabel = "Continue",
                showBackButton = true,
                errorText = effectivePinFlowError,
                currentPage = null,
                totalPages = 1,
                onBack = {
                    pinFlow = ZeaSettingsPinFlow.NONE
                    pendingAction = ZeaSecurityPendingAction.NONE
                    pinFlowError = ""
                },
                onSubmit = { enteredPin ->
                    if (lockout.lockedOut) {
                        pinFlowError = lockout.message
                    } else if (verifyAdminPin(context, enteredPin)) {
                        ZeaPinLockout.recordSuccess(context)
                        pinFlowError = ""
                        when (pendingAction) {
                            ZeaSecurityPendingAction.ENABLE_FINGERPRINT -> {
                                if (ZeaSecurityState.isBiometricEnrolled(context)) {
                                    ZeaSecurityState.setFingerprintUnlockEnabled(context, true)
                                    infoBannerText =
                                        "Fingerprint unlock is enabled. You can now use your device fingerprint as an alternative to your Zyro PIN."
                                    infoBannerIsError = false
                                } else {
                                    infoBannerText =
                                        "No fingerprint is set up on this device. Please add a fingerprint in your device settings before enabling fingerprint unlock for Zyro."
                                    infoBannerIsError = true
                                }
                                pendingAction = ZeaSecurityPendingAction.NONE
                                pinFlow = ZeaSettingsPinFlow.NONE
                            }

                            ZeaSecurityPendingAction.DISABLE_SECURITY -> {
                                pendingAction = ZeaSecurityPendingAction.NONE
                                pinFlow = ZeaSettingsPinFlow.NONE
                                showDisableConfirmDialog = true
                            }

                            ZeaSecurityPendingAction.NONE -> {
                                pinFlowError = ""
                                pinFlow = ZeaSettingsPinFlow.NEW_PIN
                            }
                        }
                    } else {
                        ZeaPinLockout.recordFailure(context)
                        pinAttemptVersion++
                        pinFlowError = if (ZeaPinLockout.isLockedOut(context)) {
                            ZeaPinLockout.cooldownMessage(context)
                        } else {
                            "Incorrect PIN. Please try again."
                        }
                    }
                },
                submitEnabled = !lockout.lockedOut
            )
            return
        }

        ZeaSettingsPinFlow.NEW_PIN -> {
            ZeaPinEntryScreen(
                title = "Create your new Zyro PIN",
                subtitle = "Choose a $ZYRO_PIN_MIN_LENGTH-$ZYRO_PIN_MAX_LENGTH digit PIN. It replaces your old PIN everywhere in Zyro.",
                buttonLabel = "Continue",
                showBackButton = true,
                errorText = pinFlowError,
                currentPage = null,
                totalPages = 1,
                onBack = {
                    pendingNewPin = ""
                    pinFlowError = ""
                    pinFlow = ZeaSettingsPinFlow.CURRENT_PIN
                },
                onSubmit = { enteredPin ->
                    pendingNewPin = enteredPin
                    pinFlowError = ""
                    pinFlow = ZeaSettingsPinFlow.CONFIRM_NEW_PIN
                }
            )
            return
        }

        ZeaSettingsPinFlow.CONFIRM_NEW_PIN -> {
            ZeaPinEntryScreen(
                title = "Confirm your new PIN",
                subtitle = "Enter the same new PIN once more to confirm.",
                buttonLabel = "Save PIN",
                showBackButton = true,
                errorText = pinFlowError,
                currentPage = null,
                totalPages = 1,
                onBack = {
                    pendingNewPin = ""
                    pinFlowError = ""
                    pinFlow = ZeaSettingsPinFlow.NEW_PIN
                },
                onSubmit = { enteredPin ->
                    if (enteredPin == pendingNewPin && saveAdminPin(context, enteredPin)) {
                        pendingNewPin = ""
                        pinFlowError = ""
                        pinFlow = ZeaSettingsPinFlow.PIN_UPDATED
                    } else if (enteredPin != pendingNewPin) {
                        pendingNewPin = ""
                        pinFlowError = "The PINs did not match. Please start again."
                        pinFlow = ZeaSettingsPinFlow.NEW_PIN
                    } else {
                        pinFlowError = "The PIN could not be saved. Please try again."
                    }
                }
            )
            return
        }

        ZeaSettingsPinFlow.PIN_UPDATED -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFE8F5E9).copy(alpha = 0.18f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "PIN Updated Successfully",
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF81C784)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Your new PIN is active immediately for every Zyro lock.",
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(onClick = {
                    pinFlow = ZeaSettingsPinFlow.NONE
                    pendingAction = ZeaSecurityPendingAction.NONE
                }) {
                    Text(text = "Done")
                }
            }
            return
        }

        ZeaSettingsPinFlow.NONE -> Unit
    }

    if (showDiagnostics) {
        ZeaDiagnosticsScreen(onBack = { showDiagnostics = false })
        return
    }

    val query = searchQuery.trim()
    val matchesSecurityCategory = query.isEmpty() ||
            "security & privacy".contains(query, ignoreCase = true) ||
            "security".contains(query, ignoreCase = true) ||
            "privacy".contains(query, ignoreCase = true)
    val matchesAppSecurityCard = query.isEmpty() || listOf(
        "app security & unlock",
        "app security",
        "security",
        "unlock",
        "change pin",
        "pin",
        "fingerprint",
        "disable security",
        "auto-lock"
    ).any { it.contains(query, ignoreCase = true) }
    val matchesDiagnosticsCard = query.isEmpty() || listOf(
        "diagnostics & recovery",
        "diagnostics",
        "recovery",
        "system check",
        "emergency",
        "health"
    ).any { it.contains(query, ignoreCase = true) }

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
                IconButton(
                    onClick = {
                        if (showSecurityDetail) {
                            showSecurityDetail = false
                            infoBannerText = ""
                            infoBannerIsError = false
                        } else {
                            onBack()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                Text(
                    text = if (showSecurityDetail) "App Security & Unlock" else "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!showSecurityDetail) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { value -> searchQuery = value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                    placeholder = { Text("Search settings...") },
                    leadingIcon = {
                        Icon(
                            imageVector = ZeaIcons.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = ZeaIcons.Cancel,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp)
                )
            }

            if (showSecurityDetail) {
                ZeaAppSecurityUnlockSection(
                    infoBannerText = infoBannerText,
                    infoBannerIsError = infoBannerIsError,
                    onBannerShown = { infoBannerText = "" },
                    onChangePinClick = {
                        pendingAction = ZeaSecurityPendingAction.NONE
                        pinFlowError = ""
                        pinFlow = ZeaSettingsPinFlow.CURRENT_PIN
                    },
                    onEnableFingerprintRequested = {
                        pendingAction = ZeaSecurityPendingAction.ENABLE_FINGERPRINT
                        pinFlowError = ""
                        pinFlow = ZeaSettingsPinFlow.CURRENT_PIN
                    },
                    onFingerprintSwitchOff = {
                        ZeaSecurityState.setFingerprintUnlockEnabled(context, false)
                        infoBannerText = "Fingerprint unlock disabled."
                        infoBannerIsError = false
                    },
                    onDisableSecurityClick = {
                        pendingAction = ZeaSecurityPendingAction.DISABLE_SECURITY
                        pinFlowError = ""
                        pinFlow = ZeaSettingsPinFlow.CURRENT_PIN
                    },
                    onEnableSecurityClick = {
                        ZeaSecurityState.setSecurityEnabled(context, true)
                        infoBannerText = "Security enabled."
                        infoBannerIsError = false
                    }
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Security & Privacy",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 22.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (matchesSecurityCategory && matchesAppSecurityCard) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { showSecurityDetail = true },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = ZeaIcons.Protection,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "App Security & Unlock",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "Change PIN, fingerprint unlock, disable security",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }

                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }
                }

                if (matchesDiagnosticsCard) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { showDiagnostics = true },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = ZeaIcons.Diagnostics,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Diagnostics & Recovery",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "System Check, protection health, emergency recovery",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }

                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }
                }

                if (!matchesSecurityCategory || (!matchesAppSecurityCard && !matchesDiagnosticsCard)) {
                    Spacer(modifier = Modifier.height(26.dp))
                    Text(
                        text = "No settings found for \"$query\"",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 22.dp)
                    )
                }
            }
        }
    }

    if (showDisableConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDisableConfirmDialog = false },
            title = { Text(text = "Disable Security?") },
            text = {
                Text(
                    text = "Turning off security makes every protected area accessible without authentication: the global launch lock, Hidden Apps, Timed Hidden Apps, private sections, and fingerprint unlock. Security stays off until you enable it again.",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableConfirmDialog = false
                        ZeaSecurityState.setSecurityEnabled(context, false)
                        infoBannerText =
                            "Security is disabled. Protected areas are accessible without authentication until you enable it again."
                        infoBannerIsError = true
                    }
                ) {
                    Text(text = "Turn Off")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirmDialog = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

/**
 * Detail section behind "App Security & Unlock": Change PIN, Enable
 * Fingerprint, and Disable/Enable Security.
 */
@Composable
private fun ZeaAppSecurityUnlockSection(
    infoBannerText: String,
    infoBannerIsError: Boolean,
    onBannerShown: () -> Unit,
    onChangePinClick: () -> Unit,
    onEnableFingerprintRequested: () -> Unit,
    onFingerprintSwitchOff: () -> Unit,
    onDisableSecurityClick: () -> Unit,
    onEnableSecurityClick: () -> Unit
) {
    val context = LocalContext.current

    if (infoBannerText.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (infoBannerIsError) {
                Color(0xFFFFEBEE).copy(alpha = 0.25f)
            } else {
                Color(0xFFE8F5E9).copy(alpha = 0.20f)
            }
        ) {
            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(
                    text = infoBannerText,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = if (infoBannerIsError) Color(0xFFEF9A9A) else Color(0xFFA5D6A7),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onBannerShown) {
                    Text(text = "OK")
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    ZeaSettingsRowCard(
        icon = ZeaIcons.PrivateApps,
        title = "Change PIN",
        subtitle = "Replace your current Zyro PIN with a new one",
        onClick = onChangePinClick
    )

    Spacer(modifier = Modifier.height(10.dp))

    var showAutoLockDialog by remember { mutableStateOf(false) }
    ZeaSettingsRowCard(
        icon = ZeaIcons.Timer,
        title = "Auto-Lock",
        subtitle = "Locks ${ZeaAutoLock.option.label.lowercase()} — tap to change when Zyro asks for your PIN again",
        onClick = { showAutoLockDialog = true }
    )

    if (showAutoLockDialog) {
        AlertDialog(
            onDismissRequest = { showAutoLockDialog = false },
            title = { Text(text = "Auto-Lock") },
            text = {
                Column {
                    Text(
                        text = "Choose when Zyro locks itself and asks for your PIN again.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ZeaAutoLockOption.entries.forEach { autoLockOption ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ZeaAutoLock.setOption(context, autoLockOption)
                                    showAutoLockDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = ZeaAutoLock.option == autoLockOption,
                                onClick = {
                                    ZeaAutoLock.setOption(context, autoLockOption)
                                    showAutoLockDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = autoLockOption.label,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = autoLockOption.description,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoLockDialog = false }) {
                    Text(text = "Close")
                }
            }
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = ZeaIcons.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable Fingerprint",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (ZeaSecurityState.securityEnabled) {
                        "Use your enrolled device fingerprint instead of your PIN"
                    } else {
                        "Unavailable while security is disabled"
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = ZeaSecurityState.fingerprintUnlockEnabled &&
                        ZeaSecurityState.securityEnabled,
                enabled = ZeaSecurityState.securityEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        onEnableFingerprintRequested()
                    } else {
                        onFingerprintSwitchOff()
                    }
                }
            )
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    if (ZeaSecurityState.securityEnabled) {
        ZeaSettingsRowCard(
            icon = ZeaIcons.Protection,
            title = "Disable Security",
            subtitle = "Turn off all Zyro authentication locks",
            titleColor = MaterialTheme.colorScheme.error,
            onClick = onDisableSecurityClick
        )
    } else {
        ZeaSettingsRowCard(
            icon = ZeaIcons.Protection,
            title = "Enable Security",
            subtitle = "Turn all Zyro authentication locks back on",
            onClick = onEnableSecurityClick
        )
    }

    if (!ZeaSecurityState.securityEnabled) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Security is currently disabled. Every protected area opens without authentication.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 22.dp)
        )
    }
}

@Composable
private fun ZeaSettingsRowCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}
