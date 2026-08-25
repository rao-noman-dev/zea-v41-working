package com.raomuhammadnoman.zea

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

// TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
object ZeaTemporaryAppLockConfig {
    const val ENABLED = true

    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
    const val PROMPT_TITLE = "Unlock Zyro"

    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
    const val LOCKED_TITLE = "Zyro is locked"
}

internal const val ZYRO_PIN_MIN_LENGTH = 4
internal const val ZYRO_PIN_MAX_LENGTH = 8

/**
 * Light security palette shared by every gate/onboarding screen so the
 * first-run experience feels like one polished product. Pure-white canvas;
 * every former light-on-dark color has a same-hue dark counterpart so the
 * layout, buttons, icons, and text placement stay exactly as designed.
 */
internal object ZeaGateUi {
    val AccentStart = Color(0xFF22D3EE)
    val AccentEnd = Color(0xFF6366F1)
    val TextPrimary = Color(0xFF0B1220)
    val TextMuted = Color(0x990B1220)
    val GlassFill = Color(0x120B1220)
    val GlassBorder = Color(0x1F0B1220)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFCD34D)

    val Gradient = Brush.linearGradient(listOf(AccentStart, AccentEnd))
}

/** Minimal vector glyphs drawn on canvas so no extra icon dependency is needed. */
internal enum class ZeaGateGlyph { SHIELD, PEOPLE, EYE, LOCK, BELL, BOLT }

internal fun DrawScope.zyroDrawGateGlyph(glyph: ZeaGateGlyph, stroke: Brush) {
    val w = size.width
    val h = size.height
    val sw = w.coerceAtMost(h)
    val style = Stroke(width = sw * 0.075f, cap = StrokeCap.Round)
    fun x(f: Float) = w * f
    fun y(f: Float) = h * f
    val path: Path = when (glyph) {
        ZeaGateGlyph.SHIELD -> Path().apply {
            moveTo(x(0.5f), y(0.06f))
            cubicTo(x(0.72f), y(0.13f), x(0.92f), y(0.17f), x(0.92f), y(0.17f))
            lineTo(x(0.92f), y(0.48f))
            cubicTo(x(0.92f), y(0.72f), x(0.76f), y(0.87f), x(0.5f), y(0.95f))
            cubicTo(x(0.24f), y(0.87f), x(0.08f), y(0.72f), x(0.08f), y(0.48f))
            lineTo(x(0.08f), y(0.17f))
            cubicTo(x(0.08f), y(0.17f), x(0.28f), y(0.13f), x(0.5f), y(0.06f))
            close()
        }
        ZeaGateGlyph.PEOPLE -> Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(x(0.28f), y(0.10f), x(0.62f), y(0.44f)))
            moveTo(x(0.12f), y(0.86f))
            cubicTo(x(0.12f), y(0.62f), x(0.30f), y(0.52f), x(0.45f), y(0.52f))
            cubicTo(x(0.60f), y(0.52f), x(0.78f), y(0.62f), x(0.78f), y(0.86f))
            addOval(androidx.compose.ui.geometry.Rect(x(0.64f), y(0.14f), x(0.90f), y(0.40f)))
            moveTo(x(0.70f), y(0.50f))
            cubicTo(x(0.88f), y(0.52f), x(0.98f), y(0.66f), x(0.98f), y(0.82f))
        }
        ZeaGateGlyph.EYE -> Path().apply {
            moveTo(x(0.05f), y(0.5f))
            cubicTo(x(0.25f), y(0.16f), x(0.75f), y(0.16f), x(0.95f), y(0.5f))
            cubicTo(x(0.75f), y(0.84f), x(0.25f), y(0.84f), x(0.05f), y(0.5f))
            close()
        }
        ZeaGateGlyph.LOCK -> Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    x(0.16f), y(0.44f), x(0.84f), y(0.94f),
                    CornerRadius(sw * 0.16f, sw * 0.16f)
                )
            )
            moveTo(x(0.30f), y(0.44f))
            lineTo(x(0.30f), y(0.32f))
            cubicTo(x(0.30f), y(0.10f), x(0.70f), y(0.10f), x(0.70f), y(0.32f))
            lineTo(x(0.70f), y(0.44f))
        }
        ZeaGateGlyph.BELL -> Path().apply {
            moveTo(x(0.22f), y(0.42f))
            cubicTo(x(0.22f), y(0.10f), x(0.78f), y(0.10f), x(0.78f), y(0.42f))
            cubicTo(x(0.78f), y(0.66f), x(0.90f), y(0.72f), x(0.90f), y(0.72f))
            lineTo(x(0.10f), y(0.72f))
            cubicTo(x(0.10f), y(0.72f), x(0.22f), y(0.66f), x(0.22f), y(0.42f))
            close()
            moveTo(x(0.42f), y(0.80f))
            cubicTo(x(0.46f), y(0.90f), x(0.54f), y(0.90f), x(0.58f), y(0.80f))
        }
        ZeaGateGlyph.BOLT -> Path().apply {
            moveTo(x(0.56f), y(0.04f))
            lineTo(x(0.16f), y(0.56f))
            lineTo(x(0.45f), y(0.56f))
            lineTo(x(0.38f), y(0.96f))
            lineTo(x(0.84f), y(0.40f))
            lineTo(x(0.55f), y(0.40f))
            close()
        }
    }
    drawPath(path, stroke, style = style)
    if (glyph == ZeaGateGlyph.EYE) {
        drawCircle(
            Color.White.copy(alpha = 0.85f),
            radius = sw * 0.09f,
            center = Offset(x(0.5f), y(0.5f))
        )
    }
    if (glyph == ZeaGateGlyph.LOCK) {
        drawCircle(Color.White.copy(alpha = 0.85f), radius = sw * 0.06f, center = Offset(x(0.5f), y(0.69f)))
    }
}

@Composable
private fun GlyphIcon(glyph: ZeaGateGlyph, modifier: Modifier, tintBrush: Brush) {
    Canvas(modifier = modifier) {
        zyroDrawGateGlyph(glyph, tintBrush)
    }
}

/** Pure-white canvas behind all gate content. */
@Composable
private fun ZeaGateScaffold(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            content()
        }
    }
}

/** "ZYRO Â· SETUP" brand row plus glowing segmented progress bar. */
@Composable
private fun ZeaGateProgress(currentPage: Int, totalPages: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "ZYRO Â· SETUP", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = ZeaGateUi.AccentStart)
        Text(text = "$currentPage / $totalPages", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = ZeaGateUi.TextMuted)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(totalPages) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(99))
                    .background(
                        if (index < currentPage) ZeaGateUi.Gradient else SolidColor(ZeaGateUi.GlassBorder)
                    )
            )
        }
    }
}

