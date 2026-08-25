package com.raomuhammadnoman.zea

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Phase 2 (P1) - Protection Health engine.
 *
 * Evaluates the ten health signals the roadmap defines for the Protection
 * Health Dashboard and System Check. Evaluation is mode-aware: signals that
 * only make sense under Device Owner protection report NOT_APPLICABLE in
 * Standard mode instead of false warnings.
 */
enum class ZeaHealthStatus {
    HEALTHY,
    WARNING,
    NOT_APPLICABLE
}

data class ZeaHealthSignal(
    val id: String,
    val title: String,
    val status: ZeaHealthStatus,
    val detail: String,
    /** Android Settings action that can fix the issue, when one exists. */
    val fixSettingsAction: String? = null
)

data class ZeaProtectionHealthReport(
    val signals: List<ZeaHealthSignal>,
    val protectedCount: Int,
    val timedCount: Int,
    val deviceOwnerMode: Boolean,
    val protectionPaused: Boolean
) {
    val issueCount: Int
        get() = signals.count { signal -> signal.status == ZeaHealthStatus.WARNING }

    val healthy: Boolean
        get() = issueCount == 0

    val firstIssue: ZeaHealthSignal?
        get() = signals.firstOrNull { signal -> signal.status == ZeaHealthStatus.WARNING }
}

object ZeaProtectionHealth {
    const val SIGNAL_DEVICE_OWNER = "device_owner"
    const val SIGNAL_APP_LOCK_ENGINE = "app_lock_engine"
    const val SIGNAL_USAGE_ACCESS = "usage_access"
    const val SIGNAL_NOTIFICATIONS = "notifications"
    const val SIGNAL_EXACT_ALARMS = "exact_alarms"
    const val SIGNAL_REGISTRY = "registry_integrity"
    const val SIGNAL_PENDING_REHIDE = "pending_rehide"
    const val SIGNAL_MONITOR_SERVICE = "monitor_service"
    const val SIGNAL_INSTALL_LOCK = "install_lock"
    const val SIGNAL_LAUNCHER_SYNC = "launcher_sync"

    /**
     * Full health evaluation. Runs on the IO dispatcher because registry,
     * DPM, and catalog reads touch storage and system services.
     */
    suspend fun evaluate(context: Context): ZeaProtectionHealthReport =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val deviceOwnerMode = ZeaOnboardingState.readSelectedMode(appContext) ==
                    ZeaOnboardingState.MODE_DEVICE_OWNER
            val deviceOwnerActive = ZeaDeviceOwnerController.isDeviceOwner(appContext)

            val registryIssue = registryIntegrityIssue(appContext)
            val protectedCount = loadPrivateApps(appContext).size
            val now = System.currentTimeMillis()
            val timedCount = loadTimedHides(appContext).count { record ->
                record.hiddenUntilEpochMillis > now
            }
            val pendingRehide = ZeaDeviceOwnerController.pendingRehidePackages(appContext)
            val protectionPaused = ZeaDeviceOwnerController.isProtectionPaused(appContext)

            val signals = mutableListOf<ZeaHealthSignal>()

            // 1. Device Owner active (only required in Device Owner mode).
            signals += if (deviceOwnerMode) {
                if (deviceOwnerActive) {
                    ZeaHealthSignal(
                        SIGNAL_DEVICE_OWNER,
                        "Device Owner",
                        ZeaHealthStatus.HEALTHY,
                        "Device Owner protection is active."
                    )
                } else {
                    ZeaHealthSignal(
                        SIGNAL_DEVICE_OWNER,
                        "Device Owner",
                        ZeaHealthStatus.WARNING,
                        "Device Owner mode is selected but the role is not provisioned. Hiding cannot work."
                    )
                }
            } else {
                ZeaHealthSignal(
                    SIGNAL_DEVICE_OWNER,
                    "Device Owner",
                    ZeaHealthStatus.NOT_APPLICABLE,
                    "Standard mode uses the App Lock engine instead."
                )
            }

