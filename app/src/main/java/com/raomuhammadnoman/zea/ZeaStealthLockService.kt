package com.raomuhammadnoman.zea

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Enforcement engine for App Lock mode.
 *
 * Watches window-change events and bounces the user back to the home screen
 * whenever a Zyro-protected app is opened from outside Zyro. Launching the
 * same app from inside Zyro arms a session allowance (ZeaLockMode), so
 * intended use is never interrupted; the allowance dies shortly after the
 * user leaves the protected app.
 *
 * The service never reads window content and never blocks Zyro itself, the
 * keyboard, or any app that is not in the protected set.
 */
class ZeaStealthLockService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val eventPackage = event.packageName?.toString()?.trim().orEmpty()
        if (eventPackage.isEmpty()) return
        if (eventPackage.equals(packageName, ignoreCase = true)) {
            // Returning to Zyro always ends any open launch session.
            ZeaLockMode.clearSessionAllow(applicationContext)
            return
        }

        val allowedPackage = ZeaLockMode.sessionAllowPackage(applicationContext)
        if (allowedPackage != null && allowedPackage.equals(eventPackage, ignoreCase = true)) {
            // Protected app still in the foreground: keep its session alive.
            ZeaLockMode.armSessionAllow(applicationContext, allowedPackage)
            return
        }

        if (!ZeaLockMode.isBlocked(applicationContext, eventPackage)) return

        performGlobalAction(GLOBAL_ACTION_HOME)
        maybeShowLockedFeedback(eventPackage)
    }

    private fun maybeShowLockedFeedback(eventPackage: String) {
        val now = System.currentTimeMillis()
        synchronized(lastFeedbackAt) {
            val last = lastFeedbackAt[eventPackage] ?: 0L
            if (now - last < FEEDBACK_THROTTLE_MILLIS) return
            lastFeedbackAt[eventPackage] = now
        }
        try {
            Toast.makeText(
                applicationContext,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Locked by Zyro. Open it from Zyro to continue.",
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: RuntimeException) {
            // Feedback is cosmetic; blocking already happened.
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val FEEDBACK_THROTTLE_MILLIS = 5_000L
        private val lastFeedbackAt = mutableMapOf<String, Long>()
    }
}