@Composable
private fun ZeaHeroTile(glyph: ZeaGateGlyph, sizeDp: Int = 104) {
    val shape = RoundedCornerShape((sizeDp * 0.3).toInt())
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(ZeaGateUi.GlassFill)
            .border(1.dp, ZeaGateUi.GlassBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(
            glyph = glyph,
            modifier = Modifier.size((sizeDp * 0.44).dp),
            tintBrush = ZeaGateUi.Gradient
        )
    }
}

@Composable
private fun ZeaGlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ZeaGateUi.GlassFill)
            .padding(vertical = 13.dp, horizontal = 15.dp)
    ) {
        content()
    }
}

@Composable
private fun ZeaFeatureRow(glyph: ZeaGateGlyph, title: String, description: String) {
    ZeaGlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(ZeaGateUi.AccentStart.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                GlyphIcon(glyph = glyph, modifier = Modifier.size(22.dp), tintBrush = ZeaGateUi.Gradient)
            }
            Spacer(modifier = Modifier.size(14.dp))
            Column {
                Text(text = title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = ZeaGateUi.TextPrimary)
                Text(text = description, fontSize = 12.sp, lineHeight = 16.sp, color = ZeaGateUi.TextMuted)
            }
        }
    }
}

@Composable
private fun ZeaPrimaryCta(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(
                if (enabled) ZeaGateUi.Gradient
                else Brush.horizontalGradient(listOf(Color(0x3322D3EE), Color(0x336366F1)))
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            color = if (enabled) Color(0xFF04121B) else ZeaGateUi.TextMuted
        )
    }
}

@Composable
private fun ZeaGhostLink(label: String, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = ZeaGateUi.TextMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(99))
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ZeaPinDots(filledCount: Int, error: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp, Alignment.CenterHorizontally)) {
        repeat(ZYRO_PIN_MAX_LENGTH) { index ->
            val filled = index < filledCount
            val shape = RoundedCornerShape(99)
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .then(
                        if (filled) {
                            Modifier
                                .clip(shape)
                                .background(
                                    if (error) {
                                        Brush.linearGradient(listOf(Color(0xFFF87171), Color(0xFFFB923C)))
                                    } else {
                                        ZeaGateUi.Gradient
                                    }
                                )
                        } else {
                            Modifier.border(2.dp, Color(0x59A0AEC1), shape)
                        }
                    )
            )
        }
    }
}

/**
 * One shared numeric-PIN screen used by every gate stage so create, confirm,
 * and unlock all look and behave identically. Uses an in-app keypad, so no
 * system keyboard is ever summoned.
 *
 * Fingerprint: when [fingerprintEnabled] is true and the caller supplies
 * [onFingerprintAuthenticated], a single optional "Use fingerprint" link
 * appears below the primary button; it opens the official Android
 * BiometricPrompt against the device's ALREADY-enrolled fingerprints as an
 * alternative to typing the PIN. Create/confirm/setup stages never enable it.
 */
