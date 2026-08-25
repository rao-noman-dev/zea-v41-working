package com.raomuhammadnoman.zea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Screens of the Apps management section.
 *
 * These render inside the existing Compose tree instead of separate activities.
 * MainActivity is declared singleTask so its recents slide survives app and
 * private launches, letting the assistant and a launched app be used side by
 * side from the recents screen.
 */
enum class ZeaAppsRoute {
    HUB,
    ALL_APPS,
    HIDDEN_APPS,
    TIMED_HIDDEN_APPS
}

/**
 * Resolves the persisted route name. A blank or unknown name means the Apps
 * section is closed and the home screen is showing.
 */
fun zeaAppsRouteOf(savedName: String): ZeaAppsRoute? =
    ZeaAppsRoute.entries.firstOrNull { route -> route.name == savedName }

/** A null parent means backing out of this route returns to the home screen. */
fun zeaAppsParentOf(route: ZeaAppsRoute): ZeaAppsRoute? = when (route) {
    ZeaAppsRoute.HUB -> null
    ZeaAppsRoute.ALL_APPS,
    ZeaAppsRoute.HIDDEN_APPS,
    ZeaAppsRoute.TIMED_HIDDEN_APPS -> ZeaAppsRoute.HUB
}

@Composable
fun ZeaAppsNavigationHost(
    route: ZeaAppsRoute,
    onNavigate: (ZeaAppsRoute) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    when (route) {
        ZeaAppsRoute.HUB -> ZeaAppsHubScreen(onNavigate = onNavigate)
        ZeaAppsRoute.ALL_APPS -> ZeaAllAppsScreen(
            onBack = onBack,
            onNavigate = onNavigate
        )
        ZeaAppsRoute.HIDDEN_APPS -> ZeaLockedAppsGate(
            sectionTitle = "Hidden Apps",
            sectionIcon = ZeaIcons.Hidden
        ) {
            ZeaHiddenAppsScreen(
                onBack = onBack,
                onNavigate = onNavigate
            )
        }
        ZeaAppsRoute.TIMED_HIDDEN_APPS -> ZeaLockedAppsGate(
            sectionTitle = "Timed Hidden Apps",
            sectionIcon = ZeaIcons.Timed
        ) {
            ZeaTimedHiddenAppsScreen(
                onBack = onBack,
                onNavigate = onNavigate
            )
        }
    }
}

/**
 * Identity gate for the hidden-app sections. The list content only renders
 * after the user enters the correct Zyro app PIN, and the gate re-arms every
 * time the activity stops, so returning from the launcher, the recents
 * screen, or another app always asks for verification again.
 *
 * Verification uses the locally stored Zyro PIN, with the device's
 * already-enrolled biometrics available as an alternative ONLY when the user
 * enabled fingerprint unlock in Settings. The master security switch in
 * Settings disables this gate entirely while it is off.
 */
@Composable
private fun ZeaLockedAppsGate(
    sectionTitle: String,
    sectionIcon: ImageVector,
    content: @Composable () -> Unit
) {
    val activity = LocalContext.current as? FragmentActivity

    // Deliberately NOT saveable: a saved "unlocked" flag would restore the
    // protected list without any verification after process death, because
    // force-stop never delivers ON_STOP to re-arm the gate.
    var unlocked by remember { mutableStateOf(false) }

    // The hidden list must never appear inside a recents slide preview or a
    // user screenshot. FLAG_SECURE blanks the overview thumbnail for this
    // screen and blocks screen capture while any gated state is showing.
    DisposableEffect(activity, unlocked, ZeaSecurityState.securityEnabled) {
        val window = activity?.window
        val gateVisible = !unlocked && ZeaSecurityState.securityEnabled
        if (gateVisible) {
            window?.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        // Dark status-bar glyphs over the white keypad canvas.
        val insetsController = window?.decorView?.let {
            androidx.core.view.WindowCompat.getInsetsController(window, it)
        }
        insetsController?.isAppearanceLightStatusBars = gateVisible
        onDispose {
            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
            insetsController?.isAppearanceLightStatusBars = false
        }
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                unlocked = false
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    // Error text for the locked view. Kept as Compose state so wrong-PIN
    // errors re-render immediately; the PIN input itself lives inside the
    // shared keypad screen and resets with it.
    var sectionPinError by remember { mutableStateOf("") }

    if (unlocked || !ZeaSecurityState.securityEnabled) {
        // Master security switch OFF: this section opens without any
        // authentication until the user re-enables security.
        content()
        return
    }

    val gateContext = LocalContext.current

    var pinAttemptVersion by remember { mutableStateOf(0) }
    val lockout = rememberZeaPinLockout(gateContext, pinAttemptVersion)
    val effectiveSectionError = if (lockout.lockedOut) lockout.message else sectionPinError

    // Same in-app keypad view as the global launch lock - no system keyboard.
    ZeaPinEntryScreen(
        title = "$sectionTitle is locked",
        subtitle = "Enter your Zyro PIN to view this list.",
        buttonLabel = "Unlock",
        showBackButton = false,
        errorText = effectiveSectionError,
        currentPage = null,
        totalPages = 1,
        fingerprintEnabled = ZeaSecurityState.fingerprintUnlockEnabled &&
                ZeaSecurityState.securityEnabled,
        onFingerprintAuthenticated = {
            ZeaPinLockout.recordSuccess(gateContext)
            unlocked = true
            sectionPinError = ""
        },
        onBack = { sectionPinError = "" },
        onSubmit = { enteredPin ->
            if (lockout.lockedOut) {
                sectionPinError = lockout.message
            } else if (verifyAdminPin(gateContext, enteredPin)) {
                ZeaPinLockout.recordSuccess(gateContext)
                unlocked = true
                sectionPinError = ""
            } else {
                ZeaPinLockout.recordFailure(gateContext)
                pinAttemptVersion++
                sectionPinError = if (ZeaPinLockout.isLockedOut(gateContext)) {
                    ZeaPinLockout.cooldownMessage(gateContext)
                } else {
                    "Incorrect PIN. Please try again."
                }
            }
        },
        submitEnabled = !lockout.lockedOut
    )
}

/**
 * A home menu entry whose destination is not built yet. It stays visible so the
 * menu reads as intended, but is disabled rather than opening an empty screen.
 */
@Composable
fun ZeaHomeMenuPlaceholder(
    label: String,
    icon: ImageVector
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        },
        onClick = {},
        enabled = false
    )
}
