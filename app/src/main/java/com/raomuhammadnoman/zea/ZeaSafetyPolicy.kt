package com.raomuhammadnoman.zea

import java.util.Locale

enum class SafetyPolicyReason {
    ALLOWED,
    EMPTY_APP_NAME,
    RAW_PACKAGE_IDENTIFIER,
    URI_OR_URL_INPUT,
    INVALID_PACKAGE_NAME,
    INVALID_LAUNCHER_ACTIVITY,
    UNSUPPORTED_SYSTEM_ACTION,
    MISSING_LAUNCH_TARGET
}

data class SafetyPolicyResult(
    val allowed: Boolean,
    val reason: SafetyPolicyReason,
    val message: String
)

/**
 * Structural checks applied before Zea launches or stores an app target.
 *
 * This policy no longer refuses apps by category. An earlier revision blocked
 * banking, payment and wallet apps outright, which conflicts with hiding them:
 * a hidden app is reachable only through Zea, so refusing to open it would
 * leave the owner locked out of their own bank app. What remains here is
 * validation of the launch target itself.
 */
object ZeaSafetyPolicy {
    private val allowedSystemActions = setOf(
        "settings",
        "camera",
        "dialer",
        "messages",
        "contacts",
        "gallery",
        "calendar",
        "calculator",
        "clock",
        "recorder",
        "notes"
    )

    private val packageNamePattern =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val classNamePattern =
        Regex("^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*$")

    private val blockedUriPrefixes = listOf(
        "http:",
        "https:",
        "intent:",
        "market:",
        "mailto:",
        "tel:",
        "sms:",
        "smsto:",
        "geo:"
    )

    fun evaluateRequestedAppName(requestedName: String): SafetyPolicyResult {
        val cleanName = requestedName.trim()

        if (cleanName.isBlank()) {
            return blockedResult(
                reason = SafetyPolicyReason.EMPTY_APP_NAME,
                message = "App name is required."
            )
        }

        if (looksLikeUriOrUrl(cleanName)) {
            return blockedResult(
                reason = SafetyPolicyReason.URI_OR_URL_INPUT,
                message = "URLs, URI commands, and deep-link text cannot be added as allowed apps."
            )
        }

        if (looksLikeRawPackageIdentifier(cleanName)) {
            return blockedResult(
                reason = SafetyPolicyReason.RAW_PACKAGE_IDENTIFIER,
                message = "Enter the installed app name instead of a package identifier."
            )
        }

        return allowedResult("The requested app name passed the safety policy.")
    }

    fun evaluateInstalledAppCandidate(candidate: InstalledAppCandidate): SafetyPolicyResult {
        return evaluateLaunchIdentity(
            displayName = candidate.displayName,
            aliases = emptyList(),
            packageNames = listOf(candidate.packageName),
            launcherActivityName = candidate.launcherActivityName,
            systemAction = null
        )
    }

    fun evaluateUserAllowedApp(app: UserAllowedApp): SafetyPolicyResult {
        return evaluateLaunchIdentity(
            displayName = app.displayName,
            aliases = app.aliases,
            packageNames = listOf(app.packageName),
            launcherActivityName = app.launcherActivityName,
            systemAction = null
        )
    }

    fun evaluateRegistryEntry(entry: AppRegistryEntry): SafetyPolicyResult {
        val packageNames = configuredPackageNames(entry)

        return evaluateLaunchIdentity(
            displayName = entry.displayName,
            aliases = listOf(entry.key) + entry.aliases,
            packageNames = packageNames,
            launcherActivityName = entry.launcherActivityName,
            systemAction = entry.systemAction
        )
    }

    fun isValidPackageName(packageName: String): Boolean {
        return packageNamePattern.matches(packageName.trim())
    }

    fun isValidLauncherActivityName(
        packageName: String,
        launcherActivityName: String
    ): Boolean {
        val cleanPackageName = packageName.trim()
        val cleanActivityName = launcherActivityName.trim()

        if (!isValidPackageName(cleanPackageName) || cleanActivityName.isBlank()) {
            return false
        }

        if (cleanActivityName.startsWith('.')) {
            val relativeName = cleanActivityName.removePrefix(".")
            return relativeName.isNotBlank() && classNamePattern.matches(relativeName)
        }

        return classNamePattern.matches(cleanActivityName) && cleanActivityName.contains('.')
    }

    fun isAllowedSystemAction(systemAction: String): Boolean {
        return systemAction.trim().lowercase(Locale.ROOT) in allowedSystemActions
    }

    fun looksLikeRawPackageIdentifier(value: String): Boolean {
        return isValidPackageName(value.trim())
    }

    fun looksLikeUriOrUrl(value: String): Boolean {
        val normalizedValue = value.trim().lowercase(Locale.ROOT)

        return "://" in normalizedValue ||
                blockedUriPrefixes.any(normalizedValue::startsWith)
    }

    private fun evaluateLaunchIdentity(
        displayName: String,
        aliases: List<String>,
        packageNames: List<String>,
        launcherActivityName: String?,
        systemAction: String?
    ): SafetyPolicyResult {
        val cleanSystemAction = systemAction
            ?.trim()
            ?.takeIf(String::isNotBlank)

        if (cleanSystemAction != null && !isAllowedSystemAction(cleanSystemAction)) {
            return blockedResult(
                reason = SafetyPolicyReason.UNSUPPORTED_SYSTEM_ACTION,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                message = "The configured system action is not approved by the Zyro safety policy."
            )
        }

        if (packageNames.isEmpty() && cleanSystemAction == null) {
            return blockedResult(
                reason = SafetyPolicyReason.MISSING_LAUNCH_TARGET,
                message = "The app does not have an approved package or system launch target."
            )
        }

        packageNames.forEach { packageName ->
            if (!isValidPackageName(packageName)) {
                return blockedResult(
                    reason = SafetyPolicyReason.INVALID_PACKAGE_NAME,
                    message = "The configured package identifier is invalid."
                )
            }
        }

        if (launcherActivityName != null) {
            val primaryPackageName = packageNames.firstOrNull()

            if (
                primaryPackageName == null ||
                !isValidLauncherActivityName(primaryPackageName, launcherActivityName)
            ) {
                return blockedResult(
                    reason = SafetyPolicyReason.INVALID_LAUNCHER_ACTIVITY,
                    message = "The configured launcher activity is invalid for this package."
                )
            }
        }

        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
        return allowedResult("The app passed the Zyro safety policy.")
    }

    private fun configuredPackageNames(entry: AppRegistryEntry): List<String> {
        return (listOfNotNull(entry.packageName) + entry.alternatePackageNames)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
    }

    private fun allowedResult(message: String): SafetyPolicyResult {
        return SafetyPolicyResult(
            allowed = true,
            reason = SafetyPolicyReason.ALLOWED,
            message = message
        )
    }

    private fun blockedResult(
        reason: SafetyPolicyReason,
        message: String
    ): SafetyPolicyResult {
        return SafetyPolicyResult(
            allowed = false,
            reason = reason,
            message = message
        )
    }
}