@Composable
internal fun ZeaPinEntryScreen(
    title: String,
    subtitle: String,
    buttonLabel: String,
    showBackButton: Boolean,
    errorText: String,
    currentPage: Int?,
    totalPages: Int,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    fingerprintEnabled: Boolean = false,
    onFingerprintAuthenticated: (() -> Unit)? = null
) {
    var pinInput by remember(title) { mutableStateOf("") }
    var localError by remember(title) { mutableStateOf("") }

    ZeaGateScaffold {
        if (currentPage != null) {
            ZeaGateProgress(currentPage, totalPages)
        }
        Spacer(modifier = Modifier.height(if (currentPage != null) 26.dp else 40.dp))

        Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
            ZeaHeroTile(glyph = ZeaGateGlyph.SHIELD)
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = title,
            fontSize = 27.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = ZeaGateUi.TextPrimary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = subtitle,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            color = ZeaGateUi.TextMuted,
            modifier = Modifier.fillMaxWidth()
        )

        val message = if (localError.isNotBlank()) localError else errorText
        if (message.isNotBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99))
                    .background(Color(0x14F87171))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                Text(text = message, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDC2626))
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        ZeaPinDots(filledCount = pinInput.length, error = message.isNotBlank())

        Spacer(modifier = Modifier.height(18.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "del")
            )
            rows.forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    rowKeys.forEach { key ->
                        when (key) {
                            "" -> Spacer(modifier = Modifier.size(74.dp, 62.dp))
                            "del" -> Box(
                                modifier = Modifier
                                    .size(74.dp, 62.dp)
                                    .clip(RoundedCornerShape(20))
                                    .clickable {
                                        if (pinInput.isNotEmpty()) {
                                            pinInput = pinInput.dropLast(1)
                                            localError = ""
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = "Delete",
                                    tint = ZeaGateUi.TextMuted
                                )
                            }
                            else -> Box(
                                modifier = Modifier
                                    .size(74.dp, 62.dp)
                                    .clip(RoundedCornerShape(20))
                                    .background(ZeaGateUi.GlassFill)
                                    .clickable {
                                        if (pinInput.length < ZYRO_PIN_MAX_LENGTH) {
                                            pinInput += key
                                            localError = ""
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = key, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = ZeaGateUi.TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        ZeaPrimaryCta(
            label = buttonLabel,
            onClick = {
                val cleanPin = pinInput.trim()
                if (cleanPin.length < ZYRO_PIN_MIN_LENGTH) {
                    localError = "Please enter at least $ZYRO_PIN_MIN_LENGTH digits."
                    return@ZeaPrimaryCta
                }
                onSubmit(cleanPin)
            }
        )

        if (fingerprintEnabled && onFingerprintAuthenticated != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val fpActivity = LocalContext.current as? FragmentActivity
            ZeaGhostLink(label = "Use fingerprint", onClick = {
                if (fpActivity != null) {
                    ZeaSecurityState.launchFingerprintPrompt(
                        activity = fpActivity,
                        title = title,
                        onSuccess = onFingerprintAuthenticated,
                        onError = { errString -> localError = errString.toString() }
                    )
                }
            })
        }

        if (showBackButton) {
            Spacer(modifier = Modifier.height(6.dp))
            ZeaGhostLink(label = "Back", onClick = onBack)
        } else {
            Spacer(modifier = Modifier.height(14.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

/**
 * Persistent onboarding bookkeeping. Two things live here:
 * 1. The overall completion flag for the one-time permissions walkthrough.
 * 2. The exact gate stage ("confirm PIN" / "permissions pages") so a process
 *    death or an activity recreation - e.g. returning from the Accessibility
 *    settings screen - resumes exactly where the user left off instead of
 *    bouncing them back to the unlock screen mid-setup.
 *
 * Everything is stored in a small dedicated preference file so the
 * authentication storage contract stays untouched.
 */
internal object ZeaOnboardingState {
    private const val PREFS_NAME = "zyro_onboarding_state"
    private const val KEY_PERMISSIONS_DONE = "permissions_onboarding_completed"
    private const val KEY_GATE_STAGE = "gate_stage"
    private const val KEY_ACK_PREFIX = "ack_step_"
    private const val KEY_SELECTED_MODE = "selected_mode"
    private const val KEY_A11Y_TRIP_PENDING = "a11y_trip_pending"
    private const val KEY_A11Y_TRIP_AT = "a11y_trip_at"

    internal const val STAGE_CONFIRM_PIN = "confirm_pin"
    internal const val STAGE_MODE_SELECTION = "mode_selection"
    internal const val STAGE_ONBOARDING_PERMISSIONS = "onboarding_permissions"

    internal const val MODE_DEVICE_OWNER = "device_owner"
    internal const val MODE_STANDARD = "standard"

    fun isPermissionsOnboardingCompleted(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PERMISSIONS_DONE, false)
        } catch (_: RuntimeException) {
            true
        }
    }

    fun markPermissionsOnboardingCompleted(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PERMISSIONS_DONE, true)
                .commit()
        } catch (_: RuntimeException) {
            // Failing to persist only repeats onboarding on next launch.
        }
    }

    /**
     * Last persisted interactive stage, or null when the gate should derive
     * its stage from the live PIN state instead.
     */
    fun readGateStage(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_GATE_STAGE, null)
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Write-through checkpoint for the gate stage. Passing null clears the
     * checkpoint (setup finished, or fell back to a derivable stage).
     */
    fun saveGateStage(context: Context, stage: String?) {
        try {
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
            if (stage == null) {
                editor.remove(KEY_GATE_STAGE)
            } else {
                editor.putString(KEY_GATE_STAGE, stage)
            }
            editor.commit()
        } catch (_: RuntimeException) {
            // Losing a checkpoint only costs resume fidelity, never security.
        }
    }

    /**
     * The setup mode the user picked on the Choose Zyro Mode page, persisted
     * so a mid-setup close resumes into the same mode-specific step list.
     * Null until an explicit selection is made - never defaulted silently.
     */
    fun readSelectedMode(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_MODE, null)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun saveSelectedMode(context: Context, mode: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED_MODE, mode)
                .commit()
        } catch (_: RuntimeException) {
        }
    }

    /**
     * Whether a mandatory settings-backed page (App Lock engine / Usage
     * access) was advanced with its explicit Continue while healthy.
     * Stored PER STEP - acknowledging one page must never invalidate
     * another page's confirmation. Unlike contacts and notifications, these
     * steps advance ONLY by pressing Continue - never by permission state
     * alone - so a granted-but-unconfirmed page keeps reappearing (showing
     * its Granted status) until it is confirmed.
     */
    fun isStepAcknowledged(context: Context, step: String): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACK_PREFIX + step, false)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun markStepAcknowledged(context: Context, step: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACK_PREFIX + step, true)
                .commit()
        } catch (_: RuntimeException) {
        }
    }

    /**
     * Clears the acknowledgment of one mandatory page whenever its backing
     * capability is found disabled again, so the mandatory re-enable flow
     * always ends on the Continue press instead of silently skipping past.
     */
    fun clearStepAcknowledgment(context: Context, step: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ACK_PREFIX + step)
                .commit()
        } catch (_: RuntimeException) {
        }
    }

    /**
     * Records that Zyro is navigating the user to Accessibility settings.
     * Persisted because vivo may kill the process while Settings is in the
     * foreground - the round-trip must still be recognized on return.
     */
    fun markAccessibilityRoundTrip(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_A11Y_TRIP_PENDING, true)
                .putLong(KEY_A11Y_TRIP_AT, System.currentTimeMillis())
                .commit()
        } catch (_: RuntimeException) {
        }
    }

    fun clearAccessibilityRoundTrip(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_A11Y_TRIP_PENDING)
                .remove(KEY_A11Y_TRIP_AT)
                .commit()
        } catch (_: RuntimeException) {
        }
    }

    /**
     * Consume-on-read check for a PIN-less resume after the Open Settings
     * hop. Returns true only when a trip was pending AND it started within
     * [graceMs]; the record is always cleared either way so any later launch
     * - even seconds after an app close - demands the PIN first.
     */
    fun takeAccessibilityRoundTrip(context: Context, graceMs: Long): Boolean {
        val resumed = try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pending = prefs.getBoolean(KEY_A11Y_TRIP_PENDING, false)
            val startedAt = prefs.getLong(KEY_A11Y_TRIP_AT, 0L)
            pending && startedAt > 0L &&
                    (System.currentTimeMillis() - startedAt) in 0..graceMs
        } catch (_: RuntimeException) {
            false
        }
        clearAccessibilityRoundTrip(context)
        return resumed
    }
}

private enum class ZeaPinGateStage {
    CREATE_PIN,
    CONFIRM_PIN,
    MODE_SELECTION,
    ENTER_PIN,
    ONBOARDING_PERMISSIONS,
    UNLOCKED
}

/**
 * Process-lifetime note that Zyro itself navigated away to the Accessibility
 * settings screen (Open Settings button). Returning from THAT specific hop
 * is a continuation of the current interaction - the permissions page must
 * reappear without demanding the PIN again. Any other exit (home, recents,
 * force-stop, task removal) ends the authenticated interaction: the very
 * next icon launch shows the "Zyro is locked" PIN screen first, even if the
 * Android process happened to survive in the background cache.
 */
internal var zeaGateLeftToAccessibilitySettingsAtMs: Long? = null

/**
 * Process-lifetime record of the task that was last authenticated against
 * the global Zyro lock. The global "Zyro is locked" PIN guards a FRESH
 * launch, not a resume: while the same Android task is still alive (the
 * user went home, switched apps, or the system silently recreated the
 * activity in the background), reopening Zyro must land straight back on
 * the previous screen. When the task is genuinely gone - recents swipe,
 * back-exit, force-stop, or process death - the next launch gets a NEW
 * task id and the PIN is demanded again.
 */
