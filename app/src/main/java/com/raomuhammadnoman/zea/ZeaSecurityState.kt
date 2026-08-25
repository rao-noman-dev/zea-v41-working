package com.raomuhammadnoman.zea

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/**
 * Central source of truth for the user-facing security switches:
 *  - securityEnabled: master switch for EVERY Zyro authentication lock
 *    (global launch PIN, Hidden Apps, Timed/Time hidden sections, private
 *    areas, fingerprint unlock). When false, no user-facing lock asks for
 *    authentication anywhere in the app until the user re-enables it.
 *  - fingerprintUnlockEnabled: allows the device's already-enrolled Android
 *    biometrics as an ALTERNATIVE to the Zyro PIN on lock screens.
 *
 * Both flags are compose-observed so every gate reacts instantly, and both
 * persist through app restarts and device reboots. The Developer Controls
 * access key is intentionally NOT managed here and stays fully independent
 * of these switches.
 */
object ZeaSecurityState {
    private const val PREFS_NAME = "zyro_security_settings_v1"
    private const val KEY_SECURITY_ENABLED = "security_enabled"
    private const val KEY_FINGERPRINT_ENABLED = "fingerprint_unlock_enabled"

    /**
     * Compose-observed master switch. Defaults to true so a fresh install
     * behaves exactly like the pre-settings app; load() reconciles with the
     * persisted value at activity start.
     */
    var securityEnabled by mutableStateOf(true)
        private set

    /** Compose-observed fingerprint-unlock alternative-to-PIN switch. */
    var fingerprintUnlockEnabled by mutableStateOf(false)
        private set

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads persisted values into the observed state. Call at app start. */
    fun load(context: Context) {
        val p = prefs(context)
        securityEnabled = p.getBoolean(KEY_SECURITY_ENABLED, true)
        fingerprintUnlockEnabled = p.getBoolean(KEY_FINGERPRINT_ENABLED, false)
    }

    fun setSecurityEnabled(context: Context, enabled: Boolean) {
        securityEnabled = enabled
        prefs(context).edit().putBoolean(KEY_SECURITY_ENABLED, enabled).apply()
    }

    fun setFingerprintUnlockEnabled(context: Context, enabled: Boolean) {
        fingerprintUnlockEnabled = enabled
        prefs(context).edit().putBoolean(KEY_FINGERPRINT_ENABLED, enabled).apply()
    }

    /**
     * Resets both switches to their defaults (used by the developer-only
     * self-clear hook) so a wiped app never comes back unprotected while a
     * stale "disabled" flag survives in storage.
     */
    fun resetToDefaults(context: Context) {
        setSecurityEnabled(context, true)
        setFingerprintUnlockEnabled(context, false)
    }

    /**
     * True only when the device reports usable, already-enrolled biometrics.
     * Zyro NEVER enrolls anything itself; this merely reflects the official
     * Android biometric state.
     */
    fun isBiometricEnrolled(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Launches the official Android BiometricPrompt against the device's
     * enrolled fingerprints. The PIN stays the fallback path - the prompt's
     * negative button simply closes it, returning the user to PIN entry.
     */
    fun launchFingerprintPrompt(
        activity: FragmentActivity,
        title: String,
        onSuccess: () -> Unit,
        onError: (CharSequence) -> Unit = {}
    ) {
        val executor: Executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    // Silent for user-driven cancels; surface real failures.
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        onError(errString)
                    }
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Use Zyro PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }
}
