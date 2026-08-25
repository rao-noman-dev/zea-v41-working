package com.raomuhammadnoman.zea

import java.util.Locale

private val REGISTRY_KEY_PATTERN = Regex("^[a-z0-9]+(?:_[a-z0-9]+)*$")


/**
 * Defines Zea's complete built-in application registry.
 *
 * Core package applications and Android system actions are declared here.
 * Additional curated package applications are declared in ZeaPopularApps.kt
 * and are merged once without copying their definitions.
 */
private val zeaCorePackageApps: List<AppRegistryEntry> = listOf(
    AppRegistryEntry(
        key = "whatsapp",
        displayName = "WhatsApp",
        aliases = listOf(
            "whatsapp",
            "whats app",
            "watsapp",
            "watsap",
            "whatapp"
        ),
        packageName = "com.whatsapp",
        category = AppCategory.COMMUNICATION
    ),
    AppRegistryEntry(
        key = "chrome",
        displayName = "Chrome",
        aliases = listOf(
            "chrome",
            "google chrome",
            "chrom"
        ),
        packageName = "com.android.chrome",
        category = AppCategory.UTILITIES
    ),
    AppRegistryEntry(
        key = "spotify",
        displayName = "Spotify",
        aliases = listOf(
            "spotify",
            "spotfy",
            "spoti fy"
        ),
        packageName = "com.spotify.music",
        category = AppCategory.MEDIA
    ),
    AppRegistryEntry(
        key = "instagram",
        displayName = "Instagram",
        aliases = listOf(
            "instagram",
            "insta",
            "instgram"
        ),
        packageName = "com.instagram.android",
        category = AppCategory.SOCIAL
    ),
    AppRegistryEntry(
        key = "tiktok",
        displayName = "TikTok",
        aliases = listOf(
            "tiktok",
            "tik tok",
            "ticktok"
        ),
        packageName = "com.zhiliaoapp.musically",
        category = AppCategory.SOCIAL
    ),
    AppRegistryEntry(
        key = "linkedin",
        displayName = "LinkedIn",
        aliases = listOf(
            "linkedin",
            "linked in",
            "linkdin"
        ),
        packageName = "com.linkedin.android",
        category = AppCategory.PRODUCTIVITY
    ),
    AppRegistryEntry(
        key = "camscanner",
        displayName = "CamScanner",
        aliases = listOf(
            "camscanner",
            "cam scanner",
            "camscaner"
        ),
        packageName = "com.intsig.camscanner",
        category = AppCategory.DOCUMENTS
    ),
    AppRegistryEntry(
        key = "yango",
        displayName = "Yango",
        aliases = listOf(
            "yango",
            "yango taxi"
        ),
        packageName = "com.yandex.yango",
        category = AppCategory.TRANSPORT
    ),
    AppRegistryEntry(
        key = "gmail",
        displayName = "Gmail",
        aliases = listOf(
            "gmail",
            "g mail",
            "google mail"
        ),
        packageName = "com.google.android.gm",
        category = AppCategory.PRODUCTIVITY
    ),
    AppRegistryEntry(
        key = "drive",
        displayName = "Google Drive",
        aliases = listOf(
            "drive",
            "google drive",
            "g drive",
            "gdrive"
        ),
        packageName = "com.google.android.apps.docs",
        category = AppCategory.CLOUD_STORAGE
    ),
    AppRegistryEntry(
        key = "maps",
        displayName = "Google Maps",
        aliases = listOf(
            "maps",
            "map",
            "google maps",
            "googlemaps",
            "location",
            "navigation"
        ),
        packageName = "com.google.android.apps.maps",
        category = AppCategory.TRAVEL
    ),
    AppRegistryEntry(
        key = "files",
        displayName = "Files",
        aliases = listOf(
            "files",
            "file",
            "google files",
            "files by google"
        ),
        packageName = "com.google.android.apps.nbu.files",
        category = AppCategory.UTILITIES
    ),
    AppRegistryEntry(
        key = "playstore",
        displayName = "Play Store",
        aliases = listOf(
            "play store",
            "playstore",
            "google play"
        ),
        packageName = "com.android.vending",
        category = AppCategory.UTILITIES
    )
)