internal var zeaGateSessionUnlockedTaskId: Int? = null

/** How long a settings round trip may take before it degrades to a fresh launch. */
private const val ZEA_A11Y_ROUND_TRIP_GRACE_MS = 5L * 60L * 1000L

private const val ZEA_STEP_CONTACTS = "contacts"
private const val ZEA_STEP_LOCK = "lock"
private const val ZEA_STEP_USAGE_ACCESS = "usage_access"
private const val ZEA_STEP_NOTIFICATIONS = "notifications"

/**
 * Live permission checks. The onboarding machine never trusts saved flags
 * for these - every launch/unlock re-reads the real Android state so a
 * manually revoked permission immediately routes the user back to the right
 * setup page.
 */
internal fun zyroIsContactsGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
}

internal fun zyroAreNotificationsGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return true // Runtime notification permission does not exist pre-13.
    }
    return ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

internal fun zyroIsLockEngineHealthy(context: Context): Boolean {
    return if (ZeaOnboardingState.readSelectedMode(context) ==
        ZeaOnboardingState.MODE_DEVICE_OWNER
    ) {
        // Device Owner track never demands the accessibility engine.
        true
    } else {
        ZeaLockMode.isLockServiceEnabled(context)
    }
}

/**
 * Live Usage access state (AppOps-backed special access, not a runtime
 * permission). Powers the private-session monitor that re-hides a private
 * app after it leaves the foreground.
 */
internal fun zyroIsUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * The REAL Android Device Owner status - never derived from what the user
 * picked in the UI. The Device Owner onboarding flow may only run when the
 * platform has actually provisioned Zyro; otherwise the option stays
 * unavailable and no step is ever treated as satisfied by selection alone.
 */
internal fun zyroIsDeviceOwnerProvisioned(context: Context): Boolean {
    val policyManager = context.getSystemService(
        Context.DEVICE_POLICY_SERVICE
    ) as? android.app.admin.DevicePolicyManager ?: return false
    return try {
        policyManager.isDeviceOwnerApp(context.packageName)
    } catch (_: RuntimeException) {
        false
    }
}

/**
 * Ordered onboarding pages for the SELECTED setup mode.
 *
 *  - Device Owner mode: Usage access (mandatory) + skippable contacts /
 *    notifications. The accessibility engine page does not apply here -
 *    enforcement is handled by owner-level policies instead.
 *  - Standard mode: App Lock engine (mandatory) + Usage access (mandatory)
 *    + skippable contacts / notifications.
 *
 * A missing mode selection falls back to the standard list only as a repair
 * path; fresh flows always pass through the Choose Mode page first.
 */
internal fun zyroOnboardingSteps(context: Context): List<String> {
    if (ZeaOnboardingState.readSelectedMode(context) ==
        ZeaOnboardingState.MODE_DEVICE_OWNER
    ) {
        return listOf(
            ZEA_STEP_USAGE_ACCESS,
            ZEA_STEP_CONTACTS,
            ZEA_STEP_NOTIFICATIONS
        )
    }
    // Standard track (and the no-mode repair path): the accessibility
    // engine is required regardless of whether the platform happens to
    // have Device Owner provisioned - the chosen mode drives the flow.
    return listOf(
        ZEA_STEP_LOCK,
        ZEA_STEP_USAGE_ACCESS,
        ZEA_STEP_CONTACTS,
        ZEA_STEP_NOTIFICATIONS
    )
}

/** Live satisfied/unsatisfied status of a single onboarding step. */
internal fun zyroIsStepSatisfied(context: Context, step: String): Boolean {
    return when (step) {
        ZEA_STEP_CONTACTS -> zyroIsContactsGranted(context)
        ZEA_STEP_LOCK -> zyroIsLockEngineHealthy(context)
        ZEA_STEP_USAGE_ACCESS -> zyroIsUsageAccessGranted(context)
        else -> zyroAreNotificationsGranted(context)
    }
}

/**
 * Whether an onboarding page still demands attention for ROUTING purposes.
 * Contacts and notifications depend purely on live permission state; the
 * mandatory settings-backed pages (App Lock engine, Usage access) also
 * require their explicit Continue acknowledgment, so a granted-but-
 * unconfirmed page never silently disappears.
 */
internal fun zyroIsOnboardingStepPending(context: Context, step: String): Boolean {
    return when (step) {
        ZEA_STEP_LOCK -> !(zyroIsLockEngineHealthy(context) &&
                ZeaOnboardingState.isStepAcknowledged(context, ZEA_STEP_LOCK))
        ZEA_STEP_USAGE_ACCESS -> !(zyroIsUsageAccessGranted(context) &&
                ZeaOnboardingState.isStepAcknowledged(context, ZEA_STEP_USAGE_ACCESS))
        else -> !zyroIsStepSatisfied(context, step)
    }
}

/**
 * The single source of truth for gate routing: the FIRST onboarding page that
 * still needs attention right now, or null when everything is healthy and the
 * unlock should go straight to Home.
 *
 * Because this is computed from live state on every call, it simultaneously
 * powers:
 *  - fresh-install sequencing (each granted page auto-skips forward),
 *  - mid-setup resume (close the app anywhere, next unlock lands here),
 *  - post-setup re-validation (revoked/disabled capability re-opens its own
 *    page - the lock engine is mandatory, contacts/notifications resurface
 *    as skippable reminders until they are allowed again).
 */
internal fun zyroFirstPendingOnboardingStep(context: Context): String? {
    if (!isAdminPinSet(context)) {
        // No PIN yet: the create/confirm PIN pages own the gate, not this.
        return null
    }
    return zyroOnboardingSteps(context).firstOrNull { step ->
        zyroIsOnboardingStepPending(context, step)
    }
}

/**
 * Gate-level decision: should an unlocked launch detour into onboarding?
 * A missing-but-skippable permission (contacts/notifications denied or later
 * revoked) still counts as pending so its reminder page can resurface, while
 * a fully healthy runtime heals a lost completion flag instead of forcing
 * another walkthrough.
 */
