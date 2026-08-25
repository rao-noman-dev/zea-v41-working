package com.raomuhammadnoman.zea

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * Discovers launchable applications and resolves user-entered app names.
 *
 * Package discovery is intentionally limited to launcher activities visible to
 * Android and packages already present in the built-in registry. Broad package
 * enumeration and QUERY_ALL_PACKAGES are not used.
 */
object ZeaInstalledApps {
    private const val MAX_AMBIGUOUS_CANDIDATES = 5

    suspend fun discoverLaunchableApps(
        context: Context
    ): List<InstalledAppCandidate> = withContext(Dispatchers.IO) {
        discoverLaunchableAppsBlocking(
            context = context.applicationContext,
            includeBlockedApps = false
        )
    }

    suspend fun resolveAllowedApp(
        context: Context,
        requestedName: String
    ): AllowedAppResolution = withContext(Dispatchers.IO) {
        resolveAllowedAppFromCandidates(
            requestedName = requestedName,
            candidates = discoverLaunchableAppsBlocking(
                context = context.applicationContext,
                includeBlockedApps = true
            )
        )
    }

    suspend fun resolveAllowedApps(
        context: Context,
        requestedNames: List<String>
    ): List<AllowedAppResolution> = withContext(Dispatchers.IO) {
        val candidates = discoverLaunchableAppsBlocking(
            context = context.applicationContext,
            includeBlockedApps = true
        )

        requestedNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizeName)
            .map { requestedName ->
                resolveAllowedAppFromCandidates(
                    requestedName = requestedName,
                    candidates = candidates
                )
            }
            .toList()
    }

    fun buildUserAllowedRegistryEntries(
        userAllowedApps: List<UserAllowedApp>
    ): List<AppRegistryEntry> {
        val builtInPackages = zeaAppRegistry
            .asSequence()
            .flatMap { entry ->
                sequenceOf(entry.packageName) + entry.alternatePackageNames.asSequence()
            }
            .filterNotNull()
            .map(::normalizePackageName)
            .toSet()

        val safeDistinctApps = userAllowedApps
            .asSequence()
            .filter { app ->
                normalizePackageName(app.packageName) !in builtInPackages
            }
            .filter { app ->
                ZeaSafetyPolicy.evaluateUserAllowedApp(app).allowed
            }
            .distinctBy { app ->
                normalizePackageName(app.packageName)
            }
            .toList()

        val unambiguousDisplayNames = safeDistinctApps
            .groupingBy { app -> normalizeName(app.displayName) }
            .eachCount()
            .filterValues { count -> count == 1 }
            .keys

        return safeDistinctApps
            .asSequence()
            .filter { app ->
                normalizeName(app.displayName) in unambiguousDisplayNames
            }
            .map(::toRegistryEntry)
            .sortedWith(appRegistryComparator)
            .toList()
    }

    internal fun resolveAllowedAppFromCandidates(
        requestedName: String,
        candidates: List<InstalledAppCandidate>
    ): AllowedAppResolution {
        val cleanRequestedName = requestedName.trim()
        val requestedNameSafety = ZeaSafetyPolicy.evaluateRequestedAppName(cleanRequestedName)

        if (!requestedNameSafety.allowed) {
            return AllowedAppResolution(
                requestedName = cleanRequestedName,
                status = AllowedAppResolutionStatus.BLOCKED,
                message = requestedNameSafety.message
            )
        }

        val rankedMatches = candidates
            .asSequence()
            .filter(::isCandidateStructurallyValid)
            .distinctBy(::candidateIdentity)
            .mapNotNull { candidate ->
                val score = appNameMatchScore(cleanRequestedName, candidate.displayName)
                if (score > 0) ScoredCandidate(candidate, score) else null
            }
            .sortedWith(scoredCandidateComparator)
            .toList()

        if (rankedMatches.isEmpty()) {
            val configuredApp = zeaAppRegistry.firstOrNull { entry ->
                registryEntryNameMatchScore(cleanRequestedName, entry) > 0
            }

            return if (configuredApp != null) {
                AllowedAppResolution(
                    requestedName = cleanRequestedName,
                    status = AllowedAppResolutionStatus.NOT_LAUNCHABLE,
                    message = "${configuredApp.displayName} is configured, but Android did not expose an enabled launcher activity."
                )
            } else {
                AllowedAppResolution(
                    requestedName = cleanRequestedName,
                    status = AllowedAppResolutionStatus.NOT_FOUND,
                    message = "No visible launchable app matched the requested name."
                )
            }
        }

        val topScore = rankedMatches.first().score
        val topMatches = rankedMatches
            .asSequence()
            .filter { match -> match.score == topScore }
            .map(ScoredCandidate::candidate)
            .distinctBy { candidate -> normalizePackageName(candidate.packageName) }
            .toList()

        val blockedTopMatch = topMatches
            .asSequence()
            .map { candidate ->
                candidate to ZeaSafetyPolicy.evaluateInstalledAppCandidate(candidate)
            }
            .firstOrNull { (_, safetyResult) -> !safetyResult.allowed }

        if (blockedTopMatch != null) {
            return AllowedAppResolution(
                requestedName = cleanRequestedName,
                status = AllowedAppResolutionStatus.BLOCKED,
                candidates = topMatches.take(MAX_AMBIGUOUS_CANDIDATES),
                message = blockedTopMatch.second.message
            )
        }

        if (topMatches.size > 1) {
            return AllowedAppResolution(
                requestedName = cleanRequestedName,
                status = AllowedAppResolutionStatus.AMBIGUOUS,
                candidates = topMatches.take(MAX_AMBIGUOUS_CANDIDATES),
                message = "Multiple installed apps matched this name. Use the complete app label."
            )
        }

        val selectedCandidate = topMatches.single()
        val selectedApp = UserAllowedApp(
            displayName = selectedCandidate.displayName,
            packageName = selectedCandidate.packageName,
            launcherActivityName = selectedCandidate.launcherActivityName,
            aliases = buildAliases(
                requestedName = cleanRequestedName,
                displayName = selectedCandidate.displayName
            )
        )
        val selectedAppSafety = ZeaSafetyPolicy.evaluateUserAllowedApp(selectedApp)

        if (!selectedAppSafety.allowed) {
            return AllowedAppResolution(
                requestedName = cleanRequestedName,
                status = AllowedAppResolutionStatus.BLOCKED,
                candidates = listOf(selectedCandidate),
                message = selectedAppSafety.message
            )
        }

        return AllowedAppResolution(
            requestedName = cleanRequestedName,
            status = AllowedAppResolutionStatus.RESOLVED,
            selectedApp = selectedApp,
            candidates = listOf(selectedCandidate),
            message = "${selectedCandidate.displayName} was verified as an installed launchable app."
        )
    }

    /**
     * Returns every launchable app including safety-blocked ones, so the Apps
     * management screens can list a blocked app and explain why it cannot be
     * managed instead of silently omitting it.
     */
    internal fun discoverAllLaunchableAppsBlocking(
        context: Context
    ): List<InstalledAppCandidate> {
        return discoverLaunchableAppsBlocking(
            context = context.applicationContext,
            includeBlockedApps = true
        )
    }

    private fun discoverLaunchableAppsBlocking(
        context: Context,
        includeBlockedApps: Boolean
    ): List<InstalledAppCandidate> {
        val packageManager = context.packageManager
        val records = mutableListOf<CandidateRecord>()

        records += queryLauncherActivities(packageManager).map { candidate ->
            CandidateRecord(candidate, DiscoveryPriority.PACKAGE_MANAGER_QUERY)
        }
        records += queryLauncherApps(context).map { candidate ->
            CandidateRecord(candidate, DiscoveryPriority.LAUNCHER_APPS)
        }

        val discoveredPackages = records
            .asSequence()
            .map { record -> normalizePackageName(record.candidate.packageName) }
            .toMutableSet()

        configuredRegistryPackageNames().forEach { packageName ->
            if (normalizePackageName(packageName) !in discoveredPackages) {
                val packageCandidates = queryConfiguredPackage(
                    packageManager = packageManager,
                    packageName = packageName
                )

                records += packageCandidates.map { candidate ->
                    CandidateRecord(candidate, DiscoveryPriority.CONFIGURED_PACKAGE_PROBE)
                }
                discoveredPackages += packageCandidates.map { candidate ->
                    normalizePackageName(candidate.packageName)
                }
            }
        }

        return records
            .asSequence()
            .filter { record ->
                record.candidate.packageName != context.packageName
            }
            .filter { record ->
                isCandidateStructurallyValid(record.candidate)
            }
            .filter { record ->
                includeBlockedApps ||
                        ZeaSafetyPolicy.evaluateInstalledAppCandidate(record.candidate).allowed
            }
            .groupBy { record ->
                normalizePackageName(record.candidate.packageName)
            }
            .values
            .map { packageRecords ->
                packageRecords.minWith(candidateRecordComparator).candidate
            }
            .sortedWith(installedCandidateComparator)
            .toList()
    }

    private fun queryConfiguredPackage(
        packageManager: PackageManager,
        packageName: String
    ): List<InstalledAppCandidate> {
        val candidates = mutableListOf<InstalledAppCandidate>()
        val launchIntent = safeAndroidCall(defaultValue = null) {
            packageManager.getLaunchIntentForPackage(packageName)
        }

        launchIntent
            ?.component
            ?.let { componentName ->
                candidateFromComponent(
                    packageManager = packageManager,
                    componentName = componentName
                )
            }
            ?.let(candidates::add)

        val packageIntent = launcherIntent().setPackage(packageName)
        candidates += queryLauncherActivities(packageManager, packageIntent)

        return candidates.distinctBy(::candidateIdentity)
    }

    private fun queryLauncherActivities(
        packageManager: PackageManager,
        intent: Intent = launcherIntent()
    ): List<InstalledAppCandidate> {
        return queryIntentActivities(packageManager, intent)
            .mapNotNull { resolveInfo ->
                candidateFromResolveInfo(packageManager, resolveInfo)
            }
    }

    private fun queryLauncherApps(
        context: Context
    ): List<InstalledAppCandidate> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
            ?: return emptyList()

        return safeAndroidCall(defaultValue = emptyList()) {
            launcherApps.getActivityList(null, Process.myUserHandle())
        }.mapNotNull(::candidateFromLauncherActivityInfo)
    }

    @Suppress("DEPRECATION")
    private fun queryIntentActivities(
        packageManager: PackageManager,
        intent: Intent
    ): List<ResolveInfo> {
        return safeAndroidCall(defaultValue = emptyList()) {
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
    }

    private fun candidateFromResolveInfo(
        packageManager: PackageManager,
        resolveInfo: ResolveInfo
    ): InstalledAppCandidate? {
        val activityInfo = resolveInfo.activityInfo ?: return null

        return candidateFromActivityInfo(
            activityInfo = activityInfo,
            displayLabel = resolveInfo.loadLabel(packageManager)
        )
    }

    private fun candidateFromLauncherActivityInfo(
        activityInfo: LauncherActivityInfo
    ): InstalledAppCandidate? {
        val componentName = activityInfo.componentName
        val applicationPackage = activityInfo.applicationInfo.packageName

        if (
            !activityInfo.applicationInfo.enabled ||
            componentName.packageName != applicationPackage
        ) {
            return null
        }

        return candidate(
            displayName = activityInfo.label?.toString()?.trim().orEmpty(),
            packageName = componentName.packageName,
            launcherActivityName = componentName.className
        )
    }

    @Suppress("DEPRECATION")
    private fun candidateFromComponent(
        packageManager: PackageManager,
        componentName: ComponentName
    ): InstalledAppCandidate? {
        val activityInfo = safeAndroidCall(defaultValue = null) {
            packageManager.getActivityInfo(componentName, 0)
        } ?: return null

        return candidateFromActivityInfo(
            activityInfo = activityInfo,
            displayLabel = activityInfo.loadLabel(packageManager)
        )
    }

    private fun candidateFromActivityInfo(
        activityInfo: ActivityInfo,
        displayLabel: CharSequence?
    ): InstalledAppCandidate? {
        if (
            !activityInfo.enabled ||
            !activityInfo.exported ||
            !activityInfo.applicationInfo.enabled
        ) {
            return null
        }

        return candidate(
            displayName = displayLabel?.toString()?.trim().orEmpty(),
            packageName = activityInfo.packageName,
            launcherActivityName = activityInfo.name
        )
    }

    private fun candidate(
        displayName: String,
        packageName: String,
        launcherActivityName: String
    ): InstalledAppCandidate? {
        val cleanDisplayName = displayName.trim()
        val cleanPackageName = packageName.trim()
        val cleanActivityName = launcherActivityName.trim()

        if (
            cleanDisplayName.isBlank() ||
            !ZeaSafetyPolicy.isValidPackageName(cleanPackageName) ||
            !ZeaSafetyPolicy.isValidLauncherActivityName(
                packageName = cleanPackageName,
                launcherActivityName = cleanActivityName
            )
        ) {
            return null
        }

        return InstalledAppCandidate(
            displayName = cleanDisplayName,
            packageName = cleanPackageName,
            launcherActivityName = cleanActivityName
        )
    }

    private fun configuredRegistryPackageNames(): List<String> {
        return zeaAppRegistry
            .asSequence()
            .flatMap { entry ->
                sequenceOf(entry.packageName) + entry.alternatePackageNames.asSequence()
            }
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::normalizePackageName)
            .toList()
    }

    private fun isCandidateStructurallyValid(
        candidate: InstalledAppCandidate
    ): Boolean {
        return candidate.displayName.isNotBlank() &&
                ZeaSafetyPolicy.isValidPackageName(candidate.packageName) &&
                ZeaSafetyPolicy.isValidLauncherActivityName(
                    packageName = candidate.packageName,
                    launcherActivityName = candidate.launcherActivityName
                )
    }

    private fun toRegistryEntry(
        app: UserAllowedApp
    ): AppRegistryEntry {
        return AppRegistryEntry(
            key = userAllowedAppKey(app.packageName),
            displayName = app.displayName.trim(),
            aliases = app.aliases
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .filterNot { alias ->
                    normalizeName(alias) == normalizeName(app.displayName)
                }
                .distinctBy(::normalizeName)
                .toList(),
            packageName = app.packageName.trim(),
            launcherActivityName = app.launcherActivityName.trim(),
            source = AppRegistrySource.USER_ALLOWED,
            category = AppCategory.OTHER
        )
    }

    private fun buildAliases(
        requestedName: String,
        displayName: String
    ): List<String> {
        return listOf(requestedName.trim())
            .filter(String::isNotBlank)
            .filterNot { alias ->
                normalizeName(alias) == normalizeName(displayName)
            }
    }

    private fun userAllowedAppKey(
        packageName: String
    ): String {
        val encodedPackageName = buildString {
            normalizePackageName(packageName).forEach { character ->
                when (character) {
                    '.' -> append("_d_")
                    '_' -> append("_u_")
                    else -> append(character)
                }
            }
        }

        return "user_$encodedPackageName"
    }

    private fun appNameMatchScore(
        requestedName: String,
        displayName: String
    ): Int {
        val requested = normalizeName(requestedName)
        val display = normalizeName(displayName)

        if (requested.isBlank() || display.isBlank()) {
            return 0
        }

        if (requested == display) {
            return 1_000
        }

        val requestedCompact = compactName(requested)
        val displayCompact = compactName(display)

        if (requestedCompact == displayCompact) {
            return 950
        }

        val requestedTokens = requested.split(' ').filter(String::isNotBlank)
        val displayTokens = display.split(' ').filter(String::isNotBlank)

        if (displayTokens.containsAll(requestedTokens)) {
            return 700 + requestedTokens.size * 10
        }

        if (requestedTokens.containsAll(displayTokens)) {
            return 650 + displayTokens.size * 10
        }

        return 0
    }


    private fun registryEntryNameMatchScore(
        requestedName: String,
        entry: AppRegistryEntry
    ): Int {
        return (listOf(entry.displayName, entry.key) + entry.aliases)
            .maxOfOrNull { searchableName ->
                appNameMatchScore(requestedName, searchableName)
            }
            ?: 0
    }

    private fun normalizeName(
        value: String
    ): String {
        return Normalizer
            .normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun compactName(
        normalizedName: String
    ): String {
        return normalizedName.replace(" ", "")
    }

    private fun normalizePackageName(
        packageName: String
    ): String {
        return packageName.trim().lowercase(Locale.ROOT)
    }

    private fun candidateIdentity(
        candidate: InstalledAppCandidate
    ): String {
        return buildString {
            append(normalizePackageName(candidate.packageName))
            append('|')
            append(candidate.launcherActivityName.trim().lowercase(Locale.ROOT))
        }
    }

    private fun launcherIntent(): Intent {
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    }


    private inline fun <T> safeAndroidCall(
        defaultValue: T,
        block: () -> T
    ): T {
        return try {
            block()
        } catch (_: PackageManager.NameNotFoundException) {
            defaultValue
        } catch (_: RuntimeException) {
            defaultValue
        }
    }

    private data class ScoredCandidate(
        val candidate: InstalledAppCandidate,
        val score: Int
    )

    private data class CandidateRecord(
        val candidate: InstalledAppCandidate,
        val priority: DiscoveryPriority
    )

    private enum class DiscoveryPriority {
        PACKAGE_MANAGER_QUERY,
        CONFIGURED_PACKAGE_PROBE,
        LAUNCHER_APPS
    }

    private val installedCandidateComparator =
        compareBy<InstalledAppCandidate> { candidate ->
            normalizeName(candidate.displayName)
        }.thenBy { candidate ->
            normalizePackageName(candidate.packageName)
        }.thenBy { candidate ->
            candidate.launcherActivityName.lowercase(Locale.ROOT)
        }

    private val scoredCandidateComparator =
        compareByDescending<ScoredCandidate> { match -> match.score }
            .thenBy { match -> normalizeName(match.candidate.displayName) }
            .thenBy { match -> normalizePackageName(match.candidate.packageName) }

    private val candidateRecordComparator =
        compareBy<CandidateRecord> { record -> record.priority.ordinal }
            .thenBy { record -> normalizeName(record.candidate.displayName) }
            .thenBy { record -> record.candidate.launcherActivityName.lowercase(Locale.ROOT) }

    private val appRegistryComparator =
        compareBy<AppRegistryEntry> { entry -> normalizeName(entry.displayName) }
            .thenBy { entry -> normalizePackageName(entry.packageName.orEmpty()) }
}
