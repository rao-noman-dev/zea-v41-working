package com.raomuhammadnoman.zea

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the app list the Apps management screens render.
 *
 * Reuses the existing discovery pipeline rather than enumerating packages
 * separately, so the Apps screens and the command launcher always agree about
 * which apps exist. Safety-blocked apps are kept in the list but marked
 * unmanageable, because hiding a listed app the user can see is clearer than
 * hiding the fact that the app exists at all.
 */
object ZeaAppCatalog {
    /**
     * Zea drives this list, so it never lists itself. Matching the whole
     * package family rather than only the running id also keeps a suffixed
     * build, such as the one used for UI verification, from listing the
     * production install alongside itself.
     */
    private const val ZEA_PACKAGE_FAMILY = "com.raomuhammadnoman.zea"

    /**
     * The full discovery pipeline (PM scan plus a per-package system-app
     * lookup) costs several hundred milliseconds on mid-range devices, which
     * made every navigation between Apps screens feel sluggish. The finished
     * list is cached against a cheap fingerprint of the protection registries
     * so repeat visits render instantly while any hide, unhide, or timed
     * change rewrites the registry JSON and forces an honest rescan. The
     * wall-clock bucket also lets newly installed or removed apps appear
     * without waiting for a registry edit.
     */
    @Volatile
    private var cachedApps: List<ZeaManagedApp>? = null

    @Volatile
    private var cachedStamp: Int = Int.MIN_VALUE

    internal fun invalidateCatalogCache() {
        cachedApps = null
        cachedStamp = Int.MIN_VALUE
    }

    private suspend fun loadManagedAppsCached(
        context: Context
    ): List<ZeaManagedApp> = withContext(Dispatchers.IO) {
        val stamp = catalogStamp(context)
        val cached = cachedApps
        if (cached != null && stamp == cachedStamp) {
            cached
        } else {
            val fresh = loadManagedAppsBlocking(context)
            cachedApps = fresh
            cachedStamp = stamp
            fresh
        }
    }

    private fun catalogStamp(context: Context): Int {
        val bucket = System.currentTimeMillis() / 10_000L
        var stamp = loadPrivateApps(context).hashCode()
        stamp = 31 * stamp + loadTimedHides(context).hashCode()
        stamp = 31 * stamp + bucket.hashCode()
        return stamp
    }

    suspend fun loadManagedApps(
        context: Context
    ): List<ZeaManagedApp> {
        val appContext = context.applicationContext
        ZeaTimedHide.restoreExpiredHides(appContext)
        return loadManagedAppsCached(appContext)
    }

    internal fun loadManagedAppsBlocking(
        context: Context
    ): List<ZeaManagedApp> {
        val packageManager = context.packageManager
        val privateRecords = loadPrivateApps(context)
        val hiddenPackages = privateRecords
            .asSequence()
            .filter { record ->
                ZeaDeviceOwnerController.isHidden(context, record.packageName) != false
            }
            .map { record ->
                record.packageName.lowercase(Locale.ROOT)
            }
            .toSet()
        val timedByPackage = loadTimedHides(context)
            .filter { record -> record.hiddenUntilEpochMillis > System.currentTimeMillis() }
            .associateBy { record -> record.packageName.lowercase(Locale.ROOT) }

        val discovered = ZeaInstalledApps
            .discoverAllLaunchableAppsBlocking(context)
            .asSequence()
            .filterNot { candidate ->
                isZeaPackage(candidate.packageName)
            }
            .map { candidate ->
                toManagedApp(
                    packageManager = packageManager,
                    candidate = candidate,
                    hiddenPackages = hiddenPackages,
                    timedByPackage = timedByPackage
                )
            }
            .distinctBy { app ->
                app.packageName.lowercase(Locale.ROOT)
            }
            .toList()

        // Android's launcher queries silently drop Device-Owner-hidden apps,
        // so restore them from the registry records that recorded the hide.
        val seenPackages = discovered
            .asSequence()
            .map { app -> app.packageName.lowercase(Locale.ROOT) }
            .toMutableSet()
        val restored = privateRecords
            .asSequence()
            .mapNotNull { record ->
                val packageKey = record.packageName.lowercase(Locale.ROOT)
                if (packageKey !in hiddenPackages || packageKey in seenPackages) {
                    return@mapNotNull null
                }
                seenPackages += packageKey

                val timed = timedByPackage[packageKey]
                ZeaManagedApp(
                    displayName = record.displayName,
                    packageName = record.packageName,
                    launcherActivityName = record.launcherActivityName,
                    systemApp = isSystemApp(packageManager, record.packageName),
                    hideMode = if (timed != null) ZeaHideMode.TIMED else ZeaHideMode.HIDDEN,
                    hiddenUntilEpochMillis = timed?.hiddenUntilEpochMillis ?: 0L,
                    manageable = true,
                    blockedReason = "",
                    firstInstallTimeEpochMillis = firstInstallTime(packageManager, record.packageName)
                )
            }
            .toList()

        return (discovered + restored).sortedWith(managedAppComparator)
    }

    private fun isZeaPackage(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase(Locale.ROOT)

        return normalized == ZEA_PACKAGE_FAMILY ||
                normalized.startsWith("$ZEA_PACKAGE_FAMILY.")
    }

    private fun toManagedApp(
        packageManager: PackageManager,
        candidate: InstalledAppCandidate,
        hiddenPackages: Set<String>,
        timedByPackage: Map<String, ZeaTimedHideRecord>
    ): ZeaManagedApp {
        val safety = ZeaSafetyPolicy.evaluateInstalledAppCandidate(candidate)
        val packageKey = candidate.packageName.lowercase(Locale.ROOT)
        val timed = timedByPackage[packageKey]
        val hidden = packageKey in hiddenPackages
        val hideMode = when {
            timed != null -> ZeaHideMode.TIMED
            hidden -> ZeaHideMode.HIDDEN
            else -> ZeaHideMode.VISIBLE
        }

        return ZeaManagedApp(
            displayName = candidate.displayName,
            packageName = candidate.packageName,
            launcherActivityName = candidate.launcherActivityName,
            systemApp = isSystemApp(packageManager, candidate.packageName),
            hideMode = hideMode,
            hiddenUntilEpochMillis = timed?.hiddenUntilEpochMillis ?: 0L,
            manageable = safety.allowed,
            blockedReason = if (safety.allowed) "" else safety.message,
            firstInstallTimeEpochMillis = firstInstallTime(packageManager, candidate.packageName)
        )
    }

    @Suppress("DEPRECATION")
    private fun isSystemApp(
        packageManager: PackageManager,
        packageName: String
    ): Boolean {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

            (applicationInfo.flags and systemFlags) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun firstInstallTime(
        packageManager: PackageManager,
        packageName: String
    ): Long {
        return try {
            packageManager
                .getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .firstInstallTime
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        } catch (_: RuntimeException) {
            0L
        }
    }

    private val managedAppComparator =
        compareBy<ZeaManagedApp> { app ->
            app.displayName.lowercase(Locale.ROOT)
        }.thenBy { app ->
            app.packageName.lowercase(Locale.ROOT)
        }
}
