package com.raomuhammadnoman.zea

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * State layer for App Lock mode, the fallback protection engine used when
 * Zyro is not the Device Owner on a device.
 *
 * Android never lets a normal app remove another app's launcher icon, so in
 * this mode "hidden" means enforced locking instead: an AccessibilityService
 * watches window changes and bounces the user back to the home screen the
 * moment a protected app is opened outside Zyro. Launching the same app from
 * inside Zyro arms a session allowance so normal use keeps working.
 *
 * The private-app record storage is shared with Device Owner mode, so every
 * list and management screen works identically in both modes; only the
 * enforcement layer differs.
 */
object ZeaLockMode {

    private const val PREFS_NAME = "zea_lock_mode_state"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_SESSION_ALLOW_PACKAGE = "session_allow_package"
    private const val KEY_TIMED_PREFIX = "timed_until_"

    /**
     * How long a launch session stays alive without seeing the allowed app's
     * window again. Refreshed on every matching window event, so it only
     * expires after the user has truly left the app.
     */
    private const val SESSION_WINDOW_MILLIS = 45_000L
    private const val KEY_SESSION_ALLOW_EXPIRES = "session_allow_expires"

    fun isLockMode(context: Context): Boolean {
        return !ZeaDeviceOwnerController.isDeviceOwner(context)
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun blockedPackages(context: Context): Set<String> {
        return try {
            prefs(context).getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
        } catch (_: RuntimeException) {
            emptySet()
        }
    }

    fun isBlocked(
        context: Context,
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val stored = blockedPackages(context).any { stored ->
            stored.equals(packageName, ignoreCase = true)
        }
        if (!stored) return false

        val unlockAt = timedUnlockAt(context, packageName)
        return unlockAt <= 0L || nowMillis < unlockAt
    }

    fun block(context: Context, packageName: String): Boolean {
        return try {
            val updated = blockedPackages(context).toMutableSet()
            updated.add(packageName.lowercase())
            prefs(context).edit()
                .putStringSet(KEY_BLOCKED_PACKAGES, updated)
                .remove(KEY_TIMED_PREFIX + packageName.lowercase())
                .commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    fun blockUntil(
        context: Context,
        packageName: String,
        unlockAtEpochMillis: Long
    ): Boolean {
        return try {
            val updated = blockedPackages(context).toMutableSet()
            updated.add(packageName.lowercase())
            prefs(context).edit()
                .putStringSet(KEY_BLOCKED_PACKAGES, updated)
                .putLong(KEY_TIMED_PREFIX + packageName.lowercase(), unlockAtEpochMillis)
                .commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    fun unblock(context: Context, packageName: String): Boolean {
        return try {
            prefs(context).edit()
                .putStringSet(KEY_BLOCKED_PACKAGES, blockedPackages(context).toMutableSet().apply {
                    removeAll { it.equals(packageName, ignoreCase = true) }
                })
                .remove(KEY_TIMED_PREFIX + packageName.lowercase())
                .commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun timedUnlockAt(context: Context, packageName: String): Long {
        return try {
            prefs(context).getLong(KEY_TIMED_PREFIX + packageName.lowercase(), 0L)
        } catch (_: RuntimeException) {
            0L
        }
    }

    /** Phase-1 diagnostic contract for verifying timed App Lock durability. */
    fun timedUnlockAtEpochMillis(context: Context, packageName: String): Long =
        timedUnlockAt(context, packageName)

    /**
     * Arms or refreshes the launch-session allowance for one package. Called
     * right before Zyro opens a protected app and refreshed by the
     * accessibility service whenever that app stays in the foreground.
     */
    fun armSessionAllow(context: Context, packageName: String) {
        try {
            prefs(context).edit()
                .putString(KEY_SESSION_ALLOW_PACKAGE, packageName)
                .putLong(KEY_SESSION_ALLOW_EXPIRES, System.currentTimeMillis() + SESSION_WINDOW_MILLIS)
                .commit()
        } catch (_: RuntimeException) {
            // A failed write only shortens the grace window; blocking still works.
        }
    }

    fun sessionAllowPackage(context: Context): String? {
        return try {
            val expires = prefs(context).getLong(KEY_SESSION_ALLOW_EXPIRES, 0L)
            if (System.currentTimeMillis() > expires) {
                null
            } else {
                prefs(context).getString(KEY_SESSION_ALLOW_PACKAGE, null)
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun clearSessionAllow(context: Context, expectedPackage: String? = null) {
        try {
            val p = prefs(context)
            if (expectedPackage != null) {
                val current = p.getString(KEY_SESSION_ALLOW_PACKAGE, null)
                if (!current.equals(expectedPackage, ignoreCase = true)) return
            }
            p.edit()
                .remove(KEY_SESSION_ALLOW_PACKAGE)
                .remove(KEY_SESSION_ALLOW_EXPIRES)
                .commit()
        } catch (_: RuntimeException) {
        }
    }

    /**
     * True when the stealth-lock AccessibilityService is currently enabled in
     * system settings. Read straight from the secure settings table because
     * AccessibilityManager answers asynchronously for freshly enabled services.
     */
    fun isLockServiceEnabled(context: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expectedClass = ZeaStealthLockService::class.java.name
            enabled.split(':').any { entry ->
                val component = ComponentName.unflattenFromString(entry.trim())
                component != null &&
                        component.packageName.equals(context.packageName, ignoreCase = true) &&
                        component.className == expectedClass
            }
        } catch (_: RuntimeException) {
            false
        }
    }
}