internal fun zyroNeedsPermissionsOnboarding(context: Context): Boolean {
    // A disabled capability instantly voids its earlier Continue
    // acknowledgment: the mandatory re-enable flow must end on a fresh
    // Continue press.
    if (!zyroIsLockEngineHealthy(context)) {
        ZeaOnboardingState.clearStepAcknowledgment(context, ZEA_STEP_LOCK)
    }
    if (!zyroIsUsageAccessGranted(context)) {
        ZeaOnboardingState.clearStepAcknowledgment(context, ZEA_STEP_USAGE_ACCESS)
    }

    // No mode chosen yet (fresh setup, or closed right after Save PIN): the
    // Choose Zyro Mode page owns the gate before any permission page.
    if (ZeaOnboardingState.readSelectedMode(context) == null &&
        !ZeaOnboardingState.isPermissionsOnboardingCompleted(context)
    ) {
        Log.i("ZeaGate", "onboarding-check pending=mode-selection")
        return true
    }

    val pendingStep = zyroFirstPendingOnboardingStep(context)
    val flagCompleted = ZeaOnboardingState.isPermissionsOnboardingCompleted(context)

    Log.i(
        "ZeaGate",
        "onboarding-check pending=$pendingStep flag=$flagCompleted mode=" +
                "${ZeaOnboardingState.readSelectedMode(context)} " +
                "contacts=${zyroIsContactsGranted(context)} " +
                "notif=${zyroAreNotificationsGranted(context)} " +
                "lockOk=${zyroIsLockEngineHealthy(context)} " +
                "usage=${zyroIsUsageAccessGranted(context)}"
    )

    if (pendingStep == null) {
        if (!flagCompleted) {
            ZeaOnboardingState.markPermissionsOnboardingCompleted(context)
            Log.i("ZeaGate", "onboarding-check healed missing completion flag")
        }
        ZeaOnboardingState.saveGateStage(context, null)
        return false
    }

    return true
}