private val zeaSystemActionApps: List<AppRegistryEntry> = listOf(
    systemApp(
        key = "settings",
        displayName = "Settings",
        systemAction = "settings",
        aliases = listOf(
            "settings",
            "setting",
            "phone settings",
            "mobile settings"
        )
    ),
    systemApp(
        key = "camera",
        displayName = "Camera",
        systemAction = "camera",
        aliases = listOf(
            "camera",
            "phone camera"
        )
    ),
    systemApp(
        key = "dialer",
        displayName = "Phone",
        systemAction = "dialer",
        aliases = listOf(
            "phone",
            "dialer",
            "phone dialer"
        )
    ),
    systemApp(
        key = "messages",
        displayName = "Messages",
        systemAction = "messages",
        aliases = listOf(
            "messages",
            "message",
            "message app",
            "phone messages",
            "sms"
        )
    ),
    systemApp(
        key = "contacts",
        displayName = "Contacts",
        systemAction = "contacts",
        aliases = listOf(
            "contacts",
            "contact",
            "phone contacts",
            "contact book"
        )
    ),
    systemApp(
        key = "gallery",
        displayName = "Gallery",
        systemAction = "gallery",
        aliases = listOf(
            "gallery",
            "albums",
            "album",
            "photo gallery",
            "photos"
        )
    ),
    systemApp(
        key = "calendar",
        displayName = "Calendar",
        systemAction = "calendar",
        aliases = listOf(
            "calendar",
            "calender",
            "phone calendar"
        )
    ),
    systemApp(
        key = "calculator",
        displayName = "Calculator",
        systemAction = "calculator",
        aliases = listOf(
            "calculator",
            "calculater",
            "calc"
        )
    ),
    systemApp(
        key = "clock",
        displayName = "Clock",
        systemAction = "clock",
        aliases = listOf(
            "clock",
            "phone clock",
            "alarms"
        )
    ),
    systemApp(
        key = "recorder",
        displayName = "Recorder",
        systemAction = "recorder",
        aliases = listOf(
            "recorder",
            "voice recorder",
            "sound recorder",
            "recording app"
        )
    ),
    systemApp(
        key = "notes",
        displayName = "Notes",
        systemAction = "notes",
        aliases = listOf(
            "notes",
            "note",
            "notes app",
            "notepad"
        )
    )
)

val zeaAppRegistry: List<AppRegistryEntry> = buildZeaAppRegistry(
    corePackageApps = zeaCorePackageApps,
    popularPackageApps = zeaPopularApps,
    systemActionApps = zeaSystemActionApps
)

private fun systemApp(
    key: String,
    displayName: String,
    systemAction: String,
    aliases: List<String>
): AppRegistryEntry {
    return AppRegistryEntry(
        systemAction = systemAction,
        category = AppCategory.SYSTEM,
        key = key,
        displayName = displayName,
        aliases = aliases
    )
}

private fun buildZeaAppRegistry(
    corePackageApps: List<AppRegistryEntry>,
    popularPackageApps: List<AppRegistryEntry>,
    systemActionApps: List<AppRegistryEntry>
): List<AppRegistryEntry> {
    val registry = corePackageApps + popularPackageApps + systemActionApps
    val validationErrors = validateRegistry(registry)

    check(validationErrors.isEmpty()) {
        validationErrors.joinToString(
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            prefix = "Invalid Zyro application registry: ",
            separator = "; "
        )
    }

    return registry
}

