package com.raomuhammadnoman.zea


import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

internal const val ZEA_DEVICE_OWNER_LOG_TAG = "ZeaDeviceOwner"

internal data class ZeaDeviceOwnerUiState(
    val isDeviceOwner: Boolean = false,
    val isAdminActive: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val protectionPaused: Boolean = false,
    val protectionInstallLockActive: Boolean? = null,
    val message: String = ""
)

internal data class ZeaDeviceOwnerOperationResult(
    val success: Boolean,
    val message: String,
    val hidden: Boolean? = null,
    val uninstallBlocked: Boolean? = null
)

internal object ZeaDeviceOwnerController {
    private data class PrivateLaunchSessionPreparation(
        val sessionId: String? = null,
        val rejectionReason: String? = null
    )

    private data class PrivateLaunchHandshakeConsumption(
        val consumed: Boolean,
        val activeMonitorStateRetained: Boolean
    )

    private const val STATE_PREFERENCES = "zea_device_owner_state"
    private const val KEY_ACTIVE_PRIVATE_PACKAGE = "active_private_package"
    private const val KEY_PROTECTION_PAUSED = "protection_paused"
    private const val KEY_PENDING_REHIDE_PACKAGES = "pending_rehide_packages"
    private const val KEY_MONITOR_SESSION = "monitor_session"
    private const val KEY_MONITOR_READY_SESSION = "monitor_ready_session"
    private const val KEY_MONITOR_READY_PACKAGE = "monitor_ready_package"
    private const val KEY_EXPECTED_UNHIDE_PACKAGE = "expected_unhide_package"
    private const val KEY_EXPECTED_UNHIDE_SESSION = "expected_unhide_session"
    private const val KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED =
        "expected_unhide_expires_elapsed"
    private const val KEY_MANUAL_VISIBILITY_PACKAGE = "manual_visibility_package"
    private const val KEY_MANUAL_VISIBILITY_EXPIRES_ELAPSED =
        "manual_visibility_expires_elapsed"

    private const val MONITOR_READY_TIMEOUT_MILLIS = 3_000L
    private const val PRIVATE_FOREGROUND_CONFIRMATION_TIMEOUT_MILLIS = 40_000L
    private const val PRIVATE_LAUNCH_PACKAGE_SETTLE_MILLIS = 750L
    private const val EXPECTED_UNHIDE_PACKAGE_ADDED_WINDOW_MILLIS = 15_000L

    private const val KEY_PRIVATE_LAUNCH_SESSION = "zea_private_launch_session"
    private const val KEY_PRIVATE_LAUNCH_PACKAGE = "zea_private_launch_package"
    private const val KEY_PRIVATE_LAUNCH_DISPATCH_ELAPSED = "zea_private_launch_dispatch_elapsed"
    private const val KEY_PRIVATE_LAUNCH_DISPATCH_WALL = "zea_private_launch_dispatch_wall"
    private const val KEY_PRIVATE_LAUNCH_OUTCOME = "zea_private_launch_outcome"
    private const val KEY_PRIVATE_LAUNCH_FAILURE_REASON = "zea_private_launch_failure_reason"

    private const val PRIVATE_LAUNCH_OUTCOME_PENDING = "pending"
    private const val PRIVATE_LAUNCH_OUTCOME_CONFIRMED = "confirmed"
    private const val PRIVATE_LAUNCH_OUTCOME_FAILED = "failed"
    private const val MONITOR_READY_POLL_MILLIS = 50L

