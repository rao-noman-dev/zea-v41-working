package com.raomuhammadnoman.zea

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Phase 2 (P1) - PIN brute-force protection.
 *
 * Consecutive failed PIN attempts trigger progressively longer cooldowns so
 * unlimited guessing becomes impractical. The policy is deliberately simple
 * and user-friendly:
 *
 *   5 failures  -> 30 seconds
 *   10 failures -> 2 minutes
 *   15 failures -> 5 minutes
 *
 * A successful unlock resets the counter to zero. The counter, the cooldown
 * deadline, and the last successful unlock are persisted so closing,
 * force-stopping, or rebooting cannot bypass an active cooldown.
 *
 * The pure policy ([cooldownMillisForFailures]) is separated from storage so
 * it can be unit-tested without Android.
 */
object ZeaPinLockout {
    private const val PREFS_NAME = "zyro_pin_lockout_v1"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempt_count"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until_epoch_ms"
    private const val KEY_LAST_SUCCESS = "last_successful_unlock_epoch_ms"

    const val FIRST_TIER_FAILURES = 5
    const val SECOND_TIER_FAILURES = 10
    const val THIRD_TIER_FAILURES = 15

    const val FIRST_TIER_COOLDOWN_MILLIS = 30_000L
    const val SECOND_TIER_COOLDOWN_MILLIS = 2L * 60_000L
    const val THIRD_TIER_COOLDOWN_MILLIS = 5L * 60_000L

    /**
     * Cooldown (ms) owed for [failedAttempts] consecutive failures.
     * 0 means the user may try again immediately. The highest reached tier
     * always wins, and the counter never shrinks except via [recordSuccess].
     */
    fun cooldownMillisForFailures(failedAttempts: Int): Long = when {
        failedAttempts >= THIRD_TIER_FAILURES -> THIRD_TIER_COOLDOWN_MILLIS
        failedAttempts >= SECOND_TIER_FAILURES -> SECOND_TIER_COOLDOWN_MILLIS
        failedAttempts >= FIRST_TIER_FAILURES -> FIRST_TIER_COOLDOWN_MILLIS
        else -> 0L
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun failedAttemptCount(context: Context): Int =
        prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0)

    fun cooldownUntilEpochMillis(context: Context): Long =
        prefs(context).getLong(KEY_COOLDOWN_UNTIL, 0L)

    fun lastSuccessfulUnlockEpochMillis(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SUCCESS, 0L)

    /**
     * Milliseconds the user must still wait before the next PIN attempt.
     * Returns 0 when no cooldown is active.
     */
    fun cooldownRemainingMillis(context: Context, nowEpochMillis: Long = System.currentTimeMillis()): Long {
        val remaining = cooldownUntilEpochMillis(context) - nowEpochMillis
        return if (remaining > 0L) remaining else 0L
    }

    fun isLockedOut(context: Context, nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        cooldownRemainingMillis(context, nowEpochMillis) > 0L

    /**
     * Records one failed PIN attempt and, when a tier threshold is reached,
     * starts the matching cooldown window. Returns the cooldown that was just
     * armed (0 when the attempt count is still below the first tier).
     */
    fun recordFailure(context: Context, nowEpochMillis: Long = System.currentTimeMillis()): Long {
        val attempts = failedAttemptCount(context) + 1
        val cooldownMillis = cooldownMillisForFailures(attempts)
        val editor = prefs(context).edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
        if (cooldownMillis > 0L) {
            editor.putLong(KEY_COOLDOWN_UNTIL, nowEpochMillis + cooldownMillis)
        }
        editor.apply()
        return cooldownMillis
    }

    /** A successful PIN unlock clears the failure counter and any cooldown. */
    fun recordSuccess(context: Context, nowEpochMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_COOLDOWN_UNTIL, 0L)
            .putLong(KEY_LAST_SUCCESS, nowEpochMillis)
            .apply()
    }

    /** User-facing message for an active cooldown, e.g. "45 seconds". */
    fun cooldownMessage(context: Context, nowEpochMillis: Long = System.currentTimeMillis()): String {
        val remainingMillis = cooldownRemainingMillis(context, nowEpochMillis)
        if (remainingMillis <= 0L) return ""
        val remainingSeconds = (remainingMillis + 999L) / 1000L
        val minutes = remainingSeconds / 60L
        val seconds = remainingSeconds % 60L
        val durationText = when {
            minutes > 0L && seconds > 0L -> "$minutes min $seconds sec"
            minutes > 0L -> if (minutes == 1L) "1 minute" else "$minutes minutes"
            else -> if (seconds == 1L) "1 second" else "$seconds seconds"
        }
        return "Too many incorrect attempts. Try again in $durationText."
    }
}

/**
 * Live lockout snapshot for PIN gates. Recomposes roughly once per second
 * while the gate is visible so the countdown text and the submit-enabled
 * state stay accurate. [attemptVersion] should be bumped after every submit
 * so a freshly armed cooldown is reflected immediately.
 */
internal data class ZeaPinLockoutSnapshot(
    val lockedOut: Boolean,
    val message: String
)

@Composable
internal fun rememberZeaPinLockout(
    context: Context,
    attemptVersion: Int
): ZeaPinLockoutSnapshot {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(attemptVersion) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    return ZeaPinLockoutSnapshot(
        lockedOut = ZeaPinLockout.isLockedOut(context, now),
        message = ZeaPinLockout.cooldownMessage(context, now)
    )
}