@Composable
fun ZeaAppLockGate(
    activity: FragmentActivity,
    content: @Composable () -> Unit
) {
    if (!ZeaTemporaryAppLockConfig.ENABLED) {
        content()
        return
    }

    val context = LocalContext.current

    // Scratch state shared between the create and confirm steps. Kept as
    // Compose state so mismatch errors re-render immediately.
    var pendingCreatePin by remember(activity) { mutableStateOf("") }
    var gateErrorText by remember(activity) { mutableStateOf("") }

    // Entry stage is restored from the persisted checkpoint first, so an
    // activity recreation (settings round-trip, process death, config
    // change) lands the user back on the exact page they left instead of
    // bouncing them to the unlock screen mid-setup. Without a checkpoint the
    // stage derives from the live PIN state.
    var stage by remember(activity) {
        mutableStateOf(
            when (ZeaOnboardingState.readGateStage(context)) {
                ZeaOnboardingState.STAGE_CONFIRM_PIN ->
                    if (pendingCreatePin.isNotEmpty()) {
                        ZeaPinGateStage.CONFIRM_PIN
                    } else {
                        // Process death wiped the in-memory draft PIN; the
                        // confirm page is meaningless without it.
                        ZeaPinGateStage.CREATE_PIN
                    }

                ZeaOnboardingState.STAGE_MODE_SELECTION ->
                    // The mode page always sits behind a saved PIN, so the
                    // PIN-first rule applies to every fresh launch here.
                    ZeaPinGateStage.ENTER_PIN

                ZeaOnboardingState.STAGE_ONBOARDING_PERMISSIONS -> {
                    // PIN-less resume is allowed ONLY for the return leg of
                    // our own "Open Settings" hop (same process, or a
                    // process death inside Settings within the grace
                    // window). Every genuinely fresh launch - home, recents,
                    // force-stop, icon tap after an app close - lands on the
                    // unlock screen first, even mid-onboarding.
                    val sameProcessTrip = zeaGateLeftToAccessibilitySettingsAtMs != null
                    val resumedTrip = sameProcessTrip ||
                            ZeaOnboardingState.takeAccessibilityRoundTrip(
                                context,
                                ZEA_A11Y_ROUND_TRIP_GRACE_MS
                            )
                    if (sameProcessTrip) {
                        ZeaOnboardingState.clearAccessibilityRoundTrip(context)
                    }
                    zeaGateLeftToAccessibilitySettingsAtMs = null
                    if (resumedTrip) {
                        ZeaPinGateStage.ONBOARDING_PERMISSIONS
                    } else {
                        ZeaPinGateStage.ENTER_PIN
                    }
                }

                else -> {
                    // Master security switch OFF: the user disabled every
                    // user-facing authentication lock, so a PIN-set app
                    // resumes straight into content. Setup flows (create
                    // PIN / onboarding) are NOT authentication locks and
                    // still run normally.
                    if (!ZeaSecurityState.securityEnabled && isAdminPinSet(context)) {
                        ZeaPinGateStage.UNLOCKED
                    } else {
                        // Global lock guards a FRESH launch only. If this
                        // activity instance was recreated into the SAME task
                        // that already authenticated in this process (vivo
                        // silently destroying background activities is the
                        // common case), the user resumes directly instead of
                        // being re-pinned. A genuinely new launch always gets
                        // a fresh task id and therefore the PIN.
                        val resumedAliveTask = zeaGateSessionUnlockedTaskId != null &&
                                zeaGateSessionUnlockedTaskId == activity.taskId
                        if (resumedAliveTask) {
                            ZeaPinGateStage.UNLOCKED
                        } else if (isAdminPinSet(context)) {
                            ZeaPinGateStage.ENTER_PIN
                        } else {
                            ZeaPinGateStage.CREATE_PIN
                        }
                    }
                }
            }
        )
    }


    // The PIN pages no longer show a page counter: the total setup length
    // depends on the mode the user is about to choose, so a fixed N would
    // lie. Real counts resume on the mode-specific permission pages.

    // PIN entry, mode selection, and onboarding pages are sensitive: block
    // screenshots, screen recording, and the recents overview preview while
    // any gated state is on screen. Unlocking clears the flag so the normal
    // app screens remain shareable; the Hidden Apps gates re-arm it for
    // their own sections.
    DisposableEffect(activity, stage) {
        val window = activity.window
        val gateVisible = stage != ZeaPinGateStage.UNLOCKED
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
        // White gate canvas needs dark status-bar glyphs; the dark app
        // content behind UNLOCKED keeps the default light glyphs.
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

    // Shared unlock path: runs after ANY successful authentication (PIN or
    // fingerprint) so both routes behave identically, including onboarding
    // routing and the task-scoped session grant.
    fun performAuthenticatedUnlock() {
        // This task is now authenticated for its whole lifetime: background
        // resumes within it never re-ask the global PIN.
        zeaGateSessionUnlockedTaskId = activity.taskId
        // Authenticated now: any stale round-trip marker is meaningless from
        // this point on.
        ZeaOnboardingState.clearAccessibilityRoundTrip(context)
        zeaGateLeftToAccessibilitySettingsAtMs = null
        if (zyroNeedsPermissionsOnboarding(context)) {
            if (ZeaOnboardingState.readSelectedMode(context) == null &&
                !ZeaOnboardingState.isPermissionsOnboardingCompleted(context)
            ) {
                // Setup never reached a mode choice (or was closed right
                // after Save PIN): the Choose Mode page is the next
                // incomplete step.
                ZeaOnboardingState.saveGateStage(
                    context,
                    ZeaOnboardingState.STAGE_MODE_SELECTION
                )
                stage = ZeaPinGateStage.MODE_SELECTION
            } else {
                ZeaOnboardingState.saveGateStage(
                    context,
                    ZeaOnboardingState.STAGE_ONBOARDING_PERMISSIONS
                )
                stage = ZeaPinGateStage.ONBOARDING_PERMISSIONS
            }
        } else {
            ZeaOnboardingState.saveGateStage(context, null)
            stage = ZeaPinGateStage.UNLOCKED
        }
    }

    val fingerprintAllowedHere = ZeaSecurityState.securityEnabled &&
            ZeaSecurityState.fingerprintUnlockEnabled

    when (stage) {
        ZeaPinGateStage.CREATE_PIN -> {
            ZeaPinEntryScreen(
                title = "Create your Zyro PIN",
                subtitle = "Choose a $ZYRO_PIN_MIN_LENGTH-$ZYRO_PIN_MAX_LENGTH digit PIN. " +
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        "This PIN belongs to Zyro only and is independent of your phone's lock-screen PIN.",
                buttonLabel = "Continue",
                showBackButton = false,
                errorText = gateErrorText,
                currentPage = null,
                totalPages = 1,
                onBack = { gateErrorText = "" },
                onSubmit = { enteredPin ->
                    pendingCreatePin = enteredPin
                    gateErrorText = ""
                    ZeaOnboardingState.saveGateStage(context, ZeaOnboardingState.STAGE_CONFIRM_PIN)
                    stage = ZeaPinGateStage.CONFIRM_PIN
                }
            )
        }

        ZeaPinGateStage.CONFIRM_PIN -> {
            ZeaPinEntryScreen(
                title = "Confirm your PIN",
                subtitle = "Enter the same PIN once more to confirm.",
                buttonLabel = "Save PIN",
                showBackButton = true,
                errorText = gateErrorText,
                currentPage = null,
                totalPages = 1,
                onBack = {
                    pendingCreatePin = ""
                    gateErrorText = ""
                    ZeaOnboardingState.saveGateStage(context, null)
                    stage = ZeaPinGateStage.CREATE_PIN
                },
                onSubmit = { enteredPin ->
                    if (enteredPin == pendingCreatePin && saveAdminPin(context, enteredPin)) {
                        pendingCreatePin = ""
                        gateErrorText = ""
                        ZeaOnboardingState.saveGateStage(
                            context,
                            ZeaOnboardingState.STAGE_MODE_SELECTION
                        )
                        stage = ZeaPinGateStage.MODE_SELECTION
                    } else if (enteredPin != pendingCreatePin) {
                        pendingCreatePin = ""
                        gateErrorText = "The PINs did not match. Please start again."
                        ZeaOnboardingState.saveGateStage(context, null)
                        stage = ZeaPinGateStage.CREATE_PIN
                    } else {
                        gateErrorText = "The PIN could not be saved. Please try again."
                    }
                }
            )
        }

        ZeaPinGateStage.ENTER_PIN -> {
            ZeaPinEntryScreen(
                title = ZeaTemporaryAppLockConfig.LOCKED_TITLE,
                subtitle = if (fingerprintAllowedHere) {
                    "Enter your Zyro PIN to continue, or use your enrolled device fingerprint."
                } else {
                    "Enter your Zyro PIN to continue. Fingerprint and device unlock are never used here."
                },
                buttonLabel = "Unlock",
                showBackButton = false,
                errorText = gateErrorText,
                currentPage = null,
                totalPages = 1,
                fingerprintEnabled = fingerprintAllowedHere,
                onFingerprintAuthenticated = { performAuthenticatedUnlock() },
                onBack = { gateErrorText = "" },
                onSubmit = { enteredPin ->
                    if (verifyAdminPin(context, enteredPin)) {
                        gateErrorText = ""
                        performAuthenticatedUnlock()
                    } else {
                        gateErrorText = "Incorrect PIN. Please try again."
                    }
                }
            )
        }

        ZeaPinGateStage.MODE_SELECTION -> {
            ZeaModeSelectionScreen(
                deviceOwnerAvailable = zyroIsDeviceOwnerProvisioned(context),
                onModeChosen = { chosenMode ->
                    ZeaOnboardingState.saveSelectedMode(context, chosenMode)
                    ZeaOnboardingState.saveGateStage(
                        context,
                        ZeaOnboardingState.STAGE_ONBOARDING_PERMISSIONS
                    )
                    stage = ZeaPinGateStage.ONBOARDING_PERMISSIONS
                }
            )
        }

        ZeaPinGateStage.ONBOARDING_PERMISSIONS -> {
            ZeaPermissionsOnboardingScreen(
                pinPagesCompleted = 0,
                repairMode = ZeaOnboardingState.isPermissionsOnboardingCompleted(context),
                onFinished = {
                    ZeaOnboardingState.markPermissionsOnboardingCompleted(context)
                    ZeaOnboardingState.saveGateStage(context, null)
                    stage = ZeaPinGateStage.UNLOCKED
                }
            )
        }

        ZeaPinGateStage.UNLOCKED -> Unit
    }

    if (stage != ZeaPinGateStage.UNLOCKED) {
        return
    }

    content()
}

/**
 * Choose Zyro Mode - the single decision page right after PIN confirmation.
 * Two mutually exclusive setup tracks:
 *  - Device Owner mode: advanced full-control track driven by real Android
 *    Device Owner capabilities.
 *  - Standard mode: normal usage track built on the accessibility engine.
 *
 * The Device Owner card is only selectable when the PLATFORM reports actual
 * provisioning (zyroIsDeviceOwnerProvisioned). A UI selection never grants
 * the role and never fakes its status.
 */
@Composable
private fun ZeaModeSelectionScreen(
    deviceOwnerAvailable: Boolean,
    onModeChosen: (String) -> Unit
) {
    var selectedMode by remember { mutableStateOf<String?>(null) }

    ZeaGateScaffold {
        Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
            ZeaHeroTile(glyph = ZeaGateGlyph.SHIELD)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Choose Zyro Mode",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = ZeaGateUi.TextPrimary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(9.dp))

        Text(
            text = "Pick how Zyro should protect this device. You cannot change this later without resetting setup.",
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            color = ZeaGateUi.TextMuted,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(22.dp))

        ZeaModeCard(
            title = "Device Owner Mode",
            description = "Advanced full-control mode using Android's Device Owner capabilities for the strongest protection.",
            selected = selectedMode == ZeaOnboardingState.MODE_DEVICE_OWNER,
            enabled = deviceOwnerAvailable,
            unavailableNote = if (deviceOwnerAvailable) null else {
                "Device Owner is not provisioned on this device"
            },
            onClick = { selectedMode = ZeaOnboardingState.MODE_DEVICE_OWNER }
        )

        Spacer(modifier = Modifier.height(12.dp))

        ZeaModeCard(
            title = "Standard Mode",
            description = "Normal usage mode where Zyro protects apps without Device Owner capabilities.",
            selected = selectedMode == ZeaOnboardingState.MODE_STANDARD,
            enabled = !deviceOwnerAvailable,
            unavailableNote = if (deviceOwnerAvailable) {
                "Device Owner is provisioned - Standard Mode is locked to keep protection at its strongest"
            } else {
                null
            },
            onClick = { selectedMode = ZeaOnboardingState.MODE_STANDARD }
        )

        Spacer(modifier = Modifier.weight(1f))

        ZeaPrimaryCta(
            label = "Continue",
            enabled = selectedMode != null,
            onClick = { selectedMode?.let(onModeChosen) }
        )

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun ZeaModeCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    unavailableNote: String?,
    onClick: () -> Unit
) {
    val borderColor = when {
        !enabled -> ZeaGateUi.TextMuted.copy(alpha = 0.25f)
        selected -> ZeaGateUi.AccentStart
        else -> ZeaGateUi.TextMuted.copy(alpha = 0.45f)
    }
    val containerColor = if (selected && enabled) {
        ZeaGateUi.AccentStart.copy(alpha = 0.10f)
    } else {
        Color.Transparent
    }
    val contentAlpha = if (enabled) 1f else 0.55f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(
                width = if (selected && enabled) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            color = if (selected && enabled) {
                                ZeaGateUi.AccentStart
                            } else {
                                ZeaGateUi.TextMuted.copy(alpha = 0.6f)
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected && enabled) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ZeaGateUi.AccentStart)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZeaGateUi.TextPrimary.copy(alpha = contentAlpha)
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            Text(
                text = description,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = ZeaGateUi.TextMuted.copy(alpha = contentAlpha),
                modifier = Modifier.padding(start = 28.dp)
            )

            if (unavailableNote != null) {
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = unavailableNote,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZeaGateUi.TextMuted.copy(alpha = 0.9f),
                    modifier = Modifier.padding(start = 28.dp)
                )
            }
        }
    }
}