    /**
     * Private launches must outlive whatever composable started them. The
     * hidden-list screen unmounts as soon as the launched app takes the
     * foreground (the auth gate re-arms on ON_STOP), and a screen-bound
     * scope would cancel the monitor mid-transaction, triggering a
     * fail-closed re-hide that kills the app about a second after it opens.
     */
    object ZeaPrivateLaunchDispatcher {
        val scope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private val stateLock = Any()
    private val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    private val alwaysRejectedPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.managedprovisioning",
        "com.google.android.apps.work.clouddpc",
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.dialer"
    )

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, ZeaDeviceAdminReceiver::class.java)

    fun readUiState(context: Context): ZeaDeviceOwnerUiState {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val admin = adminComponent(context)
        val isDeviceOwner = try {
            manager?.isDeviceOwnerApp(context.packageName) == true
        } catch (_: RuntimeException) {
            false
        }
        val isAdminActive = try {
            manager?.isAdminActive(admin) == true
        } catch (_: RuntimeException) {
            false
        }
        val usageAccessGranted = isUsageAccessGranted(context)
        val protectionPaused = isProtectionPaused(context)
        val pendingRecoveryCount = pendingRehidePackages(context).size
        val privateAppCount = loadPrivateApps(context).size
        val installLockActive = if (isDeviceOwner) {
            queryProtectionInstallLock(context)
        } else {
            null
        }

        val message = when {
            !isDeviceOwner ->
                "Device Owner is not active. Mission 008B must be provisioned only on a factory-reset emulator or spare test device. Installing this APK alone does not take device control."
            pendingRecoveryCount > 0 && installLockActive != true ->
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Fail-closed recovery is active, but the Protection Install Lock is not verified. Zyro must restore the user-wide install/update restriction before recovery can be considered safe."
            privateAppCount > 0 && installLockActive != true ->
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Protection Install Lock is not verified while private apps remain configured. Zyro must reconcile the user-wide install/update restriction before full protection is ready."
            protectionPaused && pendingRecoveryCount > 0 ->
                "Emergency pause remains active because $pendingRecoveryCount app(s) still require recovery. Private commands stay blocked, and the Protection Install Lock remains active while private records or emergency recovery entries exist."
            privateAppCount == 0 && pendingRecoveryCount == 0 && installLockActive == true ->
                "A stale Protection Install Lock is active although no private apps or emergency recovery entries remain. Run Reconcile to restore normal Android installs and updates."
            protectionPaused ->
                "Emergency pause is active. Private apps are unhidden and private commands are blocked until protection is resumed. Protection Install Lock remains active while private records exist."
            pendingRecoveryCount > 0 ->
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Fail-closed recovery is active for $pendingRecoveryCount app(s). Zyro preserves the failed identities, keeps the Protection Install Lock active, and retries through the safety monitor and reconciliation paths."
            !usageAccessGranted ->
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Device Owner is active. Grant Zyro Usage Access so it can re-hide a private app after that app leaves the foreground."
            privateAppCount > 0 ->
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Device Owner protection is ready. Added apps are hidden at rest, protected from uninstall, and Android installs/updates are blocked for this user while Zyro private protection remains configured."
            else ->
                "Device Owner protection is ready. No private apps or emergency recovery entries are configured, so the Protection Install Lock is inactive and normal Android installs/updates are available."
        }

        return ZeaDeviceOwnerUiState(
            isDeviceOwner = isDeviceOwner,
            isAdminActive = isAdminActive,
            usageAccessGranted = usageAccessGranted,
            protectionPaused = protectionPaused,
            protectionInstallLockActive = installLockActive,
            message = message
        )
    }

    fun isDeviceOwner(context: Context): Boolean {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return try {
            manager.isDeviceOwnerApp(context.packageName)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun queryProtectionInstallLock(context: Context): Boolean? {
        if (!isDeviceOwner(context)) return null
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return null
        val admin = adminComponent(context)
        return try {
            manager.getUserRestrictions(admin)
                .getBoolean(UserManager.DISALLOW_INSTALL_APPS, false)
        } catch (error: SecurityException) {
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "install-lock query denied", error)
            null
        } catch (error: IllegalArgumentException) {
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "install-lock query rejected", error)
            null
        } catch (error: RuntimeException) {
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "install-lock query failed", error)
            null
        }
    }

    fun setProtectionInstallLock(
        context: Context,
        active: Boolean
    ): ZeaDeviceOwnerOperationResult {
        if (!isDeviceOwner(context)) {
            return ZeaDeviceOwnerOperationResult(
                false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Protection Install Lock requires Zyro to be Device Owner."
            )
        }
        val manager = context.getSystemService(DevicePolicyManager::class.java)
            ?: return ZeaDeviceOwnerOperationResult(
                false,
                "Android Device Policy service is unavailable for the Protection Install Lock."
            )
        val admin = adminComponent(context)
        return try {
            if (active) {
                manager.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            } else {
                manager.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            }
            val confirmed = queryProtectionInstallLock(context)
            val success = confirmed == active
            ZeaDeviceOwnerOperationResult(
                success = success,
                message = when {
                    success && active ->
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        "Protection Install Lock is active. Android installs and updates are blocked for this user while Zyro private protection remains configured."
                    success ->
                        "Protection Install Lock is inactive. Android installs and updates are available for this user."
                    confirmed == null ->
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        "Protection Install Lock changed state could not be verified. Zyro stopped fail-closed."
                    active ->
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        "Protection Install Lock could not be confirmed active. Zyro stopped fail-closed."
                    else ->
                        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                        "Protection Install Lock could not be confirmed inactive. Zyro stopped fail-closed."
                }
            )
        } catch (error: SecurityException) {
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "install-lock policy denied active=$active", error)
            ZeaDeviceOwnerOperationResult(false, "Protection Install Lock policy was denied safely.")
        } catch (error: IllegalArgumentException) {
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "install-lock policy rejected active=$active", error)
            ZeaDeviceOwnerOperationResult(false, "Protection Install Lock policy was rejected safely.")
        } catch (error: RuntimeException) {
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "install-lock policy failed active=$active", error)
            ZeaDeviceOwnerOperationResult(false, "Protection Install Lock policy failed safely.")
        }
    }

    fun reconcileProtectionInstallLock(
        context: Context,
        privateAppCount: Int = loadPrivateApps(context).size
    ): ZeaDeviceOwnerOperationResult {
        if (privateAppCount < 0) {
            return ZeaDeviceOwnerOperationResult(false, "Private-app count is invalid for install-lock reconciliation.")
        }
        // Emergency/pending re-hide identities are still protected state even
        // when the durable private registry is temporarily empty. Never open
        // installs while such recovery work exists.
        val hasPendingRecovery = pendingRehidePackages(context).isNotEmpty()
        val shouldBeActive = privateAppCount > 0 || hasPendingRecovery
        val current = queryProtectionInstallLock(context)
        if (current == shouldBeActive) {
            return ZeaDeviceOwnerOperationResult(
                true,
                if (shouldBeActive) {
                    "Protection Install Lock is already active for $privateAppCount private app(s); pendingRecovery=$hasPendingRecovery."
                } else {
                    "Protection Install Lock is already inactive because no private apps remain."
                }
            )
        }
        return setProtectionInstallLock(context, shouldBeActive)
    }


    private fun effectiveProtectionInstallLockCount(
        context: Context,
        storedPrivateCount: Int
    ): Int {
        if (storedPrivateCount > 0) return storedPrivateCount
        return if (pendingRehidePackages(context).isNotEmpty()) 1 else 0
    }

    fun isUsageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
        } catch (_: RuntimeException) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun createUsageAccessSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun validatePrivateApp(context: Context, record: PrivateAppRecord): String? {
        return validatePackageSafety(
            context = context,
            packageName = record.packageName,
            launcherActivityName = record.launcherActivityName,
            requireLauncherComponent = true
        )
    }

    private fun validateStoredRecordSafety(
        context: Context,
        record: PrivateAppRecord,
        forceLauncherVerification: Boolean = false
    ): String? {
        val hiddenState = queryApplicationHidden(context, record.packageName)
        val requireLauncherComponent = forceLauncherVerification || hiddenState != true
        return validatePackageSafety(
            context = context,
            packageName = record.packageName,
            launcherActivityName = record.launcherActivityName,
            requireLauncherComponent = requireLauncherComponent
        )
    }

    private fun validatePackageSafety(
        context: Context,
        packageName: String,
        launcherActivityName: String? = null,
        requireLauncherComponent: Boolean = false
    ): String? {
        val normalizedPackage = packageName.trim()
        if (!packagePattern.matches(normalizedPackage)) return "The package name is invalid."
        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
        if (normalizedPackage == context.packageName) return "Zyro cannot manage itself."
        if (normalizedPackage in alwaysRejectedPackages) {
            return "This critical Android package cannot be managed."
        }

        val homePackage = resolveCurrentHomePackage(context)
            ?: return "Android could not safely verify the current Home launcher."
        if (normalizedPackage == homePackage) return "The current Home launcher cannot be managed."

        val currentInputMethodPackage = try {
            val inputMethodId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            ComponentName.unflattenFromString(inputMethodId.orEmpty())?.packageName
        } catch (_: RuntimeException) {
            return "Android could not safely verify the current keyboard."
        }
        if (
            currentInputMethodPackage != null &&
            normalizedPackage.equals(currentInputMethodPackage, ignoreCase = true)
        ) {
            return "The currently selected keyboard cannot be managed."
        }

        val defaultDialerPackage = try {
            context.getSystemService(android.telecom.TelecomManager::class.java)
                ?.defaultDialerPackage
        } catch (_: RuntimeException) {
            return "Android could not safely verify the default phone application."
        }
        if (
            defaultDialerPackage != null &&
            normalizedPackage.equals(defaultDialerPackage, ignoreCase = true)
        ) {
            return "The default phone application cannot be managed."
        }

        val defaultSmsPackage = try {
            android.provider.Telephony.Sms.getDefaultSmsPackage(context)
        } catch (_: RuntimeException) {
            return "Android could not safely verify the default messaging application."
        }
        if (
            defaultSmsPackage != null &&
            normalizedPackage.equals(defaultSmsPackage, ignoreCase = true)
        ) {
            return "The default messaging application cannot be managed."
        }

        val manager = context.getSystemService(DevicePolicyManager::class.java)
            ?: return "Device policy service is unavailable."
        val activeAdminPackages = try {
            manager.activeAdmins.orEmpty().map(ComponentName::getPackageName).toSet()
        } catch (_: RuntimeException) {
            return "Android could not safely verify active device administrators."
        }
        if (normalizedPackage in activeAdminPackages) {
            return "Device-admin and device-management apps cannot be managed."
        }

        val packageManager = context.packageManager
        val applicationInfo = try {
            packageManager.getApplicationInfo(
                normalizedPackage,
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                    PackageManager.MATCH_DISABLED_COMPONENTS
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return "The app is not installed in the primary user."
        } catch (_: RuntimeException) {
            return "Android could not safely verify the installed app."
        }

        val isInstalled = applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED != 0
        if (!isInstalled) return "The app is not installed in the primary user."
        if (!applicationInfo.enabled) return "The app is disabled."

        if (requireLauncherComponent) {
            val activityName = launcherActivityName.orEmpty().trim()
            if (activityName.isBlank()) return "The verified launcher component is missing."
            val component = ComponentName(normalizedPackage, activityName)
            val activityInfo = try {
                packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
            } catch (_: PackageManager.NameNotFoundException) {
                return "The stored launcher component is no longer installed."
            } catch (_: RuntimeException) {
                return "Android could not safely verify the stored launcher component."
            }
            if (activityInfo.packageName != normalizedPackage) {
                return "The stored launcher component does not belong to the selected package."
            }
            if (!activityInfo.enabled || !activityInfo.applicationInfo.enabled) {
                return "The stored launcher component is disabled."
            }
        }

        return null
    }


    private fun validateManagedPackageSafety(
        context: Context,
        packageName: String,
        requireStoredLauncherVerification: Boolean = true
    ): String? {
        val storedRecord = loadPrivateApps(context).firstOrNull { record ->
            record.packageName.equals(packageName.trim(), ignoreCase = true)
        }
        return if (storedRecord != null && requireStoredLauncherVerification) {
            validateStoredRecordSafety(context, storedRecord)
        } else {
            validatePackageSafety(context, packageName)
        }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            val applicationInfo = context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
            applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun setHidden(
        context: Context,
        packageName: String,
        hidden: Boolean,
        requireStoredLauncherVerification: Boolean = true
    ): ZeaDeviceOwnerOperationResult {
        if (!isDeviceOwner(context)) {
            if (hidden) markPendingRehidePackage(context, packageName)
            return ZeaDeviceOwnerOperationResult(
                success = false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "Zyro is not the Device Owner on this device."
            )
        }

        validateManagedPackageSafety(
            context = context,
            packageName = packageName,
            requireStoredLauncherVerification = requireStoredLauncherVerification
        )?.let { reason ->
            if (hidden) markPendingRehidePackage(context, packageName)
            return ZeaDeviceOwnerOperationResult(false, reason)
        }

        val manager = context.getSystemService(DevicePolicyManager::class.java)
            ?: return ZeaDeviceOwnerOperationResult(false, "Device policy service is unavailable.")
        val admin = adminComponent(context)

        return try {
            val applied = manager.setApplicationHidden(admin, packageName, hidden)
            val confirmed = manager.isApplicationHidden(admin, packageName)
            val success = confirmed == hidden
            Log.i(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "set hidden package=$packageName requested=$hidden applied=$applied confirmed=$confirmed"
            )
            if (hidden) {
                if (success) clearPendingRehidePackage(context, packageName)
                else markPendingRehidePackage(context, packageName)
            }
            if (success) refreshLauncherAfterVisibilityChange(context)
            ZeaDeviceOwnerOperationResult(
                success = success,
                message = when {
                    success && hidden -> "The app is hidden at rest."
                    success -> "The app is visible again."
                    else -> "Android did not confirm the requested hidden state."
                },
                hidden = confirmed
            )
        } catch (error: SecurityException) {
            if (hidden) markPendingRehidePackage(context, packageName)
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "hide policy rejected package=$packageName", error)
            ZeaDeviceOwnerOperationResult(false, "Android rejected the Device Owner hide policy.")
        } catch (error: IllegalArgumentException) {
            if (hidden) markPendingRehidePackage(context, packageName)
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "hide policy invalid package=$packageName", error)
            ZeaDeviceOwnerOperationResult(false, "The package cannot be managed by this policy.")
        } catch (error: RuntimeException) {
            if (hidden) markPendingRehidePackage(context, packageName)
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "hide policy failed package=$packageName", error)
            ZeaDeviceOwnerOperationResult(false, "The hide policy failed safely on this firmware.")
        }
    }

    /**
     * Some OEM launchers (vivo FunTouch) fail to sync drawer rows on live
     * visibility changes in BOTH directions: hiding leaves a dummy ghost row
     * exposed, and unhiding fails to restore the icon until the launcher's
     * model is rebuilt from scratch. Verified on-device that relaunching the
     * HOME task with FLAG_ACTIVITY_CLEAR_TASK forces that full rebuild without
     * needing any privileged force-stop permission. The launch is debounced so
     * bulk hide/unhide batches trigger a single bounce. While Zyro's own UI is
     * foreground the rebuild is deferred (flag + flush on exit) so toggling
     * never throws the user out of the app mid-operation and result dialogs
     * stay visible; background flows (timed hides, services) rebuild after the
     * debounce directly. Fire-and-forget: failures must never block the toggle
     * result.
     */
    @Volatile
    var uiInForeground: Boolean = false

    @Volatile
    private var launcherRefreshJob: Job? = null

    @Volatile
    private var launcherRefreshPending: Boolean = false

    fun flushPendingLauncherRefresh(context: Context) {
        if (!launcherRefreshPending) return
        launcherRefreshPending = false
        fireLauncherRebuild(context.applicationContext)
    }

    private fun refreshLauncherAfterVisibilityChange(context: Context) {
        launcherRefreshJob?.cancel()
        launcherRefreshJob = ZeaPrivateLaunchDispatcher.scope.launch {
            delay(2000)
            try {
                if (uiInForeground) {
                    launcherRefreshPending = true
                    Log.i(
                        ZEA_DEVICE_OWNER_LOG_TAG,
                        "launcher refresh deferred while Zyro is foreground"
                    )
                    return@launch
                }
                fireLauncherRebuild(context)
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun fireLauncherRebuild(context: Context) {
        try {
            val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val homePackage = context.packageManager.resolveActivity(
                probe,
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            if (homePackage.isNullOrEmpty() || homePackage == context.packageName) {
                return
            }
            try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                    }
                )
                Log.i(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "launcher task cleared after visibility change package=$homePackage"
                )
                return
            } catch (_: RuntimeException) {
            }
            try {
                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                activityManager?.killBackgroundProcesses(homePackage)
                Log.i(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "launcher refreshed via fallback package=$homePackage"
                )
            } catch (_: RuntimeException) {
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun queryApplicationHidden(context: Context, packageName: String): Boolean? {
        if (!isDeviceOwner(context)) return null
        val normalizedPackage = packageName.trim()
        if (!packagePattern.matches(normalizedPackage)) return null
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return null
        return try {
            manager.isApplicationHidden(adminComponent(context), normalizedPackage)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun isHidden(context: Context, packageName: String): Boolean? {
        if (validatePackageSafety(context, packageName) != null) return null
        return queryApplicationHidden(context, packageName)
    }

    private fun queryUninstallBlocked(context: Context, packageName: String): Boolean? {
        if (!isDeviceOwner(context)) return null
        val normalizedPackage = packageName.trim()
        if (!packagePattern.matches(normalizedPackage)) return null
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return null
        return try {
            manager.isUninstallBlocked(adminComponent(context), normalizedPackage)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun isUninstallBlocked(context: Context, packageName: String): Boolean? {
        if (validatePackageSafety(context, packageName) != null) return null
        return queryUninstallBlocked(context, packageName)
    }

    fun setUninstallBlocked(
        context: Context,
        packageName: String,
        blocked: Boolean,
        requireStoredLauncherVerification: Boolean = true
    ): ZeaDeviceOwnerOperationResult {
        if (!isDeviceOwner(context)) {
            if (blocked) markPendingRehidePackage(context, packageName)
            return ZeaDeviceOwnerOperationResult(
                success = false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "Zyro is not the Device Owner on this device."
            )
        }

        validateManagedPackageSafety(
            context = context,
            packageName = packageName,
            requireStoredLauncherVerification = requireStoredLauncherVerification
        )?.let { reason ->
            if (blocked) markPendingRehidePackage(context, packageName)
            return ZeaDeviceOwnerOperationResult(false, reason)
        }

        val manager = context.getSystemService(DevicePolicyManager::class.java)
            ?: return ZeaDeviceOwnerOperationResult(false, "Device policy service is unavailable.")
        val admin = adminComponent(context)

        return try {
            manager.setUninstallBlocked(admin, packageName, blocked)
            val confirmed = manager.isUninstallBlocked(admin, packageName)
            val success = confirmed == blocked
            Log.i(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "set uninstall blocked package=$packageName requested=$blocked confirmed=$confirmed"
            )
            if (blocked && !success) {
                markPendingRehidePackage(context, packageName)
            }
            ZeaDeviceOwnerOperationResult(
                success = success,
                message = when {
                    success && blocked -> "Package-specific uninstall protection is active."
                    success -> "Package-specific uninstall protection was removed."
                    else -> "Android did not confirm the requested uninstall-protection state."
                },
                uninstallBlocked = confirmed
            )
        } catch (error: SecurityException) {
            if (blocked) markPendingRehidePackage(context, packageName)
            Log.e(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "uninstall-block policy rejected package=$packageName",
                error
            )
            ZeaDeviceOwnerOperationResult(
                false,
                "Android rejected the Device Owner uninstall-protection policy."
            )
        } catch (error: IllegalArgumentException) {
            if (blocked) markPendingRehidePackage(context, packageName)
            Log.e(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "uninstall-block policy invalid package=$packageName",
                error
            )
            ZeaDeviceOwnerOperationResult(false, "The package cannot be managed by this policy.")
        } catch (error: RuntimeException) {
            if (blocked) markPendingRehidePackage(context, packageName)
            Log.e(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "uninstall-block policy failed package=$packageName",
                error
            )
            ZeaDeviceOwnerOperationResult(
                false,
                "The uninstall-protection policy failed safely on this firmware."
            )
        }
    }

    fun ensureProtectedState(
        context: Context,
        packageName: String,
        requireStoredLauncherVerification: Boolean = true
    ): ZeaDeviceOwnerOperationResult {
        val storedPrivateCount = loadPrivateApps(context).size
        val effectiveProtectionCount = effectiveProtectionInstallLockCount(context, storedPrivateCount)
        if (effectiveProtectionCount > 0) {
            val installLockResult = reconcileProtectionInstallLock(context, effectiveProtectionCount)
            if (!installLockResult.success) {
                markPendingRehidePackage(context, packageName)
                return ZeaDeviceOwnerOperationResult(
                    false,
                    "Complete protected state was not applied because the Protection Install Lock could not be verified. ${installLockResult.message}"
                )
            }
        }

        val uninstallResult = setUninstallBlocked(
            context = context,
            packageName = packageName,
            blocked = true,
            requireStoredLauncherVerification = requireStoredLauncherVerification
        )
        val hideResult = setHidden(
            context = context,
            packageName = packageName,
            hidden = true,
            requireStoredLauncherVerification = requireStoredLauncherVerification
        )

        val hiddenConfirmed = hideResult.hidden ?: queryApplicationHidden(context, packageName)
        val uninstallBlockedConfirmed = uninstallResult.uninstallBlocked
            ?: queryUninstallBlocked(context, packageName)
        val success = hideResult.success &&
            uninstallResult.success &&
            hiddenConfirmed == true &&
            uninstallBlockedConfirmed == true

        if (success) {
            clearPendingRehidePackage(context, packageName)
        } else {
            markPendingRehidePackage(context, packageName)
        }

        return ZeaDeviceOwnerOperationResult(
            success = success,
            message = when {
                success -> "The app is hidden at rest and protected from uninstall."
                hideResult.success && !uninstallResult.success ->
                    "The app is hidden, but package-specific uninstall protection was not confirmed."
                !hideResult.success && uninstallResult.success ->
                    "Uninstall protection is active, but hidden-at-rest state was not confirmed."
                else ->
                    "Android did not confirm the complete protected state. Fail-closed recovery remains active."
            },
            hidden = hiddenConfirmed,
            uninstallBlocked = uninstallBlockedConfirmed
        )
    }

    private fun startPrivateLauncherActivity(
        context: Context,
        component: ComponentName
    ): String {
        // The private session now intentionally persists after the user
        // leaves the app, so the recents slide keeps working. Only NEW_TASK
        // is set: EXCLUDE_FROM_RECENTS would hide the slide entirely and
        // NO_HISTORY would finish the activity as soon as it left the
        // foreground. The session boundary is screen-off/shutdown/max
        // duration, enforced by the session monitor.
        val flaggedIntent = Intent.makeMainActivity(component).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(flaggedIntent)
            return "intent_new_task_session_persistent"
        } catch (error: RuntimeException) {
            Log.w(
                ZEA_DEVICE_OWNER_LOG_TAG,
                "Flagged private launch rejected; using LauncherApps fallback",
                error
            )
        }

        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (launcherApps != null) {
            try {
                launcherApps.startMainActivity(
                    component,
                    Process.myUserHandle(),
                    null,
                    null
                )
                return "launcher_apps_current_user"
            } catch (error: SecurityException) {
                Log.w(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "LauncherApps private launch rejected; using explicit MAIN fallback",
                    error
                )
            } catch (error: RuntimeException) {
                Log.w(
                    ZEA_DEVICE_OWNER_LOG_TAG,
                    "LauncherApps private launch failed; using explicit MAIN fallback",
                    error
                )
            }
        }

        val fallbackIntent = Intent.makeMainActivity(component).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
        return "intent_make_main_activity"
    }

    suspend fun launchPrivateApp(
        context: Context,
        record: PrivateAppRecord
    ): ZeaDeviceOwnerOperationResult {
        if (!isDeviceOwner(context)) {
            // App Lock mode: no policy unhide is needed or possible; the
            // accessibility engine simply allows this package for a session.
            val recordPresent = loadPrivateApps(context).any { stored ->
                stored.packageName.equals(record.packageName, ignoreCase = true)
            }
            if (!recordPresent) {
                return ZeaDeviceOwnerOperationResult(
                    false,
                    "Private launch stopped because the Zyro protection record is no longer present."
                )
            }
            val launchIntent = context.packageManager.getLaunchIntentForPackage(record.packageName)
                ?: return ZeaDeviceOwnerOperationResult(
                    false,
                    "${record.displayName} has no openable screen on this device."
                )
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ZeaLockMode.armSessionAllow(context, record.packageName)
            return try {
                context.startActivity(launchIntent)
                ZeaDeviceOwnerOperationResult(
                    success = true,
                    message = "${record.displayName} opened in App Lock mode. It locks again automatically after you leave it."
                )
            } catch (_: RuntimeException) {
                ZeaLockMode.clearSessionAllow(context, record.packageName)
                ZeaDeviceOwnerOperationResult(
                    false,
                    "${record.displayName} could not be opened."
                )
            }
        }
        if (isProtectionPaused(context)) {
            return ZeaDeviceOwnerOperationResult(false, "Private protection is paused. Resume protection first.")
        }
        if (!isUsageAccessGranted(context)) {
            return ZeaDeviceOwnerOperationResult(
                false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Usage Access is required before Zyro can safely open and automatically re-hide a private app."
            )
        }
        validateStoredRecordSafety(context, record)?.let { reason ->
            return ZeaDeviceOwnerOperationResult(false, reason)
        }

        val currentRecords = loadPrivateApps(context)
        val recordStillPresent = currentRecords.any { stored ->
            stored.packageName.equals(record.packageName, ignoreCase = true)
        }
        if (!recordStillPresent) {
            return ZeaDeviceOwnerOperationResult(
                false,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                "Private launch stopped because the Zyro protection record is no longer present."
            )
        }
        val installLockResult = reconcileProtectionInstallLock(context, currentRecords.size)
        if (!installLockResult.success) {
            return ZeaDeviceOwnerOperationResult(
                false,
                "Private launch stopped because the Protection Install Lock is not verified. ${installLockResult.message}"
            )
        }

        val uninstallProtection = setUninstallBlocked(
            context = context,
            packageName = record.packageName,
            blocked = true
        )
        if (!uninstallProtection.success) {
            return ZeaDeviceOwnerOperationResult(
                success = false,
                message = "Private launch stopped because package-specific uninstall protection was not confirmed. ${uninstallProtection.message}",
                uninstallBlocked = uninstallProtection.uninstallBlocked
            )
        }

        val sessionPreparation = tryPreparePrivateLaunchSession(
            context = context,
            packageName = record.packageName
        )
        val sessionId = sessionPreparation.sessionId
            ?: return ZeaDeviceOwnerOperationResult(
                false,
                sessionPreparation.rejectionReason
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    ?: "Zyro could not reserve the private app session safely. No app was unhidden."
            )
        ZeaPrivateSessionDiagnosticLedger.record(
            context = context,
            sessionId = sessionId,
            eventCode = "SESSION_PREPARED",
            targetPackage = record.packageName,
            state = "prepared=true",
            reason = "controller_prepare_monitor_session"
        )
        val lifecycleExpectationArmed = armExpectedUnhidePackageAdded(
            context = context,
            sessionId = sessionId,
            packageName = record.packageName
        )
        ZeaPrivateSessionDiagnosticLedger.record(
            context = context,
            sessionId = sessionId,
            eventCode = "UNHIDE_LIFECYCLE_EXPECTATION_ARMED",
            targetPackage = record.packageName,
            state = "armed=$lifecycleExpectationArmed;windowMs=$EXPECTED_UNHIDE_PACKAGE_ADDED_WINDOW_MILLIS",
            reason = "controller_prepare_unhide"
        )
        if (!lifecycleExpectationArmed) {
            val recovery = ensureProtectedState(context, record.packageName)
            if (recovery.success) {
                clearActivePrivatePackage(context)
                clearMonitorSession(context, sessionId)
            }
            return ZeaDeviceOwnerOperationResult(
                success = false,
                message = if (recovery.success) {
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    "Zyro could not reserve the expected unhide lifecycle callback, so the app remained protected."
                } else {
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    "Zyro could not reserve the expected unhide lifecycle callback. Fail-closed recovery remains active."
                },
                hidden = recovery.hidden,
                uninstallBlocked = recovery.uninstallBlocked
            )
        }

        ZeaPrivateSessionDiagnosticLedger.record(
            context = context,
            sessionId = sessionId,
            eventCode = "UNHIDE_REQUESTED",
            targetPackage = record.packageName,
            state = "requestedHidden=false",
            reason = "private_launch"
        )
        val unhideResult = setHidden(context, record.packageName, false)
        ZeaPrivateSessionDiagnosticLedger.record(
            context = context,
            sessionId = sessionId,
            eventCode = "UNHIDE_RESULT",
            targetPackage = record.packageName,
            state = "success=${unhideResult.success};hidden=${unhideResult.hidden}",
            reason = "private_launch"
        )
        if (!unhideResult.success) {
            clearExpectedUnhidePackageAdded(context, sessionId, record.packageName)
            val recovery = ensureProtectedState(context, record.packageName)
            if (recovery.success) {
                clearActivePrivatePackage(context)
                clearMonitorSession(context, sessionId)
            }
            return ZeaDeviceOwnerOperationResult(
                false,
                if (recovery.success) {
                    "The app could not be safely unhidden and remains hidden."
                } else {
                    "The app could not be safely unhidden. Fail-closed recovery remains active."
                },
                hidden = recovery.hidden
            )
        }

        validateStoredRecordSafety(
            context = context,
            record = record,
            forceLauncherVerification = true
        )?.let { reason ->
            clearExpectedUnhidePackageAdded(context, sessionId, record.packageName)
            val recovery = ensureProtectedState(context, record.packageName)
            if (recovery.success) {
                clearActivePrivatePackage(context)
                clearMonitorSession(context, sessionId)
            }
            return ZeaDeviceOwnerOperationResult(
                false,
                if (recovery.success) {
                    "$reason The app was hidden again before launch."
                } else {
                    "$reason Fail-closed recovery remains active."
                },
                hidden = recovery.hidden
            )
        }

        ZeaPrivateSessionDiagnosticLedger.record(
            context = context,
            sessionId = sessionId,
            eventCode = "MONITOR_START_REQUESTED",
            targetPackage = record.packageName,
            state = "requested=true",
            reason = "controller_start_service"
        )
        val serviceStarted = ZeaPrivateSessionMonitorService.start(
            context = context,
            packageName = record.packageName,
            displayName = record.displayName,
            sessionId = sessionId
        )
        if (!serviceStarted || !awaitMonitorReady(context, sessionId, record.packageName)) {
            clearExpectedUnhidePackageAdded(context, sessionId, record.packageName)
            ZeaPrivateSessionMonitorService.stop(context)
            val recovery = ensureProtectedState(context, record.packageName)
            if (recovery.success) {
                clearActivePrivatePackage(context)
                clearMonitorSession(context, sessionId)
            }
            return ZeaDeviceOwnerOperationResult(
                false,
                if (recovery.success) {
                    "The safety monitor did not become ready, so the app was hidden again."
                } else {
                    "The safety monitor did not become ready. Fail-closed recovery remains active."
                },
                hidden = recovery.hidden
            )
        }

        delay(PRIVATE_LAUNCH_PACKAGE_SETTLE_MILLIS)
        val launcherComponent = ComponentName(
            record.packageName,
            record.launcherActivityName
        )
        ZeaPrivateSessionDiagnosticLedger.record(
            context = context,
            sessionId = sessionId,
            eventCode = "LAUNCH_INTENT_CREATED",
            targetPackage = record.packageName,
            state =
                "launcherAppsPreferred=true;fallback=makeMainActivity;" +
                    "settleMs=$PRIVATE_LAUNCH_PACKAGE_SETTLE_MILLIS",
            reason = "controller_launcher_semantics"
        )

        return try {
            val launchStateSaved = markPrivateLaunchDispatched(
                context = context,
                sessionId = sessionId,
                packageName = record.packageName
            )

            if (!launchStateSaved) {
                throw IllegalStateException(
                    "Private launch state could not be recorded."
                )
            }

            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = sessionId,
                eventCode = "DISPATCH_RECORDED",
                targetPackage = record.packageName,
                state = "saved=true",
                reason = "controller_dispatch_state"
            )
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = sessionId,
                eventCode = "START_ACTIVITY_CALLED",
                targetPackage = record.packageName,
                state = "called=true",
                reason = "controller_start_activity"
            )
            val launchStrategy = startPrivateLauncherActivity(
                context = context,
                component = launcherComponent
            )
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = sessionId,
                eventCode = "START_ACTIVITY_RETURNED",
                targetPackage = record.packageName,
                state = "returned=true;strategy=$launchStrategy",
                reason = "controller_start_activity"
            )
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = sessionId,
                eventCode = "CONTROLLER_AWAIT_STARTED",
                targetPackage = record.packageName,
                state = "timeoutMs=$PRIVATE_FOREGROUND_CONFIRMATION_TIMEOUT_MILLIS",
                reason = "controller_wait_for_monitor"
            )

            val foregroundResult = awaitPrivateForegroundConfirmation(
                context = context,
                sessionId = sessionId,
                packageName = record.packageName
            )

            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = sessionId,
                eventCode = when {
                    foregroundResult.confirmed -> "CONTROLLER_AWAIT_CONFIRMED"
                    foregroundResult.failureReason ==
                        "Private foreground confirmation timed out." ->
                        "CONTROLLER_AWAIT_TIMEOUT"
                    else -> "CONTROLLER_AWAIT_FAILED"
                },
                targetPackage = record.packageName,
                state = "confirmed=${foregroundResult.confirmed}",
                reason = foregroundResult.failureReason.orEmpty().ifBlank {
                    "monitor_confirmation_observed"
                }
            )

            if (foregroundResult.confirmed) {
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = context,
                    sessionId = sessionId,
                    eventCode = "TASK_CLEANUP_STARTED",
                    targetPackage = record.packageName,
                    state = "started=true",
                    reason = "confirmed_private_launch"
                )
                // Zea's own task stays in recents so the assistant slide and
                // the launched app slide can be used side by side.
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = context,
                    sessionId = sessionId,
                    eventCode = "TASK_CLEANUP_FINISHED",
                    targetPackage = record.packageName,
                    state = "assistantTaskRetained=true",
                    reason = "confirmed_private_launch"
                )
                val handshakeConsumption =
                    consumeConfirmedPrivateLaunchOutcome(
                        context = context,
                        sessionId = sessionId,
                        packageName = record.packageName
                    )
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = context,
                    sessionId = sessionId,
                    eventCode = "CONTROLLER_HANDSHAKE_CONSUMED",
                    targetPackage = record.packageName,
                    state =
                        "consumed=${handshakeConsumption.consumed};" +
                            "activeMonitorStateRetained=${handshakeConsumption.activeMonitorStateRetained}",
                    reason = "confirmed_private_launch"
                )
                if (!handshakeConsumption.consumed) {
                    throw IllegalStateException(
                        "Confirmed private launch handshake could not be consumed safely."
                    )
                }
                if (handshakeConsumption.activeMonitorStateRetained) {
                    val retainedDispatchElapsed =
                        privateLaunchDispatchedElapsedRealtime(
                            context = context,
                            packageName = record.packageName
                        )
                    val retainedDispatchWall =
                        privateLaunchDispatchedWallClockMillis(
                            context = context,
                            packageName = record.packageName
                        )
                    val dispatchStateRetained =
                        retainedDispatchElapsed != null &&
                            retainedDispatchWall != null
                    ZeaPrivateSessionDiagnosticLedger.record(
                        context = context,
                        sessionId = sessionId,
                        eventCode = "ACTIVE_MONITOR_STATE_RETENTION_CHECK",
                        targetPackage = record.packageName,
                        state =
                            "dispatchStateRetained=$dispatchStateRetained;" +
                                "dispatchElapsed=${retainedDispatchElapsed ?: 0L};" +
                                "dispatchWall=${retainedDispatchWall ?: 0L}",
                        reason = "controller_handshake_consumed"
                    )
                    if (!dispatchStateRetained) {
                        throw IllegalStateException(
                            "Active private monitor timing state was lost after controller confirmation."
                        )
                    }
                }
                ZeaDeviceOwnerOperationResult(
                    success = true,
                    message = "Opening private ${record.displayName}. It will be hidden again after leaving the foreground.",
                    hidden = false
                )
            } else {
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = context,
                    sessionId = sessionId,
                    eventCode = "FAIL_CLOSED_REHIDE",
                    targetPackage = record.packageName,
                    state = "controllerAwaitConfirmed=false",
                    reason = foregroundResult.failureReason.orEmpty().ifBlank {
                        "controller_await_failed"
                    }
                )
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = context,
                    sessionId = sessionId,
                    eventCode = "REHIDE_REQUESTED",
                    targetPackage = record.packageName,
                    state = "requestedHidden=true",
                    reason = "controller_await_failed"
                )
                val recovery = ensureProtectedState(context, record.packageName)
                ZeaPrivateSessionDiagnosticLedger.record(
                    context = context,
                    sessionId = sessionId,
                    eventCode = "REHIDE_RESULT",
                    targetPackage = record.packageName,
                    state = "success=${recovery.success};hidden=${recovery.hidden};uninstallBlocked=${recovery.uninstallBlocked}",
                    reason = "controller_await_failed"
                )

                if (recovery.success) {
                    ZeaPrivateSessionMonitorService.stop(context)
                    clearActivePrivatePackage(context)
                    clearMonitorSession(context, sessionId)
                } else {
                    markPendingRehidePackage(context, record.packageName)
                }

                clearPrivateLaunchOutcome(
                    context = context,
                    sessionId = sessionId,
                    packageName = record.packageName
                )
                ZeaDeviceOwnerOperationResult(
                    success = false,
                    message = if (recovery.success) {
                        "The private app did not reach a stable foreground and was hidden again."
                    } else {
                        "The private app did not reach the foreground. Fail-closed recovery remains active."
                    },
                    hidden = recovery.hidden
                )
            }
        } catch (error: RuntimeException) {
            clearExpectedUnhidePackageAdded(context, sessionId, record.packageName)
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = sessionId,
                eventCode = "FAIL_CLOSED_REHIDE",
                targetPackage = record.packageName,
                state = "runtimeFailure=true",
                reason = "controller_runtime_failure"
            )
            Log.e(ZEA_DEVICE_OWNER_LOG_TAG, "private launch failed package=${record.packageName}", error)
            ZeaPrivateSessionMonitorService.stop(context)
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = sessionId,
                eventCode = "REHIDE_REQUESTED",
                targetPackage = record.packageName,
                state = "requestedHidden=true",
                reason = "controller_runtime_failure"
            )
            val recovery = ensureProtectedState(context, record.packageName)
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = sessionId,
                eventCode = "REHIDE_RESULT",
                targetPackage = record.packageName,
                state = "success=${recovery.success};hidden=${recovery.hidden};uninstallBlocked=${recovery.uninstallBlocked}",
                reason = "controller_runtime_failure"
            )
            if (recovery.success) {
                clearActivePrivatePackage(context)
                clearMonitorSession(context, sessionId)
            }
            ZeaDeviceOwnerOperationResult(
                false,
                if (recovery.success) {
                    "The app could not be opened and was hidden again."
                } else {
                    "The app could not be opened. Fail-closed recovery remains active."
                },
                hidden = recovery.hidden
            )
        }
    }

    fun reconcileProtectedPackageLifecycle(
        context: Context,
        packageName: String,
        eventAction: String,
        packageReplacing: Boolean = false
    ): ZeaDeviceOwnerOperationResult {
        val normalizedPackage = packageName.trim()
        if (!packagePattern.matches(normalizedPackage)) {
            return ZeaDeviceOwnerOperationResult(
                success = false,
                message = "The package lifecycle event did not contain a valid package."
            )
        }

        if (eventAction == Intent.ACTION_PACKAGE_REMOVED && !packageReplacing) {
            return removeUninstalledPackageBookkeeping(context, normalizedPackage, eventAction)
        }

        val currentRecords = loadPrivateApps(context)
        val effectiveProtectionCount = effectiveProtectionInstallLockCount(context, currentRecords.size)
        if (effectiveProtectionCount > 0) {
            val installLockResult = reconcileProtectionInstallLock(context, effectiveProtectionCount)
            if (!installLockResult.success) {
                return ZeaDeviceOwnerOperationResult(
                    false,
                    "Package lifecycle recovery stopped because the Protection Install Lock could not be verified. ${installLockResult.message}"
                )
            }
        }

        val record = currentRecords.firstOrNull { stored ->
            stored.packageName.equals(normalizedPackage, ignoreCase = true)
        } ?: return ZeaDeviceOwnerOperationResult(
            success = true,
            message = "The package lifecycle event did not target a protected app."
        )

        val lifecycleEventId = UUID.randomUUID().toString()
        val reason = eventAction.ifBlank { "package lifecycle" }

        if (consumeManualVisibilityWindow(context, record.packageName)) {
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = lifecycleEventId,
                eventCode = "PACKAGE_LIFECYCLE_REHIDE_SKIPPED_MANUAL_WINDOW",
                targetPackage = record.packageName,
                state = "replacing=$packageReplacing",
                reason = reason
            )
            return ZeaDeviceOwnerOperationResult(
                success = true,
                message = "The visibility broadcast landed inside a manual unhide window, so the app stays visible.",
                hidden = queryApplicationHidden(context, record.packageName),
                uninstallBlocked = queryUninstallBlocked(context, record.packageName)
            )
        }

        if (isProtectionPaused(context)) {
            val uninstallProtection = setUninstallBlocked(
                context = context,
                packageName = record.packageName,
                blocked = true,
                requireStoredLauncherVerification = false
            )
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = lifecycleEventId,
                eventCode = "PACKAGE_LIFECYCLE_REHIDE_SKIPPED_PROTECTION_PAUSED",
                targetPackage = record.packageName,
                state =
                    "replacing=$packageReplacing;uninstallBlocked=${uninstallProtection.uninstallBlocked}",
                reason = reason
            )
            return ZeaDeviceOwnerOperationResult(
                success = uninstallProtection.success,
                message = if (uninstallProtection.success) {
                    "Protection is paused, so the protected app remains visible while uninstall protection stays active."
                } else {
                    "Protection is paused, but uninstall protection could not be confirmed."
                },
                hidden = queryApplicationHidden(context, record.packageName),
                uninstallBlocked = uninstallProtection.uninstallBlocked
            )
        }

        val expectedPrivateUnhide = shouldDeferExpectedUnhidePackageAdded(
            context = context,
            packageName = record.packageName,
            eventAction = eventAction,
            packageReplacing = packageReplacing
        )
        if (expectedPrivateUnhide) {
            val observedHidden = queryApplicationHidden(
                context = context,
                packageName = record.packageName
            )
            val observedUninstallBlocked = queryUninstallBlocked(
                context = context,
                packageName = record.packageName
            )
            ZeaPrivateSessionDiagnosticLedger.record(
                context = context,
                sessionId = lifecycleEventId,
                eventCode = "PACKAGE_LIFECYCLE_REHIDE_DEFERRED_ACTIVE_SESSION",
                targetPackage = record.packageName,
                state =
                    "event=PACKAGE_ADDED;replacing=$packageReplacing;expectedWindow=true;" +
                        "observedHidden=$observedHidden;" +
                        "observedUninstallBlocked=$observedUninstallBlocked",
                reason = reason
            )
            return ZeaDeviceOwnerOperationResult(
                success = true,
                message =
                    "The package-added callback matches the exact expected private-unhide window. " +
                        "The controller transaction remains responsible for validating visibility, " +
                        "launcher readiness, monitor readiness, and fail-closed restoration.",
                hidden = observedHidden,
                uninstallBlocked = observedUninstallBlocked
            )
        }

        ZeaPrivateSessionDiagnosticLedger.record(
            context = context,
            sessionId = lifecycleEventId,
            eventCode = "PACKAGE_LIFECYCLE_REHIDE_REQUESTED",
            targetPackage = record.packageName,
            state = "requestedHidden=true",
            reason = reason
        )

        val result = ensureProtectedState(
            context = context,
            packageName = record.packageName,
            requireStoredLauncherVerification = false
        )

        ZeaPrivateSessionDiagnosticLedger.record(
            context = context,
            sessionId = lifecycleEventId,
            eventCode = "PACKAGE_LIFECYCLE_REHIDE_RESULT",
            targetPackage = record.packageName,
            state = "success=${result.success};hidden=${result.hidden};uninstallBlocked=${result.uninstallBlocked}",
            reason = reason
        )

        return ZeaDeviceOwnerOperationResult(
            success = result.success,
            message = if (result.success) {
                "The protected app was restored to hidden-at-rest and uninstall-blocked state."
            } else {
                "The complete hidden-at-rest and uninstall-blocked state was not confirmed. Fail-closed recovery remains active."
            },
            hidden = result.hidden,
            uninstallBlocked = result.uninstallBlocked
        )
    }

    fun reconcileUninstallProtection(
        context: Context,
        reason: String
    ): ZeaDeviceOwnerOperationResult {
        if (!isDeviceOwner(context)) {
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            return ZeaDeviceOwnerOperationResult(false, "Zyro is not the Device Owner.")
        }

        val records = loadPrivateApps(context)
        val effectiveProtectionCount = effectiveProtectionInstallLockCount(context, records.size)
        val installLockResult = reconcileProtectionInstallLock(context, effectiveProtectionCount)
        if (!installLockResult.success) {
            return ZeaDeviceOwnerOperationResult(
                false,
                "Uninstall-protection reconciliation stopped because the Protection Install Lock could not be verified. ${installLockResult.message}"
            )
        }
        var protectedCount = 0
        val failures = linkedMapOf<String, String>()

        records.forEach { record ->
            if (!isPackageInstalled(context, record.packageName)) {
                clearPendingRehidePackage(context, record.packageName)
                return@forEach
            }

            validateStoredRecordSafety(context, record)?.let { validation ->
                failures[record.packageName] = "${record.displayName}: $validation"
                markPendingRehidePackage(context, record.packageName)
                return@forEach
            }

            val result = setUninstallBlocked(
                context = context,
                packageName = record.packageName,
                blocked = true
            )
            if (result.success) {
                protectedCount += 1
            } else {
                failures[record.packageName] = "${record.displayName}: ${result.message}"
                markPendingRehidePackage(context, record.packageName)
            }
        }

        val message = buildString {
            append(
                "Uninstall-protection reconciliation completed: " +
                    "$protectedCount/${records.size} target(s)."
            )
            if (failures.isNotEmpty()) {
                append(" Package-specific uninstall protection is still required for: ")
                append(failures.values.joinToString("; "))
                append('.')
            }
            append(" Reason: $reason.")
        }
        return ZeaDeviceOwnerOperationResult(
            success = failures.isEmpty(),
            message = message
        )
    }

    fun reconcileHiddenState(context: Context, reason: String): ZeaDeviceOwnerOperationResult {
        return reconcileHiddenStateInternal(
            context = context,
            reason = reason,
            allowWhilePaused = false
        )
    }

    private fun removeUninstalledPackageBookkeeping(
        context: Context,
        packageName: String,
        reason: String
    ): ZeaDeviceOwnerOperationResult {
        val recordsBefore = loadPrivateApps(context)
        val timersBefore = loadTimedHides(context)
        val hadPrivate = recordsBefore.any { record ->
            record.packageName.equals(packageName, ignoreCase = true)
        }
        val hadTimer = timersBefore.any { record ->
            record.packageName.equals(packageName, ignoreCase = true)
        }

        val privateSaved = if (hadPrivate) {
            savePrivateApps(
                context,
                recordsBefore.filterNot { record ->
                    record.packageName.equals(packageName, ignoreCase = true)
                }
            )
        } else {
            true
        }
        val timerSaved = if (hadTimer) {
            ZeaTimedHide.cancel(context, packageName)
            saveTimedHides(
                context,
                timersBefore.filterNot { record ->
                    record.packageName.equals(packageName, ignoreCase = true)
                }
            )
        } else {
            true
        }

        clearPendingRehidePackage(context, packageName)
        if (activePrivatePackage(context).equals(packageName, ignoreCase = true)) {
            clearActivePrivatePackage(context)
            clearMonitorSession(context)
            ZeaPrivateSessionMonitorService.stop(context)
        }
        ZeaAppCatalog.invalidateCatalogCache()

        val remaining = loadPrivateApps(context).size
        val lockResult = reconcileProtectionInstallLock(context, remaining)
        val success = privateSaved && timerSaved && lockResult.success
        return ZeaDeviceOwnerOperationResult(
            success = success,
            message = if (success) {
                "Removed stale protection bookkeeping for uninstalled package $packageName. Reason: $reason."
            } else {
                "Uninstalled package cleanup did not fully persist for $packageName. privateSaved=$privateSaved timerSaved=$timerSaved. ${lockResult.message}"
            }
        )
    }

    private fun pruneUninstalledProtectionRecords(
        context: Context
    ): ZeaDeviceOwnerOperationResult {
        val records = loadPrivateApps(context)
        val stale = records.filterNot { record ->
            isPackageInstalled(context, record.packageName)
        }
        if (stale.isEmpty()) {
            return ZeaDeviceOwnerOperationResult(true, "No stale uninstalled private records found.")
        }

        var allSucceeded = true
        val messages = mutableListOf<String>()
        stale.forEach { record ->
            val result = removeUninstalledPackageBookkeeping(
                context = context,
                packageName = record.packageName,
                reason = "reconciliation_stale_registry"
            )
            if (!result.success) allSucceeded = false
            messages.add(result.message)
        }
        return ZeaDeviceOwnerOperationResult(
            success = allSucceeded,
            message = messages.joinToString(" ")
        )
    }

    private fun reconcileHiddenStateInternal(
        context: Context,
        reason: String,
        allowWhilePaused: Boolean
    ): ZeaDeviceOwnerOperationResult {
        if (!isDeviceOwner(context)) {
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            return ZeaDeviceOwnerOperationResult(false, "Zyro is not the Device Owner.")
        }

        ZeaAppHideService.sweepOrphanedHiddenApps(context, force = true)
        val staleCleanup = pruneUninstalledProtectionRecords(context)
        if (!staleCleanup.success) {
            return ZeaDeviceOwnerOperationResult(
                false,
                "Protected-state reconciliation stopped because stale registry cleanup failed. ${staleCleanup.message}"
            )
        }

        val records = loadPrivateApps(context)
        val pendingAtStart = pendingRehidePackages(context)
        val initialProtectionCount = effectiveProtectionInstallLockCount(context, records.size)
        val installLockResult = reconcileProtectionInstallLock(context, initialProtectionCount)
        if (!installLockResult.success) {
            return ZeaDeviceOwnerOperationResult(
                false,
                "Protected-state reconciliation stopped because the Protection Install Lock could not be verified. ${installLockResult.message}"
            )
        }

        if (isProtectionPaused(context) && !allowWhilePaused) {
            return ZeaDeviceOwnerOperationResult(
                true,
                if (records.isNotEmpty() || pendingAtStart.isNotEmpty()) {
                    "Protection is paused; reconciliation did not hide apps, and the Protection Install Lock remains active while private records or emergency recovery entries exist."
                } else {
                    "Protection is paused; reconciliation did not hide apps, and the Protection Install Lock is inactive because no protected or emergency-recovery state remains."
                }
            )
        }

        val recordsByPackage = records.associateBy { it.packageName }
        val candidatePackages = linkedSetOf<String>().apply {
            addAll(recordsByPackage.keys)
            addAll(pendingAtStart)
            val activePackage = activePrivatePackage(context)
            if (activePackage.isNotBlank()) add(activePackage)
        }

        var hiddenCount = 0
        val failures = linkedMapOf<String, String>()
        val unresolvedPendingWithoutRecord = linkedSetOf<String>()

        candidatePackages.forEach { packageName ->
            if (!isPackageInstalled(context, packageName)) {
                clearPendingRehidePackage(context, packageName)
                return@forEach
            }

            val record = recordsByPackage[packageName]
            val validation = if (record != null) {
                validateStoredRecordSafety(context, record)
            } else {
                validatePackageSafety(context, packageName)
            }
            if (validation != null) {
                val displayName = record?.displayName ?: packageName
                failures[packageName] = "$displayName: $validation"
                markPendingRehidePackage(context, packageName)
                return@forEach
            }

            val result = ensureProtectedState(context, packageName)
            if (result.success) {
                hiddenCount += 1
                if (record == null && pendingAtStart.any { pending ->
                        pending.equals(packageName, ignoreCase = true)
                    }) {
                    // The package is safe at rest, but no durable private record exists.
                    // Keep the emergency identity pending until persistence is recovered.
                    unresolvedPendingWithoutRecord.add(packageName)
                }
            } else {
                val displayName = record?.displayName ?: packageName
                failures[packageName] = "$displayName: ${result.message}"
            }
        }

        val pendingAfter = linkedSetOf<String>().apply {
            addAll(failures.keys)
            addAll(unresolvedPendingWithoutRecord)
        }
        setPendingRehidePackages(context, pendingAfter)

        if (pendingAfter.isEmpty()) {
            clearActivePrivatePackage(context)
            clearMonitorSession(context)
            ZeaPrivateSessionMonitorService.stop(context)
        } else {
            val currentActive = activePrivatePackage(context)
            val recoveryPackage = currentActive.takeIf { it in pendingAfter }
                ?: pendingAfter.first()
            setActivePrivatePackage(context, recoveryPackage)

            if (!isProtectionPaused(context)) {
                val recoveryName = recordsByPackage[recoveryPackage]
                    ?.displayName
                    .orEmpty()
                    .ifBlank { recoveryPackage }
                val sessionId = prepareMonitorSession(context, recoveryPackage)
                ZeaPrivateSessionMonitorService.start(
                    context = context,
                    packageName = recoveryPackage,
                    displayName = recoveryName,
                    sessionId = sessionId
                )
            }
        }

        val finalProtectionCount = if (records.isNotEmpty()) {
            records.size
        } else if (pendingAfter.isNotEmpty()) {
            1
        } else {
            0
        }
        val finalInstallLock = reconcileProtectionInstallLock(context, finalProtectionCount)
        val success =
            failures.isEmpty() && unresolvedPendingWithoutRecord.isEmpty() && finalInstallLock.success
        val message = buildString {
            append(
                "Protected-state reconciliation completed: " +
                    "$hiddenCount/${candidatePackages.size} target(s) are hidden and uninstall-blocked."
            )
            if (failures.isNotEmpty()) {
                append(" Fail-closed recovery is still required for: ")
                append(failures.values.joinToString("; "))
                append('.')
            }
            if (unresolvedPendingWithoutRecord.isNotEmpty()) {
                append(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    " Emergency recovery remains active because durable Zyro records are still missing for: "
                )
                append(unresolvedPendingWithoutRecord.joinToString(", "))
                append('.')
            }
            if (!finalInstallLock.success) {
                append(" Protection Install Lock final-state verification failed.")
            }
            append(" Reason: $reason.")
        }
        return ZeaDeviceOwnerOperationResult(success, message)
    }

    fun unhideAllAndPause(context: Context): ZeaDeviceOwnerOperationResult {
        if (!isDeviceOwner(context)) {
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            return ZeaDeviceOwnerOperationResult(false, "Zyro is not the Device Owner.")
        }

        val records = loadPrivateApps(context)
        val pendingAtStart = pendingRehidePackages(context)
        val effectiveProtectionCount = effectiveProtectionInstallLockCount(context, records.size)
        val installLockResult = reconcileProtectionInstallLock(context, effectiveProtectionCount)
        if (!installLockResult.success) {
            return ZeaDeviceOwnerOperationResult(
                false,
                "Emergency pause stopped because the Protection Install Lock could not be verified first. ${installLockResult.message}"
            )
        }

        setProtectionPaused(context, true)
        ZeaPrivateSessionMonitorService.stop(context)

        val recordsByPackage = records.associateBy { it.packageName }
        val candidatePackages = linkedSetOf<String>().apply {
            addAll(recordsByPackage.keys)
            addAll(pendingAtStart)
            val activePackage = activePrivatePackage(context)
            if (activePackage.isNotBlank()) add(activePackage)
        }

        var visibleCount = 0
        val failures = linkedMapOf<String, String>()
        val unresolvedPendingWithoutRecord = linkedSetOf<String>()
        candidatePackages.forEach { packageName ->
            if (!isPackageInstalled(context, packageName)) {
                clearPendingRehidePackage(context, packageName)
                return@forEach
            }

            val record = recordsByPackage[packageName]
            val validation = if (record != null) {
                validateStoredRecordSafety(context, record)
            } else {
                validatePackageSafety(context, packageName)
            }
            if (validation != null) {
                val displayName = record?.displayName ?: packageName
                failures[packageName] = "$displayName: $validation"
                return@forEach
            }

            val uninstallProtection = setUninstallBlocked(
                context = context,
                packageName = packageName,
                blocked = true
            )
            if (!uninstallProtection.success) {
                val displayName = record?.displayName ?: packageName
                failures[packageName] = "$displayName: ${uninstallProtection.message}"
                markPendingRehidePackage(context, packageName)
                return@forEach
            }

            val result = setHidden(context, packageName, false)
            if (result.success) {
                visibleCount += 1
                if (record == null && pendingAtStart.any { pending ->
                        pending.equals(packageName, ignoreCase = true)
                    }) {
                    unresolvedPendingWithoutRecord.add(packageName)
                } else {
                    clearPendingRehidePackage(context, packageName)
                }
            } else {
                val displayName = record?.displayName ?: packageName
                failures[packageName] = "$displayName: ${result.message}"
            }
        }

        val pendingAfter = linkedSetOf<String>().apply {
            addAll(failures.keys)
            addAll(unresolvedPendingWithoutRecord)
        }
        setPendingRehidePackages(context, pendingAfter)

        if (pendingAfter.isEmpty()) {
            clearActivePrivatePackage(context)
            clearMonitorSession(context)
        } else {
            setActivePrivatePackage(context, pendingAfter.first())
        }

        val finalProtectionCount = if (records.isNotEmpty()) {
            records.size
        } else if (pendingAfter.isNotEmpty()) {
            1
        } else {
            0
        }
        val finalInstallLock = reconcileProtectionInstallLock(context, finalProtectionCount)

        val message = buildString {
            append(
                "Emergency pause enabled. " +
                    "$visibleCount/${candidatePackages.size} target(s) are confirmed visible while package-specific uninstall protection remains active."
            )
            if (failures.isNotEmpty()) {
                append(" Recovery must not continue because these apps were not confirmed visible: ")
                append(failures.values.joinToString("; "))
                append('.')
            }
            if (unresolvedPendingWithoutRecord.isNotEmpty()) {
                append(
                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                    " Emergency recovery tracking remains active for targets whose durable Zyro record is still missing: "
                )
                append(unresolvedPendingWithoutRecord.joinToString(", "))
                append('.')
            }
            if (!finalInstallLock.success) {
                append(" Protection Install Lock final-state verification failed.")
            } else if (records.isNotEmpty() || pendingAfter.isNotEmpty()) {
                append(" Protection Install Lock remains active while private records or emergency recovery entries exist.")
            } else {
                append(" Protection Install Lock is inactive because no private records or emergency recovery entries remain.")
            }
            append(" Private commands remain blocked until Resume Protection is used.")
        }
        return ZeaDeviceOwnerOperationResult(
            success = failures.isEmpty() && finalInstallLock.success,
            message = message
        )
    }

    fun resumeProtection(context: Context): ZeaDeviceOwnerOperationResult {
        if (!isDeviceOwner(context)) {
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            return ZeaDeviceOwnerOperationResult(false, "Zyro is not the Device Owner.")
        }

        setProtectionPaused(context, true)
        val reconciliation = reconcileHiddenStateInternal(
            context = context,
            reason = "transactional protection resume",
            allowWhilePaused = true
        )
        return if (reconciliation.success) {
            setProtectionPaused(context, false)
            ZeaDeviceOwnerOperationResult(
                success = true,
                message = "Protection resumed only after every configured app was confirmed hidden and uninstall-blocked. ${reconciliation.message}",
                hidden = true
            )
        } else {
            setProtectionPaused(context, true)
            ZeaDeviceOwnerOperationResult(
                success = false,
                message = "Protection remains paused because hidden-at-rest confirmation failed. ${reconciliation.message}"
            )
        }
    }

    fun activePrivatePackage(context: Context): String = synchronized(stateLock) {
        statePreferences(context).getString(KEY_ACTIVE_PRIVATE_PACKAGE, "").orEmpty()
    }

    fun setActivePrivatePackage(context: Context, packageName: String) {
        synchronized(stateLock) {
            statePreferences(context).edit()
                .putString(KEY_ACTIVE_PRIVATE_PACKAGE, packageName.trim())
                .commit()
        }
    }

    fun clearActivePrivatePackage(context: Context) {
        synchronized(stateLock) {
            statePreferences(context).edit().remove(KEY_ACTIVE_PRIVATE_PACKAGE).commit()
        }
    }

    fun pendingRehidePackages(context: Context): Set<String> = synchronized(stateLock) {
        statePreferences(context)
            .getStringSet(KEY_PENDING_REHIDE_PACKAGES, emptySet())
            .orEmpty()
            .toSet()
    }

    fun markPendingRehidePackage(context: Context, packageName: String) {
        val normalized = packageName.trim()
        if (!packagePattern.matches(normalized)) return
        synchronized(stateLock) {
            val preferences = statePreferences(context)
            val updated = preferences
                .getStringSet(KEY_PENDING_REHIDE_PACKAGES, emptySet())
                .orEmpty()
                .toSet() + normalized
            preferences.edit()
                .putStringSet(KEY_PENDING_REHIDE_PACKAGES, updated)
                .commit()
        }
    }

    fun clearPendingRehidePackage(context: Context, packageName: String) {
        val normalized = packageName.trim()
        synchronized(stateLock) {
            val preferences = statePreferences(context)
            val updated = preferences
                .getStringSet(KEY_PENDING_REHIDE_PACKAGES, emptySet())
                .orEmpty()
                .toSet() - normalized
            preferences.edit()
                .putStringSet(KEY_PENDING_REHIDE_PACKAGES, updated)
                .commit()
        }
    }

    private fun setPendingRehidePackages(context: Context, packages: Set<String>) {
        synchronized(stateLock) {
            statePreferences(context).edit()
                .putStringSet(KEY_PENDING_REHIDE_PACKAGES, packages.toSet())
                .commit()
        }
    }

    private fun armExpectedUnhidePackageAdded(
        context: Context,
        sessionId: String,
        packageName: String
    ): Boolean {
        val normalizedPackage = packageName.trim()
        if (!packagePattern.matches(normalizedPackage) || sessionId.isBlank()) return false

        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activePackage = preferences
                .getString(KEY_ACTIVE_PRIVATE_PACKAGE, "")
                .orEmpty()
                .trim()
            val activeSession = preferences
                .getString(KEY_MONITOR_SESSION, "")
                .orEmpty()
                .trim()
            val pendingRecoveryForPackage = preferences
                .getStringSet(KEY_PENDING_REHIDE_PACKAGES, emptySet())
                .orEmpty()
                .any { pendingPackage ->
                    pendingPackage.equals(normalizedPackage, ignoreCase = true)
                }

            if (
                preferences.getBoolean(KEY_PROTECTION_PAUSED, false) ||
                pendingRecoveryForPackage ||
                activeSession != sessionId ||
                !activePackage.equals(normalizedPackage, ignoreCase = true)
            ) {
                return@synchronized false
            }

            preferences.edit()
                .putString(KEY_EXPECTED_UNHIDE_PACKAGE, normalizedPackage)
                .putString(KEY_EXPECTED_UNHIDE_SESSION, sessionId)
                .putLong(
                    KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED,
                    android.os.SystemClock.elapsedRealtime() +
                        EXPECTED_UNHIDE_PACKAGE_ADDED_WINDOW_MILLIS
                )
                .commit()
        }
    }

    private fun shouldDeferExpectedUnhidePackageAdded(
        context: Context,
        packageName: String,
        eventAction: String,
        packageReplacing: Boolean
    ): Boolean {
        if (
            eventAction != Intent.ACTION_PACKAGE_ADDED ||
            packageReplacing
        ) {
            return false
        }

        val normalizedPackage = packageName.trim()
        if (!packagePattern.matches(normalizedPackage)) return false

        val expectedWindowMatches = synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activePackage = preferences
                .getString(KEY_ACTIVE_PRIVATE_PACKAGE, "")
                .orEmpty()
                .trim()
            val activeSession = preferences
                .getString(KEY_MONITOR_SESSION, "")
                .orEmpty()
                .trim()
            val expectedPackage = preferences
                .getString(KEY_EXPECTED_UNHIDE_PACKAGE, "")
                .orEmpty()
                .trim()
            val expectedSession = preferences
                .getString(KEY_EXPECTED_UNHIDE_SESSION, "")
                .orEmpty()
                .trim()
            val expiresElapsed = preferences.getLong(
                KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED,
                0L
            )
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val pendingRecoveryForPackage = preferences
                .getStringSet(KEY_PENDING_REHIDE_PACKAGES, emptySet())
                .orEmpty()
                .any { pendingPackage ->
                    pendingPackage.equals(normalizedPackage, ignoreCase = true)
                }

            val matches =
                !preferences.getBoolean(KEY_PROTECTION_PAUSED, false) &&
                    !pendingRecoveryForPackage &&
                    activeSession.isNotBlank() &&
                    activeSession == expectedSession &&
                    activePackage.equals(normalizedPackage, ignoreCase = true) &&
                    expectedPackage.equals(normalizedPackage, ignoreCase = true) &&
                    expiresElapsed >= nowElapsed

            if (expectedPackage.isNotBlank() && expiresElapsed < nowElapsed) {
                preferences.edit()
                    .remove(KEY_EXPECTED_UNHIDE_PACKAGE)
                    .remove(KEY_EXPECTED_UNHIDE_SESSION)
                    .remove(KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED)
                    .commit()
            }

            matches
        }

        return expectedWindowMatches
    }

    /**
     * Panel-driven unhides (Unhide Only) make a protected app visible without
     * any active private-launch session. Making an app visible broadcasts
     * ACTION_PACKAGE_ADDED, and the safety lifecycle handler would read that
     * as an unprotected visible app and immediately restore hidden-at-rest.
     * Arming this short window tells the handler that the very next visibility
     * broadcast for this package is expected and must not be fought.
     */
    fun armManualVisibilityWindow(
        context: Context,
        packageName: String
    ): Boolean {
        val normalizedPackage = packageName.trim()
        if (!packagePattern.matches(normalizedPackage)) return false

        return synchronized(stateLock) {
            statePreferences(context).edit()
                .putString(KEY_MANUAL_VISIBILITY_PACKAGE, normalizedPackage)
                .putLong(
                    KEY_MANUAL_VISIBILITY_EXPIRES_ELAPSED,
                    android.os.SystemClock.elapsedRealtime() +
                            EXPECTED_UNHIDE_PACKAGE_ADDED_WINDOW_MILLIS
                )
                .commit()
        }
    }

    private fun consumeManualVisibilityWindow(
        context: Context,
        packageName: String
    ): Boolean {
        val normalizedPackage = packageName.trim()

        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val armedPackage = preferences
                .getString(KEY_MANUAL_VISIBILITY_PACKAGE, "")
                .orEmpty()
                .trim()
            val expiresElapsed = preferences.getLong(
                KEY_MANUAL_VISIBILITY_EXPIRES_ELAPSED,
                0L
            )
            val nowElapsed = android.os.SystemClock.elapsedRealtime()

            fun clearWindow() {
                preferences.edit()
                    .remove(KEY_MANUAL_VISIBILITY_PACKAGE)
                    .remove(KEY_MANUAL_VISIBILITY_EXPIRES_ELAPSED)
                    .commit()
            }

            if (armedPackage.isBlank() || expiresElapsed < nowElapsed) {
                if (armedPackage.isNotBlank()) clearWindow()
                return@synchronized false
            }

            if (!armedPackage.equals(normalizedPackage, ignoreCase = true)) {
                return@synchronized false
            }

            clearWindow()
            true
        }
    }

    private fun clearExpectedUnhidePackageAdded(
        context: Context,
        sessionId: String? = null,
        packageName: String? = null
    ) {
        synchronized(stateLock) {
            val preferences = statePreferences(context)
            val expectedSession = preferences
                .getString(KEY_EXPECTED_UNHIDE_SESSION, "")
                .orEmpty()
            val expectedPackage = preferences
                .getString(KEY_EXPECTED_UNHIDE_PACKAGE, "")
                .orEmpty()

            if (
                sessionId != null &&
                expectedSession.isNotBlank() &&
                expectedSession != sessionId
            ) {
                return@synchronized
            }
            if (
                packageName != null &&
                expectedPackage.isNotBlank() &&
                !expectedPackage.equals(packageName.trim(), ignoreCase = true)
            ) {
                return@synchronized
            }

            preferences.edit()
                .remove(KEY_EXPECTED_UNHIDE_PACKAGE)
                .remove(KEY_EXPECTED_UNHIDE_SESSION)
                .remove(KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED)
                .commit()
        }
    }

    private fun tryPreparePrivateLaunchSession(
        context: Context,
        packageName: String
    ): PrivateLaunchSessionPreparation {
        val normalizedPackage = packageName.trim()
        if (!packagePattern.matches(normalizedPackage)) {
            return PrivateLaunchSessionPreparation(
                rejectionReason = "The private app package is invalid."
            )
        }

        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activePackage = preferences
                .getString(KEY_ACTIVE_PRIVATE_PACKAGE, "")
                .orEmpty()
                .trim()
            val activeSession = preferences
                .getString(KEY_MONITOR_SESSION, "")
                .orEmpty()
                .trim()
            val pendingRecovery = preferences
                .getStringSet(KEY_PENDING_REHIDE_PACKAGES, emptySet())
                .orEmpty()
                .isNotEmpty()

            when {
                activePackage.isNotBlank() || activeSession.isNotBlank() -> {
                    // Sessions now live until screen-off/shutdown/max duration,
                    // so a new private launch supersedes the previous one
                    // instead of being rejected.
                    val previousPackage = activePackage
                    if (previousPackage.isNotBlank() &&
                        previousPackage != normalizedPackage
                    ) {
                        ZeaPrivateSessionMonitorService.stop(context)
                        val rehideResult = setHidden(
                            context = context,
                            packageName = previousPackage,
                            hidden = true,
                            requireStoredLauncherVerification = false
                        )
                        if (!rehideResult.success) {
                            return@synchronized PrivateLaunchSessionPreparation(
                                rejectionReason =
                                    // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                    "Zyro could not re-hide the previous private app before opening this one. Fail-closed recovery remains active."
                            )
                        }
                    }
                    val sessionId = UUID.randomUUID().toString()
                    val saved = preferences.edit()
                        .putString(KEY_ACTIVE_PRIVATE_PACKAGE, normalizedPackage)
                        .putString(KEY_MONITOR_SESSION, sessionId)
                        .remove(KEY_MONITOR_READY_SESSION)
                        .remove(KEY_MONITOR_READY_PACKAGE)
                        .remove(KEY_EXPECTED_UNHIDE_PACKAGE)
                        .remove(KEY_EXPECTED_UNHIDE_SESSION)
                        .remove(KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED)
                        .commit()

                    if (saved) {
                        PrivateLaunchSessionPreparation(sessionId = sessionId)
                    } else {
                        PrivateLaunchSessionPreparation(
                            rejectionReason =
                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                "Zyro could not reserve the private app session safely. No app was unhidden."
                        )
                    }
                }
                pendingRecovery ->
                    PrivateLaunchSessionPreparation(
                        rejectionReason =
                            "Fail-closed recovery is active. Finish recovery before opening another private app."
                    )
                else -> {
                    val sessionId = UUID.randomUUID().toString()
                    val saved = preferences.edit()
                        .putString(KEY_ACTIVE_PRIVATE_PACKAGE, normalizedPackage)
                        .putString(KEY_MONITOR_SESSION, sessionId)
                        .remove(KEY_MONITOR_READY_SESSION)
                        .remove(KEY_MONITOR_READY_PACKAGE)
                        .remove(KEY_EXPECTED_UNHIDE_PACKAGE)
                        .remove(KEY_EXPECTED_UNHIDE_SESSION)
                        .remove(KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED)
                        .commit()

                    if (saved) {
                        PrivateLaunchSessionPreparation(sessionId = sessionId)
                    } else {
                        PrivateLaunchSessionPreparation(
                            rejectionReason =
                                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                                "Zyro could not reserve the private app session safely. No app was unhidden."
                        )
                    }
                }
            }
        }
    }

    fun prepareMonitorSession(context: Context, packageName: String): String {
        val sessionId = UUID.randomUUID().toString()
        synchronized(stateLock) {
            statePreferences(context).edit()
                .putString(KEY_ACTIVE_PRIVATE_PACKAGE, packageName.trim())
                .putString(KEY_MONITOR_SESSION, sessionId)
                .remove(KEY_MONITOR_READY_SESSION)
                .remove(KEY_MONITOR_READY_PACKAGE)
                .remove(KEY_EXPECTED_UNHIDE_PACKAGE)
                .remove(KEY_EXPECTED_UNHIDE_SESSION)
                .remove(KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED)
                .commit()
        }
        return sessionId
    }

    fun markMonitorReady(context: Context, sessionId: String, packageName: String): Boolean {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val expectedSession = preferences.getString(KEY_MONITOR_SESSION, "").orEmpty()
            val expectedPackage = preferences.getString(KEY_ACTIVE_PRIVATE_PACKAGE, "").orEmpty()
            if (sessionId.isBlank() || sessionId != expectedSession || packageName != expectedPackage) {
                return@synchronized false
            }
            preferences.edit()
                .putString(KEY_MONITOR_READY_SESSION, sessionId)
                .putString(KEY_MONITOR_READY_PACKAGE, packageName)
                .commit()
        }
    }

    fun clearMonitorSession(
        context: Context,
        sessionId: String? = null,
        preservePrivateLaunchOutcome: Boolean = false
    ) {
        synchronized(stateLock) {
            val preferences = statePreferences(context)
            val currentSession = preferences.getString(KEY_MONITOR_SESSION, "").orEmpty()
            if (sessionId != null && currentSession.isNotBlank() && currentSession != sessionId) {
                return@synchronized
            }

            val editor = preferences.edit()
                .remove(KEY_MONITOR_SESSION)
                .remove(KEY_MONITOR_READY_SESSION)
                .remove(KEY_MONITOR_READY_PACKAGE)
                .remove(KEY_EXPECTED_UNHIDE_PACKAGE)
                .remove(KEY_EXPECTED_UNHIDE_SESSION)
                .remove(KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED)

            if (!preservePrivateLaunchOutcome) {
                editor
                    .remove(KEY_PRIVATE_LAUNCH_SESSION)
                    .remove(KEY_PRIVATE_LAUNCH_PACKAGE)
                    .remove(KEY_PRIVATE_LAUNCH_DISPATCH_ELAPSED)
                    .remove(KEY_PRIVATE_LAUNCH_DISPATCH_WALL)
                    .remove(KEY_PRIVATE_LAUNCH_OUTCOME)
                    .remove(KEY_PRIVATE_LAUNCH_FAILURE_REASON)
            }

            editor.commit()
        }
    }

    private suspend fun awaitMonitorReady(
        context: Context,
        sessionId: String,
        packageName: String
    ): Boolean {
        var waited = 0L
        while (waited < MONITOR_READY_TIMEOUT_MILLIS) {
            val preferences = statePreferences(context)
            val readySession = preferences.getString(KEY_MONITOR_READY_SESSION, "").orEmpty()
            val readyPackage = preferences.getString(KEY_MONITOR_READY_PACKAGE, "").orEmpty()
            if (readySession == sessionId && readyPackage == packageName) return true
            delay(MONITOR_READY_POLL_MILLIS)
            waited += MONITOR_READY_POLL_MILLIS
        }
        return false
    }

    private data class PrivateLaunchAwaitResult(
        val confirmed: Boolean,
        val failureReason: String? = null
    )

    fun markPrivateLaunchDispatched(
        context: Context,
        sessionId: String,
        packageName: String
    ): Boolean {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activeSession = preferences.getString(KEY_MONITOR_SESSION, "").orEmpty()
            val readySession = preferences.getString(KEY_MONITOR_READY_SESSION, "").orEmpty()
            val readyPackage = preferences.getString(KEY_MONITOR_READY_PACKAGE, "").orEmpty()

            if (
                activeSession.isBlank() ||
                activeSession != sessionId ||
                readySession != sessionId ||
                readyPackage != packageName
            ) {
                return@synchronized false
            }

            preferences.edit()
                .putString(KEY_PRIVATE_LAUNCH_SESSION, sessionId)
                .putString(KEY_PRIVATE_LAUNCH_PACKAGE, packageName)
                .putLong(
                    KEY_PRIVATE_LAUNCH_DISPATCH_ELAPSED,
                    android.os.SystemClock.elapsedRealtime()
                )
                .putLong(
                    KEY_PRIVATE_LAUNCH_DISPATCH_WALL,
                    System.currentTimeMillis()
                )
                .putString(
                    KEY_PRIVATE_LAUNCH_OUTCOME,
                    PRIVATE_LAUNCH_OUTCOME_PENDING
                )
                .remove(KEY_PRIVATE_LAUNCH_FAILURE_REASON)
                .commit()
        }
    }

    fun privateLaunchDispatchedElapsedRealtime(
        context: Context,
        packageName: String
    ): Long? {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activeSession = preferences.getString(KEY_MONITOR_SESSION, "").orEmpty()
            val launchSession = preferences.getString(KEY_PRIVATE_LAUNCH_SESSION, "").orEmpty()
            val launchPackage = preferences.getString(KEY_PRIVATE_LAUNCH_PACKAGE, "").orEmpty()
            val dispatchElapsed = preferences.getLong(
                KEY_PRIVATE_LAUNCH_DISPATCH_ELAPSED,
                0L
            )

            if (
                activeSession.isNotBlank() &&
                activeSession == launchSession &&
                launchPackage == packageName &&
                dispatchElapsed > 0L
            ) {
                dispatchElapsed
            } else {
                null
            }
        }
    }

    fun privateLaunchDispatchedWallClockMillis(
        context: Context,
        packageName: String
    ): Long? {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activeSession = preferences.getString(KEY_MONITOR_SESSION, "").orEmpty()
            val launchSession = preferences.getString(KEY_PRIVATE_LAUNCH_SESSION, "").orEmpty()
            val launchPackage = preferences.getString(KEY_PRIVATE_LAUNCH_PACKAGE, "").orEmpty()
            val dispatchWall = preferences.getLong(
                KEY_PRIVATE_LAUNCH_DISPATCH_WALL,
                0L
            )

            if (
                activeSession.isNotBlank() &&
                activeSession == launchSession &&
                launchPackage == packageName &&
                dispatchWall > 0L
            ) {
                dispatchWall
            } else {
                null
            }
        }
    }

    fun isPrivateForegroundConfirmed(
        context: Context,
        packageName: String
    ): Boolean {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activeSession = preferences.getString(KEY_MONITOR_SESSION, "").orEmpty()
            val launchSession = preferences.getString(KEY_PRIVATE_LAUNCH_SESSION, "").orEmpty()
            val launchPackage = preferences.getString(KEY_PRIVATE_LAUNCH_PACKAGE, "").orEmpty()
            val outcome = preferences.getString(KEY_PRIVATE_LAUNCH_OUTCOME, "").orEmpty()

            activeSession.isNotBlank() &&
                activeSession == launchSession &&
                launchPackage == packageName &&
                outcome == PRIVATE_LAUNCH_OUTCOME_CONFIRMED
        }
    }

    fun markPrivateForegroundConfirmed(
        context: Context,
        packageName: String
    ): Boolean {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activeSession = preferences.getString(KEY_MONITOR_SESSION, "").orEmpty()
            val launchSession = preferences.getString(KEY_PRIVATE_LAUNCH_SESSION, "").orEmpty()
            val launchPackage = preferences.getString(KEY_PRIVATE_LAUNCH_PACKAGE, "").orEmpty()

            if (
                activeSession.isBlank() ||
                activeSession != launchSession ||
                launchPackage != packageName
            ) {
                return@synchronized false
            }

            preferences.edit()
                .putString(
                    KEY_PRIVATE_LAUNCH_OUTCOME,
                    PRIVATE_LAUNCH_OUTCOME_CONFIRMED
                )
                .remove(KEY_PRIVATE_LAUNCH_FAILURE_REASON)
                .remove(KEY_EXPECTED_UNHIDE_PACKAGE)
                .remove(KEY_EXPECTED_UNHIDE_SESSION)
                .remove(KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED)
                .commit()
        }
    }

    fun markPrivateForegroundFailed(
        context: Context,
        packageName: String,
        reason: String
    ): Boolean {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val activeSession = preferences.getString(KEY_MONITOR_SESSION, "").orEmpty()
            val launchSession = preferences.getString(KEY_PRIVATE_LAUNCH_SESSION, "").orEmpty()
            val launchPackage = preferences.getString(KEY_PRIVATE_LAUNCH_PACKAGE, "").orEmpty()

            if (
                activeSession.isBlank() ||
                activeSession != launchSession ||
                launchPackage != packageName
            ) {
                return@synchronized false
            }

            preferences.edit()
                .putString(
                    KEY_PRIVATE_LAUNCH_OUTCOME,
                    PRIVATE_LAUNCH_OUTCOME_FAILED
                )
                .putString(
                    KEY_PRIVATE_LAUNCH_FAILURE_REASON,
                    reason
                )
                .remove(KEY_EXPECTED_UNHIDE_PACKAGE)
                .remove(KEY_EXPECTED_UNHIDE_SESSION)
                .remove(KEY_EXPECTED_UNHIDE_EXPIRES_ELAPSED)
                .commit()
        }
    }

    private fun consumeConfirmedPrivateLaunchOutcome(
        context: Context,
        sessionId: String,
        packageName: String
    ): PrivateLaunchHandshakeConsumption {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val launchSession = preferences
                .getString(KEY_PRIVATE_LAUNCH_SESSION, "")
                .orEmpty()
            val launchPackage = preferences
                .getString(KEY_PRIVATE_LAUNCH_PACKAGE, "")
                .orEmpty()
            val launchOutcome = preferences
                .getString(KEY_PRIVATE_LAUNCH_OUTCOME, "")
                .orEmpty()

            if (
                launchSession != sessionId ||
                launchPackage != packageName ||
                launchOutcome != PRIVATE_LAUNCH_OUTCOME_CONFIRMED
            ) {
                return@synchronized PrivateLaunchHandshakeConsumption(
                    consumed = false,
                    activeMonitorStateRetained = false
                )
            }

            val activeMonitorSession = preferences
                .getString(KEY_MONITOR_SESSION, "")
                .orEmpty()
            val retainActiveMonitorState =
                activeMonitorSession == sessionId

            val editor = preferences.edit()
                .remove(KEY_PRIVATE_LAUNCH_OUTCOME)
                .remove(KEY_PRIVATE_LAUNCH_FAILURE_REASON)

            if (!retainActiveMonitorState) {
                editor
                    .remove(KEY_PRIVATE_LAUNCH_SESSION)
                    .remove(KEY_PRIVATE_LAUNCH_PACKAGE)
                    .remove(KEY_PRIVATE_LAUNCH_DISPATCH_ELAPSED)
                    .remove(KEY_PRIVATE_LAUNCH_DISPATCH_WALL)
            }

            val committed = editor.commit()
            PrivateLaunchHandshakeConsumption(
                consumed = committed,
                activeMonitorStateRetained =
                    committed && retainActiveMonitorState
            )
        }
    }

    private fun clearPrivateLaunchOutcome(
        context: Context,
        sessionId: String,
        packageName: String
    ) {
        synchronized(stateLock) {
            val preferences = statePreferences(context)
            val launchSession = preferences
                .getString(KEY_PRIVATE_LAUNCH_SESSION, "")
                .orEmpty()
            val launchPackage = preferences
                .getString(KEY_PRIVATE_LAUNCH_PACKAGE, "")
                .orEmpty()

            if (
                launchSession != sessionId ||
                launchPackage != packageName
            ) {
                return@synchronized
            }

            preferences.edit()
                .remove(KEY_PRIVATE_LAUNCH_SESSION)
                .remove(KEY_PRIVATE_LAUNCH_PACKAGE)
                .remove(KEY_PRIVATE_LAUNCH_DISPATCH_ELAPSED)
                .remove(KEY_PRIVATE_LAUNCH_DISPATCH_WALL)
                .remove(KEY_PRIVATE_LAUNCH_OUTCOME)
                .remove(KEY_PRIVATE_LAUNCH_FAILURE_REASON)
                .commit()
        }
    }

    private fun readPrivateLaunchAwaitResult(
        context: Context,
        sessionId: String,
        packageName: String
    ): PrivateLaunchAwaitResult? {
        return synchronized(stateLock) {
            val preferences = statePreferences(context)
            val launchSession = preferences.getString(KEY_PRIVATE_LAUNCH_SESSION, "").orEmpty()
            val launchPackage = preferences.getString(KEY_PRIVATE_LAUNCH_PACKAGE, "").orEmpty()

            if (
                launchSession != sessionId ||
                launchPackage != packageName
            ) {
                return@synchronized null
            }

            when (
                preferences.getString(
                    KEY_PRIVATE_LAUNCH_OUTCOME,
                    ""
                ).orEmpty()
            ) {
                PRIVATE_LAUNCH_OUTCOME_CONFIRMED ->
                    PrivateLaunchAwaitResult(confirmed = true)

                PRIVATE_LAUNCH_OUTCOME_FAILED ->
                    PrivateLaunchAwaitResult(
                        confirmed = false,
                        failureReason = preferences.getString(
                            KEY_PRIVATE_LAUNCH_FAILURE_REASON,
                            ""
                        ).orEmpty().ifBlank {
                            "Private foreground confirmation failed."
                        }
                    )

                else -> null
            }
        }
    }

    private suspend fun awaitPrivateForegroundConfirmation(
        context: Context,
        sessionId: String,
        packageName: String
    ): PrivateLaunchAwaitResult {
        val started = android.os.SystemClock.elapsedRealtime()

        while (
            android.os.SystemClock.elapsedRealtime() - started <
                PRIVATE_FOREGROUND_CONFIRMATION_TIMEOUT_MILLIS
        ) {
            readPrivateLaunchAwaitResult(
                context = context,
                sessionId = sessionId,
                packageName = packageName
            )?.let {
                return it
            }

            val dispatchStillValid =
                privateLaunchDispatchedElapsedRealtime(
                    context = context,
                    packageName = packageName
                ) != null

            if (!dispatchStillValid) {
                return PrivateLaunchAwaitResult(
                    confirmed = false,
                    failureReason =
                        "The private session ended before foreground confirmation."
                )
            }

            delay(MONITOR_READY_POLL_MILLIS)
        }

        return readPrivateLaunchAwaitResult(
            context = context,
            sessionId = sessionId,
            packageName = packageName
        ) ?: PrivateLaunchAwaitResult(
            confirmed = false,
            failureReason = "Private foreground confirmation timed out."
        )
    }

    fun isProtectionPaused(context: Context): Boolean {
        return statePreferences(context).getBoolean(KEY_PROTECTION_PAUSED, false)
    }

    private fun setProtectionPaused(context: Context, paused: Boolean) {
        statePreferences(context).edit().putBoolean(KEY_PROTECTION_PAUSED, paused).commit()
    }

    private fun statePreferences(context: Context) =
        context.applicationContext.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    private fun resolveCurrentHomePackage(context: Context): String? {
        return try {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        } catch (_: RuntimeException) {
            null
        }
    }
}