private fun validateRegistry(
    registry: List<AppRegistryEntry>
): List<String> {
    val errors = mutableListOf<String>()

    registry.forEach { entry ->
        errors += validateRegistryEntry(entry)
    }

    errors += duplicateValueErrors(
        label = "key",
        values = registry.map { entry -> entry.key }
    )
    errors += duplicateValueErrors(
        label = "display name",
        values = registry.map { entry -> entry.displayName }
    )
    errors += duplicateValueErrors(
        label = "package name",
        values = registry.flatMap(::packageNamesForEntry)
    )
    errors += duplicateValueErrors(
        label = "system action",
        values = registry.mapNotNull { entry -> entry.systemAction }
    )
    errors += duplicateSearchNameErrors(registry)

    return errors.distinct()
}

private fun validateRegistryEntry(
    entry: AppRegistryEntry
): List<String> {
    val errors = mutableListOf<String>()
    val cleanKey = entry.key.trim()
    val cleanDisplayName = entry.displayName.trim()
    val cleanPackageName = entry.packageName?.trim().orEmpty()
    val cleanSystemAction = entry.systemAction?.trim().orEmpty()
    val hasPackageTarget = cleanPackageName.isNotBlank()
    val hasSystemTarget = cleanSystemAction.isNotBlank()

    if (!REGISTRY_KEY_PATTERN.matches(cleanKey)) {
        errors += "Invalid registry key: ${entry.key}"
    }

    if (cleanDisplayName.isBlank()) {
        errors += "Blank display name for key: $cleanKey"
    }

    if (hasPackageTarget == hasSystemTarget) {
        errors += "Entry must define exactly one launch-target type: $cleanKey"
    }

    if (entry.source != AppRegistrySource.BUILT_IN) {
        errors += "Static registry entry has an invalid source: $cleanKey"
    }

    if (hasSystemTarget && entry.category != AppCategory.SYSTEM) {
        errors += "System action entry has an invalid category: $cleanKey"
    }

    if (hasPackageTarget && entry.category == AppCategory.SYSTEM) {
        errors += "Package entry has an invalid system category: $cleanKey"
    }

    val normalizedAliases = entry.aliases
        .map(::normalizeRegistryText)

    if (normalizedAliases.any(String::isBlank)) {
        errors += "Blank alias for key: $cleanKey"
    }

    if (normalizedAliases.size != normalizedAliases.distinct().size) {
        errors += "Duplicate alias for key: $cleanKey"
    }

    val packageNames = packageNamesForEntry(entry)

    if (packageNames.size != packageNames.distinctBy(::normalizePackageName).size) {
        errors += "Duplicate package candidate for key: $cleanKey"
    }

    val safetyResult = ZeaSafetyPolicy.evaluateRegistryEntry(entry)

    if (!safetyResult.allowed) {
        errors += "Safety policy rejected $cleanKey: ${safetyResult.reason}"
    }

    return errors
}

private fun packageNamesForEntry(
    entry: AppRegistryEntry
): List<String> {
    return sequenceOf(entry.packageName)
        .plus(entry.alternatePackageNames.asSequence())
        .filterNotNull()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
}

private fun duplicateValueErrors(
    label: String,
    values: List<String>
): List<String> {
    return values
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .groupingBy(::normalizeRegistryText)
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
        .sorted()
        .map { value -> "Duplicate $label: $value" }
}

private fun duplicateSearchNameErrors(
    registry: List<AppRegistryEntry>
): List<String> {
    val ownersBySearchName = linkedMapOf<String, MutableSet<String>>()

    registry.forEach { entry ->
        sequenceOf(entry.key, entry.displayName)
            .plus(entry.aliases.asSequence())
            .map(::normalizeRegistryText)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { searchName ->
                ownersBySearchName
                    .getOrPut(searchName, ::linkedSetOf)
                    .add(entry.key)
            }
    }

    return ownersBySearchName
        .filterValues { owners -> owners.size > 1 }
        .map { (searchName, owners) ->
            "Ambiguous search name '$searchName' belongs to ${owners.sorted().joinToString()}"
        }
}

private fun normalizeRegistryText(value: String): String {
    return value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun normalizePackageName(value: String): String {
    return value.trim().lowercase(Locale.ROOT)
}