            // 2. App Lock engine (accessibility service in Standard mode).
            signals += if (zyroIsLockEngineHealthy(appContext)) {
                ZeaHealthSignal(
                    SIGNAL_APP_LOCK_ENGINE,
                    "App Lock Engine",
                    ZeaHealthStatus.HEALTHY,
                    if (deviceOwnerMode) "Not required in Device Owner mode." else "Accessibility lock engine is running."
                )
            } else {
                ZeaHealthSignal(
                    SIGNAL_APP_LOCK_ENGINE,
                    "App Lock Engine",
                    ZeaHealthStatus.WARNING,
                    "App Lock accessibility service is disabled. Locked apps can open freely.",
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            }

            // 3. Usage Access.
            signals += if (ZeaDeviceOwnerController.isUsageAccessGranted(appContext)) {
                ZeaHealthSignal(
                    SIGNAL_USAGE_ACCESS,
                    "Usage Access",
                    ZeaHealthStatus.HEALTHY,
                    "Usage Access is granted; private session monitoring can work."
                )
            } else {
                ZeaHealthSignal(
                    SIGNAL_USAGE_ACCESS,
                    "Usage Access",
                    ZeaHealthStatus.WARNING,
                    "Usage Access is disabled. Private app monitoring may not work.",
                    Settings.ACTION_USAGE_ACCESS_SETTINGS
                )
            }

            // 4. Notification permission (Android 13+ only requires it).
            val notificationsGranted = zyroAreNotificationsGranted(appContext)
            signals += if (notificationsGranted || Build.VERSION.SDK_INT < 33) {
                ZeaHealthSignal(
                    SIGNAL_NOTIFICATIONS,
                    "Notifications",
                    ZeaHealthStatus.HEALTHY,
                    "Notifications are available for protection alerts."
                )
            } else {
                ZeaHealthSignal(
                    SIGNAL_NOTIFICATIONS,
                    "Notifications",
                    ZeaHealthStatus.WARNING,
                    "Notification permission is not granted. Protection alerts stay silent.",
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                )
            }

            // 5. Exact alarm capability for timed hiding (Android 12+ gate).
            val exactAlarmsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(AlarmManager::class.java)
                    ?.canScheduleExactAlarms() == true
            } else {
                true
            }
            signals += if (exactAlarmsOk) {
                ZeaHealthSignal(
                    SIGNAL_EXACT_ALARMS,
                    "Timed Hide Engine",
                    ZeaHealthStatus.HEALTHY,
                    "Exact alarms are available for timed hiding."
                )
            } else {
                ZeaHealthSignal(
                    SIGNAL_EXACT_ALARMS,
                    "Timed Hide Engine",
                    ZeaHealthStatus.WARNING,
                    "Exact alarms are not allowed. Timed unhide may be delayed.",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    } else {
                        null
                    }
                )
            }

            // 6. Registry integrity.
            signals += if (registryIssue == null) {
                ZeaHealthSignal(
                    SIGNAL_REGISTRY,
                    "Registry Integrity",
                    ZeaHealthStatus.HEALTHY,
                    "Protected-app registry is readable and valid."
                )
            } else {
                ZeaHealthSignal(
                    SIGNAL_REGISTRY,
                    "Registry Integrity",
                    ZeaHealthStatus.WARNING,
                    registryIssue
                )
            }

            // 7. Pending re-hide queue.
            signals += if (pendingRehide.isEmpty()) {
                ZeaHealthSignal(
                    SIGNAL_PENDING_REHIDE,
                    "Pending Re-hide",
                    ZeaHealthStatus.HEALTHY,
                    "No apps are waiting to be re-hidden."
                )
            } else {
                ZeaHealthSignal(
                    SIGNAL_PENDING_REHIDE,
                    "Pending Re-hide",
                    ZeaHealthStatus.WARNING,
                    "${pendingRehide.size} app(s) still need to be re-hidden. Protection is in fail-closed recovery."
                )
            }

            // 8. Private session monitor availability (Device Owner mode only).
            val monitorAvailable = try {
                appContext.packageManager.getServiceInfo(
                    android.content.ComponentName(
                        appContext,
                        ZeaPrivateSessionMonitorService::class.java
                    ),
                    0
                )
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: RuntimeException) {
                false
            }
            signals += when {
                !deviceOwnerMode -> ZeaHealthSignal(
                    SIGNAL_MONITOR_SERVICE,
                    "Private Session Monitor",
                    ZeaHealthStatus.NOT_APPLICABLE,
                    "Session monitoring is a Device Owner mode feature."
                )
                monitorAvailable -> ZeaHealthSignal(
                    SIGNAL_MONITOR_SERVICE,
                    "Private Session Monitor",
                    ZeaHealthStatus.HEALTHY,
                    "Monitor service is declared and available."
                )
                else -> ZeaHealthSignal(
                    SIGNAL_MONITOR_SERVICE,
                    "Private Session Monitor",
                    ZeaHealthStatus.WARNING,
                    "Monitor service is unavailable. Opened private apps may not re-hide automatically."
                )
            }

            // 9. Protection install lock consistency (Device Owner only).
            signals += when {
                !deviceOwnerActive -> ZeaHealthSignal(
                    SIGNAL_INSTALL_LOCK,
                    "Install Lock",
                    if (deviceOwnerMode) ZeaHealthStatus.WARNING else ZeaHealthStatus.NOT_APPLICABLE,
                    if (deviceOwnerMode) {
                        "Install lock cannot be verified without Device Owner."
                    } else {
                        "Install lock is a Device Owner mode feature."
                    }
                )
                else -> {
                    val lockActive = ZeaDeviceOwnerController
                        .readUiState(appContext)
                        .protectionInstallLockActive
                    val shouldBeActive = protectedCount > 0 || pendingRehide.isNotEmpty()
                    if (lockActive == shouldBeActive) {
                        ZeaHealthSignal(
                            SIGNAL_INSTALL_LOCK,
                            "Install Lock",
                            ZeaHealthStatus.HEALTHY,
                            if (shouldBeActive) {
                                "Install/update protection is active for $protectedCount protected app(s)."
                            } else {
                                "Install lock correctly inactive; nothing is protected."
                            }
                        )
                    } else {
                        ZeaHealthSignal(
                            SIGNAL_INSTALL_LOCK,
                            "Install Lock",
                            ZeaHealthStatus.WARNING,
                            if (shouldBeActive) {
                                "Install lock should be active but is not. Protected apps could be uninstalled."
                            } else {
                                "A stale install lock blocks normal installs although nothing is protected."
                            }
                        )
                    }
                }
            }

            // 10. Launcher state sync (Device Owner only, sampled over registry).
            signals += when {
                !deviceOwnerActive -> ZeaHealthSignal(
                    SIGNAL_LAUNCHER_SYNC,
                    "Launcher Sync",
                    if (deviceOwnerMode) ZeaHealthStatus.WARNING else ZeaHealthStatus.NOT_APPLICABLE,
                    if (deviceOwnerMode) {
                        "Launcher sync cannot be verified without Device Owner."
                    } else {
                        "Launcher hiding is a Device Owner mode feature."
                    }
                )
                protectedCount == 0 -> ZeaHealthSignal(
                    SIGNAL_LAUNCHER_SYNC,
                    "Launcher Sync",
                    ZeaHealthStatus.HEALTHY,
                    "No hidden apps to synchronize."
                )
                else -> {
                    val mismatched = loadPrivateApps(appContext).filter { record ->
                        ZeaDeviceOwnerController.isHidden(
                            appContext,
                            record.packageName
                        ) != true
                    }
                    if (mismatched.isEmpty()) {
                        ZeaHealthSignal(
                            SIGNAL_LAUNCHER_SYNC,
                            "Launcher Sync",
                            ZeaHealthStatus.HEALTHY,
                            "All $protectedCount protected app(s) are hidden from the launcher."
                        )
                    } else {
                        ZeaHealthSignal(
                            SIGNAL_LAUNCHER_SYNC,
                            "Launcher Sync",
                            ZeaHealthStatus.WARNING,
                            "${mismatched.size} of $protectedCount protected app(s) are NOT hidden in the launcher: " +
                                    mismatched.take(3).joinToString(", ") { it.displayName } +
                                    if (mismatched.size > 3) "…" else ""
                        )
                    }
                }
            }

            ZeaProtectionHealthReport(
                signals = signals,
                protectedCount = protectedCount,
                timedCount = timedCount,
                deviceOwnerMode = deviceOwnerMode,
                protectionPaused = protectionPaused
            )
        }

    /**
     * Validates the raw registry payload without mutating anything. Returns
     * null when the registry is readable and schema-valid, otherwise a short
     * human-readable issue description.
     */
    internal fun registryIntegrityIssue(context: Context): String? {
        val raw = try {
            getZeaPrefs(context)
                .getString(ZeaStorageContract.PRIVATE_APPS_JSON, "") ?: ""
        } catch (_: RuntimeException) {
            return "Protected-app registry could not be read."
        }

        if (raw.isBlank()) {
            return null
        }

        return try {
            val parsed = JSONArray(raw)
            val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
            var invalidRecords = 0
            for (index in 0 until parsed.length()) {
                val record = parsed.optJSONObject(index) ?: run {
                    invalidRecords++
                    continue
                }
                val packageName = record.optString("packageName", "")
                if (!packagePattern.matches(packageName)) {
                    invalidRecords++
                }
            }
            if (invalidRecords > 0) {
                "$invalidRecords registry record(s) are invalid and would be dropped by repair."
            } else {
                null
            }
        } catch (_: Exception) {
            "Protected-app registry is corrupted and cannot be parsed."
        }
    }

    /** Builds the platform Settings intent used by dashboard "Fix Now". */
    fun buildFixIntent(context: Context, signal: ZeaHealthSignal): Intent? {
        val action = signal.fixSettingsAction ?: return null
        val intent = Intent(action)
        when (action) {
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM -> {
                intent.data = android.net.Uri.parse("package:${context.packageName}")
            }
            Settings.ACTION_APP_NOTIFICATION_SETTINGS -> {
                intent.putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    context.packageName
                )
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** Notification Manager backed check kept for diagnostics reuse. */
    internal fun areNotificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return false
        return manager.areNotificationsEnabled()
    }
}