/**
 * State-driven permissions onboarding. The visible page is ALWAYS derived
 * from live Android state - first unsatisfied step wins - so already-granted
 * pages are never replayed, closing the app mid-setup resumes exactly where
 * it stopped, and a later revocation re-opens its own page on the next
 * unlock. The completion flag lives in ZeaOnboardingState; repair passes
 * (flag already set) hide the page counter since the N-page walkthrough no
 * longer applies.
 */
@Composable
private fun ZeaPermissionsOnboardingScreen(
    pinPagesCompleted: Int,
    repairMode: Boolean,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var lockServiceEnabled by remember { mutableStateOf(false) }
    var usageAccessGranted by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }

    // Session-local floor over the step list. Live re-validation always
    // selects the first unsatisfied page; the floor only moves forward when
    // the user consciously advances past a still-missing permission (Skip),
    // so a skipped reminder cannot trap the flow inside this session - yet
    // it resurfaces again on future launches until it is actually allowed.
    var minStepIndex by remember { mutableIntStateOf(0) }

    val steps = remember { zyroOnboardingSteps(context) }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
        }
    }

    // THE STATE MACHINE: effective page = first PENDING step at or after the
    // session floor. Recomputed on every resume (settings round trips flip
    // accessibility instantly) and every manual advance. Advancing past a
    // skipped page never lands on an already-satisfied one - the next
    // genuinely pending page wins, and if nothing is left the flow finishes.
    val effectiveIndex = remember(resumeTick, minStepIndex) {
        var next = steps.size
        for ((index, step) in steps.withIndex()) {
            if (index >= minStepIndex && zyroIsOnboardingStepPending(context, step)) {
                next = index
                break
            }
        }
        next
    }

    LaunchedEffect(effectiveIndex, resumeTick) {
        lockServiceEnabled = zyroIsLockEngineHealthy(context)
        usageAccessGranted = zyroIsUsageAccessGranted(context)
    }

    LaunchedEffect(effectiveIndex) {
        if (effectiveIndex >= steps.size) {
            // Everything became healthy mid-session (e.g. the service was
            // enabled in Settings): leave onboarding straight away.
            onFinished()
        }
    }

    fun advancePastCurrent() {
        minStepIndex = effectiveIndex + 1
    }

    val contactsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        advancePastCurrent()
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onFinished()
    }

    if (effectiveIndex >= steps.size) {
        // Fully healthy at composition time: render nothing, the effect
        // above hands control back to the gate.
        return
    }

    val activeStep = steps[effectiveIndex]

    ZeaGateScaffold {
        if (!repairMode) {
            ZeaGateProgress(pinPagesCompleted + effectiveIndex + 1, pinPagesCompleted + steps.size)
        }

        Spacer(modifier = Modifier.height(26.dp))

        val activeGlyph = when (activeStep) {
            ZEA_STEP_CONTACTS -> ZeaGateGlyph.PEOPLE
            ZEA_STEP_LOCK -> ZeaGateGlyph.LOCK
            ZEA_STEP_USAGE_ACCESS -> ZeaGateGlyph.SHIELD
            else -> ZeaGateGlyph.BELL
        }
        Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
            ZeaHeroTile(glyph = activeGlyph)
        }

        Spacer(modifier = Modifier.height(20.dp))

        val (heading, description, primaryLabel, secondaryLabel, statusGranted) = when (activeStep) {
            ZEA_STEP_CONTACTS -> Quintuple(
                "Contacts Access",
                "Zyro uses your contacts to send messages when you ask it to. Allow access to continue.",
                "Allow Contacts",
                null as String?,
                null as Boolean?
            )
            ZEA_STEP_LOCK -> Quintuple(
                "App Lock Engine",
                if (lockServiceEnabled) {
                    "App Lock is active. Whenever a locked app opens outside Zyro, you return to the home screen instantly."
                } else {
                    "This lets Zyro pull you back to the home screen whenever a locked app opens outside it. Turn App Lock on for Zyro in Accessibility settings."
                },
                "Open Settings",
                null as String?,
                lockServiceEnabled
            )
            ZEA_STEP_USAGE_ACCESS -> Quintuple(
                "Usage Access",
                if (usageAccessGranted) {
                    "Usage access is active. Private apps are re-hidden the moment they leave the foreground."
                } else {
                    "This lets Zyro re-hide a private app right after it leaves the foreground. Enable Usage access for Zyro in the next screen."
                },
                "Open Settings",
                null as String?,
                usageAccessGranted
            )
            else -> Quintuple(
                "Notifications",
                "Zyro shows a notification while a private-app session or recovery is active.",
                "Allow Notifications",
                null as String?,
                null as Boolean?
            )
        }

        Text(
            text = heading,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = ZeaGateUi.TextPrimary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(9.dp))

        Text(
            text = description,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            color = ZeaGateUi.TextMuted,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        val featureRows: List<Triple<ZeaGateGlyph, String, String>> = when (activeStep) {
            ZEA_STEP_CONTACTS -> listOf(
                Triple(ZeaGateGlyph.BOLT, "Instant detection", "Know which contacts open protected apps"),
                Triple(ZeaGateGlyph.PEOPLE, "Private contact vault", "Chosen contacts vanish from every search"),
                Triple(ZeaGateGlyph.SHIELD, "Zero uploads", "Your address book never touches any server")
            )
            ZEA_STEP_LOCK -> listOf(
                Triple(ZeaGateGlyph.BOLT, "Real-time guard", "Watches launches even after reboot"),
                Triple(ZeaGateGlyph.LOCK, "Enforcement core", "Blocks bypass attempts instantly"),
                Triple(ZeaGateGlyph.SHIELD, "Reads nothing", "No screen content, no keystrokes, ever")
            )
            ZEA_STEP_USAGE_ACCESS -> listOf(
                Triple(ZeaGateGlyph.SHIELD, "Auto re-hide", "Private apps vanish the moment you leave them"),
                Triple(ZeaGateGlyph.BOLT, "Instant response", "No lag between leaving and hiding"),
                Triple(ZeaGateGlyph.LOCK, "App-level only", "Never reads screen content or messages")
            )
            else -> listOf(
                Triple(ZeaGateGlyph.BELL, "Intrusion alerts", "Someone hits a hidden app? You'll know"),
                Triple(ZeaGateGlyph.SHIELD, "Action confirmations", "Apps hidden or restored â€” logged for you"),
                Triple(ZeaGateGlyph.BOLT, "Silent by default", "No spam, no marketing, no noise")
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            featureRows.forEach { row ->
                ZeaFeatureRow(glyph = row.first, title = row.second, description = row.third)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Revocable anytime Â· Nothing leaves your device",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = ZeaGateUi.AccentStart
            )
        }

        if (statusGranted == true) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99))
                    .background(ZeaGateUi.Success.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 7.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                Text(text = "âœ“ Granted", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ZeaGateUi.Success)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val onPrimary: () -> Unit = when (activeStep) {
            ZEA_STEP_CONTACTS -> {
                {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.READ_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        advancePastCurrent()
                    } else {
                        contactsLauncher.launch(
                            android.Manifest.permission.READ_CONTACTS
                        )
                    }
                }
            }
            ZEA_STEP_LOCK -> {
                {
                    // Tag this navigation as an authenticated round trip so
                    // the gate resumes the engine page without re-asking the
                    // PIN when the user comes back.
                    ZeaOnboardingState.markAccessibilityRoundTrip(context)
                    zeaGateLeftToAccessibilitySettingsAtMs = System.currentTimeMillis()
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    } catch (_: RuntimeException) {
                        // Settings screen unavailable on this build.
                    }
                }
            }
            ZEA_STEP_USAGE_ACCESS -> {
                {
                    // Same authenticated round-trip contract as the engine
                    // page: the return leg lands back here without a PIN,
                    // every other launch still demands it.
                    ZeaOnboardingState.markAccessibilityRoundTrip(context)
                    zeaGateLeftToAccessibilitySettingsAtMs = System.currentTimeMillis()
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        )
                    } catch (_: RuntimeException) {
                        // Settings screen unavailable on this build.
                    }
                }
            }
            else -> {
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            onFinished()
                        } else {
                            notificationsLauncher.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    } else {
                        onFinished()
                    }
                }
            }
        }

        ZeaPrimaryCta(label = primaryLabel, onClick = onPrimary)

        when (activeStep) {
            ZEA_STEP_CONTACTS -> {
                Spacer(modifier = Modifier.height(6.dp))
                ZeaGhostLink(label = "Skip for now", onClick = { advancePastCurrent() })
            }

            ZEA_STEP_LOCK -> {
                Spacer(modifier = Modifier.height(8.dp))
                ZeaPrimaryCta(
                    label = "Continue",
                    enabled = lockServiceEnabled,
                    onClick = {
                        // The engine page only ever advances by an explicit
                        // Continue while healthy - never automatically.
                        ZeaOnboardingState.markStepAcknowledged(context, ZEA_STEP_LOCK)
                        advancePastCurrent()
                    }
                )
            }

            ZEA_STEP_USAGE_ACCESS -> {
                Spacer(modifier = Modifier.height(8.dp))
                ZeaPrimaryCta(
                    label = "Continue",
                    enabled = usageAccessGranted,
                    onClick = {
                        // Mandatory step: advances only by an explicit
                        // Continue while Usage access is actually granted.
                        ZeaOnboardingState.markStepAcknowledged(context, ZEA_STEP_USAGE_ACCESS)
                        advancePastCurrent()
                    }
                )
            }

            else -> {
                Spacer(modifier = Modifier.height(6.dp))
                ZeaGhostLink(label = "Skip for now", onClick = { onFinished() })
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
