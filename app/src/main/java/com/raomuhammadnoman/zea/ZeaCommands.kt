package com.raomuhammadnoman.zea

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log
import java.util.Locale


private val appCommandNoiseWords = setOf(
    "open",
    "launch",
    "start",
    "show",
    "run",
    "please",
    "kindly",
    "zea",
    "app",
    "application",
    "khol",
    "kholo",
    "kholdo",
    "kholna",
    "kar",
    "karo",
    "kardo",
    "kr",
    "krdo",
    "do",
    "mujhe",
    "mera",
    "meri",
    "mere"
)

private val appOpenIntentPattern = Regex(
    pattern = "\\b(open|launch|start|show|run|khol|kholo|kholdo|kholna)\\b",
    option = RegexOption.IGNORE_CASE
)


private const val ZEA_LAUNCH_LOG_TAG = "ZeaLaunch"

private val simpleAppOpenCommandPattern = Regex(
    pattern = "^\\s*(open|launch|start)\\s+(.+?)\\s*$",
    option = RegexOption.IGNORE_CASE
)

private val simpleAppNameSuffixPattern = Regex(
    pattern = "\\s+(app|application)\\s*$",
    option = RegexOption.IGNORE_CASE
)

internal data class ResolvedAppOpenTarget(
    val command: ZeaCommand,
    val entry: AppRegistryEntry
)

private data class AppLookupSnapshot(
    val entries: List<AppRegistryEntry>,
    val entriesByKey: Map<String, AppRegistryEntry>,
    val entriesByExactName: Map<String, AppRegistryEntry>
)

internal object ZeaAppLookupCache {
    private val rebuildLock = Any()

    @Volatile
    private var snapshot: AppLookupSnapshot? = null

    fun warm(context: Context) {
        snapshot(context.applicationContext)
    }

    fun entries(context: Context?): List<AppRegistryEntry> {
        return if (context == null) {
            zeaAppRegistry
        } else {
            snapshot(context.applicationContext).entries
        }
    }

    fun findByKey(
        context: Context,
        appKey: String
    ): AppRegistryEntry? {
        val normalizedKey = normalizeFastAppName(appKey)
        if (normalizedKey.isBlank()) return null
        return snapshot(context.applicationContext).entriesByKey[normalizedKey]
    }

    fun findByExactName(
        context: Context,
        requestedName: String
    ): AppRegistryEntry? {
        val normalizedName = normalizeFastAppName(requestedName)
        if (normalizedName.isBlank()) return null
        return snapshot(context.applicationContext).entriesByExactName[normalizedName]
    }

    fun invalidate(reason: String) {
        synchronized(rebuildLock) {
            snapshot = null
        }
        Log.i(ZEA_LAUNCH_LOG_TAG, "app lookup cache invalidated reason=$reason")
    }

    private fun snapshot(context: Context): AppLookupSnapshot {
        snapshot?.let { cached ->
            Log.i(
                ZEA_LAUNCH_LOG_TAG,
                "app lookup cache hit entries=${cached.entries.size}"
            )
            return cached
        }

        return synchronized(rebuildLock) {
            snapshot?.let { cached ->
                Log.i(
                    ZEA_LAUNCH_LOG_TAG,
                    "app lookup cache hit after lock entries=${cached.entries.size}"
                )
                return@synchronized cached
            }

            val started = SystemClock.elapsedRealtime()
            Log.i(ZEA_LAUNCH_LOG_TAG, "app lookup cache rebuild start")

            val privatePackages = loadPrivateApps(context)
                .asSequence()
                .map { app -> app.packageName.trim().lowercase(Locale.ROOT) }
                .filter(String::isNotBlank)
                .toSet()
            val userEntries = loadUserAllowedRegistryEntries(context)
            val entries = (zeaAppRegistry + userEntries)
                .asSequence()
                .filter { entry -> ZeaSafetyPolicy.evaluateRegistryEntry(entry).allowed }
                .filterNot { entry ->
                    sequenceOf(entry.packageName)
                        .plus(entry.alternatePackageNames.asSequence())
                        .filterNotNull()
                        .map { packageName -> packageName.trim().lowercase(Locale.ROOT) }
                        .any(privatePackages::contains)
                }
                .distinctBy { entry -> normalizeFastAppName(entry.key) }
                .toList()

            val entriesByKey = LinkedHashMap<String, AppRegistryEntry>()
            val entriesByName = LinkedHashMap<String, AppRegistryEntry>()
            val ambiguousNames = HashSet<String>()

            entries.forEach { entry ->
                normalizeFastAppName(entry.key)
                    .takeIf(String::isNotBlank)
                    ?.let { normalizedKey -> entriesByKey[normalizedKey] = entry }

                sequenceOf(entry.displayName, entry.key)
                    .plus(entry.aliases.asSequence())
                    .map(::normalizeFastAppName)
                    .filter(String::isNotBlank)
                    .distinct()
                    .forEach { normalizedName ->
                        if (normalizedName in ambiguousNames) {
                            return@forEach
                        }

                        val existing = entriesByName[normalizedName]
                        if (existing == null || existing.key == entry.key) {
                            entriesByName[normalizedName] = entry
                        } else {
                            entriesByName.remove(normalizedName)
                            ambiguousNames += normalizedName
                        }
                    }
            }

            AppLookupSnapshot(
                entries = entries.toList(),
                entriesByKey = entriesByKey.toMap(),
                entriesByExactName = entriesByName.toMap()
            ).also { rebuilt ->
                snapshot = rebuilt
                Log.i(
                    ZEA_LAUNCH_LOG_TAG,
                    "app lookup cache rebuild end entries=${rebuilt.entries.size} names=${rebuilt.entriesByExactName.size} elapsedMs=${SystemClock.elapsedRealtime() - started}"
                )
            }
        }
    }
}

private fun normalizeFastAppName(value: String): String {
    return value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

internal fun resolveSimpleAppOpenCommand(
    context: Context,
    input: String
): ResolvedAppOpenTarget? {
    val matchingStarted = SystemClock.elapsedRealtime()
    Log.i(ZEA_LAUNCH_LOG_TAG, "fast-path matching start")

    val match = simpleAppOpenCommandPattern.matchEntire(input)
    if (match == null) {
        Log.i(
            ZEA_LAUNCH_LOG_TAG,
            "fast-path matching end matched=false elapsedMs=${SystemClock.elapsedRealtime() - matchingStarted}"
        )
        return null
    }

    val requestedName = match.groupValues[2]
        .replace(simpleAppNameSuffixPattern, "")
        .trim()

    Log.i(
        ZEA_LAUNCH_LOG_TAG,
        "fast-path matching end matched=true elapsedMs=${SystemClock.elapsedRealtime() - matchingStarted}"
    )

    val resolutionStarted = SystemClock.elapsedRealtime()
    val entry = ZeaAppLookupCache.findByExactName(context, requestedName)
    Log.i(
        ZEA_LAUNCH_LOG_TAG,
        "fast-path target resolution name=${normalizeFastAppName(requestedName)} found=${entry != null} elapsedMs=${SystemClock.elapsedRealtime() - resolutionStarted}"
    )

    return entry?.let { resolvedEntry ->
        ResolvedAppOpenTarget(
            command = ZeaCommand(
                rawCommand = input,
                action = "open_app",
                appKey = resolvedEntry.key,
                appDisplayName = resolvedEntry.displayName,
                status = "success",
                message = "Opening ${resolvedEntry.displayName}."
            ),
            entry = resolvedEntry
        )
    }
}

private data class RankedAppMatch(
    val entry: AppRegistryEntry,
    val score: Int
)

private fun normalizeAppToken(raw: String): String {
    return raw
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")
}

private fun normalizeAppTokens(value: String): List<String> {
    return value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .map(::normalizeAppToken)
        .filter { token ->
            token.isNotBlank() && token !in appCommandNoiseWords
        }
}

private fun appEditDistance(
    left: String,
    right: String
): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length

    var previous = IntArray(right.length + 1) { index -> index }

    for (leftIndex in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1

        for (rightIndex in right.indices) {
            val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1

            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + substitutionCost
            )
        }

        previous = current
    }

    return previous[right.length]
}

private fun appAliasScore(
    commandTokens: List<String>,
    alias: String
): Int {
    val aliasTokens = normalizeAppTokens(alias)

    if (aliasTokens.isEmpty() || commandTokens.size < aliasTokens.size) {
        return 0
    }

    val aliasCompact = aliasTokens.joinToString("")
    val commandCompact = commandTokens.joinToString("")
    var bestScore = 0

    if (commandTokens == aliasTokens) {
        bestScore = 4_000 + aliasTokens.size * 100 + aliasCompact.length
    } else if (commandCompact == aliasCompact) {
        bestScore = 3_600 + aliasTokens.size * 100 + aliasCompact.length
    }

    val lastStart = commandTokens.size - aliasTokens.size

    for (startIndex in 0..lastStart) {
        val candidateTokens = commandTokens.subList(
            startIndex,
            startIndex + aliasTokens.size
        )
        val candidateCompact = candidateTokens.joinToString("")

        if (candidateTokens == aliasTokens) {
            bestScore = maxOf(
                bestScore,
                3_000 + aliasTokens.size * 100 + aliasCompact.length
            )
            continue
        }

        if (candidateCompact == aliasCompact) {
            bestScore = maxOf(
                bestScore,
                2_700 + aliasTokens.size * 100 + aliasCompact.length
            )
            continue
        }

        val maximumLength = maxOf(candidateCompact.length, aliasCompact.length)
        val allowedDistance = when {
            maximumLength >= 9 -> 2
            maximumLength >= 5 -> 1
            else -> 0
        }

        if (allowedDistance == 0) {
            continue
        }

        val distance = appEditDistance(candidateCompact, aliasCompact)

        if (distance <= allowedDistance) {
            bestScore = maxOf(
                bestScore,
                1_500 + aliasTokens.size * 50 + maximumLength - distance * 100
            )
        }
    }

    return bestScore
}

private fun availableAppRegistry(context: Context?): List<AppRegistryEntry> {
    return ZeaAppLookupCache.entries(context)
}

private fun detectAppFromRegistry(
    command: String,
    registry: List<AppRegistryEntry>
): AppRegistryEntry? {
    val commandTokens = normalizeAppTokens(command)

    if (commandTokens.isEmpty()) {
        return null
    }

    val rankedApps = registry
        .asSequence()
        .map { app ->
            val searchableNames = sequenceOf(app.displayName, app.key)
                .plus(app.aliases.asSequence())
                .distinctBy { name -> name.lowercase(Locale.ROOT) }

            val score = searchableNames.maxOfOrNull { alias ->
                appAliasScore(commandTokens, alias)
            } ?: 0

            RankedAppMatch(entry = app, score = score)
        }
        .filter { match -> match.score > 0 }
        .sortedWith(
            compareByDescending<RankedAppMatch> { match -> match.score }
                .thenByDescending { match -> normalizeAppTokens(match.entry.displayName).size }
                .thenBy { match -> match.entry.displayName.lowercase(Locale.ROOT) }
        )
        .toList()

    val bestMatch = rankedApps.firstOrNull() ?: return null
    val tiedBestMatches = rankedApps
        .takeWhile { match -> match.score == bestMatch.score }
        .map(RankedAppMatch::entry)
        .distinctBy { entry ->
            listOf(
                entry.packageName.orEmpty(),
                entry.systemAction.orEmpty(),
                entry.launcherActivityName.orEmpty()
            ).joinToString("|").lowercase(Locale.ROOT)
        }

    return tiedBestMatches.singleOrNull()
}

fun detectApp(command: String): AppRegistryEntry? {
    return detectAppFromRegistry(
        command = command,
        registry = zeaAppRegistry
    )
}

fun detectApp(
    context: Context,
    command: String
): AppRegistryEntry? {
    return detectAppFromRegistry(
        command = command,
        registry = availableAppRegistry(context)
    )
}

private fun supportedAppsSummary(registry: List<AppRegistryEntry>): String {
    val builtInCount = registry.count { entry ->
        entry.source == AppRegistrySource.BUILT_IN
    }
    val userAllowedCount = registry.count { entry ->
        entry.source == AppRegistrySource.USER_ALLOWED
    }

    return if (userAllowedCount == 0) {
        "$builtInCount configured apps"
    } else {
        "$builtInCount configured apps and $userAllowedCount verified user apps"
    }
}

private fun supportedAppsSummary(context: Context?): String {
    return if (context == null) {
        supportedAppsText()
    } else {
        supportedAppsText(context)
    }
}

fun supportedAppsText(): String {
    return supportedAppsSummary(zeaAppRegistry)
}

fun supportedAppsText(context: Context): String {
    return supportedAppsSummary(availableAppRegistry(context))
}

private fun requestedAppName(command: String): String {
    var requestedName = command.trim()

    appCommandNoiseWords.forEach { noiseWord ->
        requestedName = requestedName.replace(
            Regex("\\b${Regex.escape(noiseWord)}\\b", RegexOption.IGNORE_CASE),
            " "
        )
    }

    return requestedName
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun blockedAppCommandOrNull(
    input: String,
    command: String,
    registry: List<AppRegistryEntry>
): ZeaCommand? {
    val requestedName = requestedAppName(command)
    val requestedTokens = normalizeAppTokens(requestedName)
    val exactConfiguredName = registry.any { entry ->
        sequenceOf(entry.displayName, entry.key)
            .plus(entry.aliases.asSequence())
            .any { name -> normalizeAppTokens(name) == requestedTokens }
    }
    val safetyResult = ZeaSafetyPolicy.evaluateRequestedAppName(
        requestedName.ifBlank { command.trim() }
    )

    if (safetyResult.allowed || exactConfiguredName) {
        return null
    }

    return ZeaCommand(
        rawCommand = input,
        action = "blocked_app",
        appKey = "blocked",
        appDisplayName = "Blocked App",
        status = "error",
        message = safetyResult.message
    )
}

fun hasAppOpenIntent(command: String): Boolean {
    return appOpenIntentPattern.containsMatchIn(command)
}

fun isContactsAuditCommand(command: String): Boolean {
    val cleanCommand = command.trim().lowercase(Locale.ROOT)

    val hasContactWord = cleanCommand.contains("contact") ||
            cleanCommand.contains("contacts")

    val hasAuditWord = cleanCommand.contains("audit") ||
            cleanCommand.contains("list") ||
            cleanCommand.contains("history") ||
            cleanCommand.contains("record") ||
            cleanCommand.contains("dikhao") ||
            cleanCommand.contains("show") ||
            cleanCommand.contains("sare") ||
            cleanCommand.contains("saare") ||
            cleanCommand.contains("all")

    val hasSavedOrWhatsAppContext = cleanCommand.contains("whatsapp") ||
            cleanCommand.contains("saved") ||
            cleanCommand.contains("save") ||
            cleanCommand.contains("phone") ||
            cleanCommand.contains("number") ||
            cleanCommand.contains("history") ||
            cleanCommand.contains("audit") ||
            cleanCommand.contains("list")

    return hasContactWord && hasAuditWord && hasSavedOrWhatsAppContext
}

fun isConfirmCommand(input: String): Boolean {
    val command = input.trim().lowercase(Locale.ROOT)

    return command == "yes" ||
            command == "y" ||
            command == "confirm" ||
            command == "ok" ||
            command == "okay" ||
            command == "haan" ||
            command == "han" ||
            command == "send" ||
            command == "send karo" ||
            command == "kar do" ||
            command == "bhej do"
}

fun isCancelCommand(input: String): Boolean {
    val command = input.trim().lowercase(Locale.ROOT)

    return command == "no" ||
            command == "n" ||
            command == "cancel" ||
            command == "stop" ||
            command == "nahi" ||
            command == "mat karo" ||
            command == "cancel karo"
}

fun removeCommandWords(input: String, removableWords: List<String>): String {
    var cleaned = input.lowercase(Locale.ROOT)

    removableWords.forEach { word ->
        cleaned = cleaned.replace(
            Regex("\\b${Regex.escape(word)}\\b"),
            " "
        )
    }

    return cleaned
        .replace(Regex("\\s+"), " ")
        .trim()
}


private val whatsAppReferencePattern = Regex(
    pattern = "\\b(whatsapp|watsapp|wa)\\b",
    option = RegexOption.IGNORE_CASE
)

private val whatsAppMessageSignals = listOf(
    "message",
    "msg",
    "likho",
    "write",
    "send",
    "bhejo",
    "bhajo",
    "tell",
    "bolo",
    "kaho",
    "kehdo",
    "keh do"
)

private val whatsAppCommonRemovableWords = listOf(
    "zea",
    "open",
    "kholo",
    "khol",
    "whatsapp",
    "watsapp",
    "wa",
    "karo",
    "kro",
    "kardo",
    "kar do",
    "krdo",
    "kr",
    "kar",
    "do",
    "ko",
    "ka",
    "ki",
    "ke",
    "mujhe",
    "please",
    "with"
)

private val whatsAppChatRemovableWords =
    whatsAppCommonRemovableWords + listOf("chat", "contact")

private val whatsAppMessageContactRemovableWords =
    whatsAppCommonRemovableWords +
            whatsAppMessageSignals +
            listOf("keh", "to", "on")

private fun hasWhatsAppReference(command: String): Boolean {
    return whatsAppReferencePattern.containsMatchIn(command)
}

private fun hasWhatsAppMessageSignal(command: String): Boolean {
    val paddedCommand = " ${command.lowercase(Locale.ROOT)} "

    return whatsAppMessageSignals.any { signal ->
        paddedCommand.contains(" $signal ")
    }
}

private fun whatsAppMessageCommand(
    input: String,
    status: String,
    message: String,
    contactQuery: String = "",
    messageText: String = ""
): ZeaCommand {
    return ZeaCommand(
        rawCommand = input,
        action = "prefill_whatsapp_message",
        appKey = "whatsapp",
        appDisplayName = "WhatsApp",
        status = status,
        message = message,
        contactQuery = contactQuery,
        messageText = messageText
    )
}

private fun whatsAppChatCommand(
    input: String,
    status: String,
    message: String,
    contactQuery: String = ""
): ZeaCommand {
    return ZeaCommand(
        rawCommand = input,
        action = "open_whatsapp_chat",
        appKey = "whatsapp",
        appDisplayName = "WhatsApp",
        status = status,
        message = message,
        contactQuery = contactQuery
    )
}

fun extractWhatsAppContactQuery(input: String): String {
    return removeCommandWords(input, whatsAppChatRemovableWords)
}


private fun smartWords(value: String): List<String> {
    return value
        .trim()
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
}


fun looksLikeContactFirstMessage(command: String): Boolean {
    val words = smartWords(command)

    if (words.size < 3) {
        return false
    }

    val lowerCommand = command.lowercase(Locale.ROOT)

    val blockedStarts = listOf(
        "open",
        "kholo",
        "khol",
        "youtube",
        "whatsapp",
        "watsapp",
        "chrome",
        "spotify",
        "maps",
        "map",
        "settings",
        "camera",
        "phone",
        "dialer",
        "search",
        "find",
        "contact",
        "number"
    )

    if (blockedStarts.any { lowerCommand.startsWith(it) }) {
        return false
    }

    val firstWord = words.first().lowercase(Locale.ROOT)

    return firstWord.length >= 2 && commandHasContactFirstMessageSignal(command)
}

fun buildContactFirstMessageCommand(input: String): ZeaCommand {
    val cleanedInput = input.trim()

    if (cleanedInput.isBlank()) {
        return ZeaCommand(
            rawCommand = input,
            action = "unknown",
            appKey = "unknown",
            appDisplayName = "Unknown",
            status = "error",
            message = "Command not recognized. Example: Open WhatsApp or Tell John: I will meet you tomorrow"
        )
    }

    return whatsAppMessageCommand(
        input = input,
        status = "success",
        message = "WhatsApp message is ready to pre-fill.",
        contactQuery = cleanedInput
    )
}

fun isNaturalWhatsAppMessageCommand(command: String): Boolean {
    val normalizedCommand = command.lowercase(Locale.ROOT)
    val hasColonMessageShape =
        hasWhatsAppReference(normalizedCommand) && normalizedCommand.contains(":")

    if (!hasWhatsAppMessageSignal(normalizedCommand) && !hasColonMessageShape) {
        return false
    }

    val request = extractWhatsAppMessageRequest(normalizedCommand)

    return request.contactQuery.isNotBlank() && request.messageText.isNotBlank()
}

fun buildWhatsAppMessageCommand(input: String): ZeaCommand {
    val request = extractWhatsAppMessageRequest(input)

    if (request.contactQuery.isBlank()) {
        return whatsAppMessageCommand(
            input = input,
            status = "error",
            message = "Contact name is missing. Example: Tell John: I will meet you tomorrow"
        )
    }

    if (request.messageText.isBlank()) {
        return whatsAppMessageCommand(
            input = input,
            status = "error",
            message = "Message text is missing. Example: Tell John: I will meet you tomorrow"
        )
    }

    return whatsAppMessageCommand(
        input = input,
        status = "success",
        message = "WhatsApp message is ready for confirmation.",
        contactQuery = request.contactQuery,
        messageText = request.messageText
    )
}

fun isNaturalWhatsAppChatCommand(command: String): Boolean {
    val normalizedCommand = command.lowercase(Locale.ROOT)
    val requestedName = requestedAppName(normalizedCommand)

    if (
        ZeaSafetyPolicy.looksLikeRawPackageIdentifier(requestedName) ||
        ZeaSafetyPolicy.looksLikeUriOrUrl(requestedName)
    ) {
        return false
    }

    val hasWhatsAppWord = hasWhatsAppReference(normalizedCommand)
    val hasChatWord = normalizedCommand.contains("chat")
    val hasOpenWord = hasAppOpenIntent(normalizedCommand)
    val contactQuery = extractWhatsAppContactQuery(normalizedCommand)

    if (hasWhatsAppMessageSignal(command) || contactQuery.isBlank()) {
        return false
    }

    return (hasChatWord && hasOpenWord) ||
            (hasChatWord && contactQuery.isNotBlank()) ||
            (hasWhatsAppWord && contactQuery.isNotBlank())
}

fun buildOpenWhatsAppChatCommand(input: String): ZeaCommand {
    val contactQuery = extractWhatsAppContactQuery(input)

    if (contactQuery.isBlank()) {
        return whatsAppChatCommand(
            input = input,
            status = "error",
            message = "Contact name is missing. Example: Open WhatsApp chat with John"
        )
    }

    return whatsAppChatCommand(
        input = input,
        status = "success",
        message = "Opening WhatsApp chat for: $contactQuery",
        contactQuery = contactQuery
    )
}

private fun cleanWhatsAppMessageText(value: String): String {
    var cleaned = value.trim()

    cleaned = cleaned.trimStart { character ->
        character == ':' ||
                character == ',' ||
                character == '\u060C' ||
                character == '-' ||
                character == '\u2013' ||
                character == '\u2014'
    }.trim()

    val removableStarts = listOf(
        "ki ",
        "ke ",
        "kay ",
        "that "
    )

    removableStarts.forEach { word ->
        if (cleaned.lowercase(Locale.ROOT).startsWith(word)) {
            cleaned = cleaned.drop(word.length).trim()
        }
    }

    return cleaned
}

private fun cleanWhatsAppContactQuery(contactPart: String): String {
    return removeCommandWords(contactPart, whatsAppMessageContactRemovableWords)
}

private data class ResolverToken(
    val original: String,
    val normalized: String,
    val startChar: Int,
    val endChar: Int,
    val wordIndex: Int
)

private data class ResolverWordSpan(
    val startWordIndex: Int,
    val endWordIndexExclusive: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String
)


private val resolverWordRegex = Regex("[\\p{L}\\p{N}]+")

private val resolverActionPhrases = listOf(
    "send a message to",
    "send message to",
    "write a message to",
    "write message to",
    "send text to",
    "text",
    "message",
    "msg",
    "send",
    "write",
    "tell",
    "ask",
    "message karo",
    "msg karo",
    "message bhejo",
    "message bhajo",
    "msg bhejo",
    "msg bhajo",
    "message likho",
    "msg likho",
    "bhejo",
    "bhajo",
    "likho",
    "bolo",
    "kaho",
    "keh do",
    "kehdo",
    "send karo",
    "text karo"
)

private val resolverMessageMarkerPhrases = listOf(
    "that",
    "and say",
    "and tell",
    "saying",
    "ki",
    "ke",
    "kay",
    "keh",
    "bol ke",
    "keh ke"
)

private val resolverReceiverConnectorPhrases = listOf(
    "to",
    "ko",
    "for"
)

private val resolverSoftFillerPhrases = listOf(
    "zea",
    "please",
    "plz",
    "yar",
    "yaar",
    "ab",
    "zara",
    "jara",
    "kindly",
    "now",
    "okay",
    "ok",
    "aisa karo",
    "aisa karna",
    "can you",
    "could you",
    "will you"
)

private fun resolverNormalizeWord(value: String): String {
    return value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
        .trim()
}

private fun resolverTokens(value: String): List<ResolverToken> {
    return resolverWordRegex.findAll(value)
        .mapIndexed { index, match ->
            ResolverToken(
                original = match.value,
                normalized = resolverNormalizeWord(match.value),
                startChar = match.range.first,
                endChar = match.range.last + 1,
                wordIndex = index
            )
        }
        .filter { it.normalized.isNotBlank() }
        .toList()
}

private fun resolverPhraseTokens(phrase: String): List<String> {
    return resolverTokens(phrase)
        .map { it.normalized }
        .filter { it.isNotBlank() }
}

private fun resolverTrimSeparators(value: String): String {
    return value
        .trim()
        .trim { character ->
            character == ':' ||
                    character == ',' ||
                    character == '\u060C' ||
                    character == ';' ||
                    character == '\u061B' ||
                    character == '-' ||
                    character == '\u2013' ||
                    character == '\u2014'
        }
        .trim()
}


private data class ResolverContactMatchCandidate(
    val contact: ContactResult,
    val contactSpan: ResolverWordSpan,
    val matchedPhrase: String,
    val score: Int,
    val reason: String,
    val matchedWordCount: Int,
    val contactWordCount: Int
)

private fun resolverTransliterateContactText(value: String): String {
    val builder = StringBuilder()

    value.forEach { character ->
        val mappedValue = when (character) {
            '\u0627', '\u0622', '\u0623', '\u0625' -> "a"
            '\u0628' -> "b"
            '\u067E' -> "p"
            '\u062A', '\u0679', '\u0637' -> "t"
            '\u062B', '\u0633', '\u0635' -> "s"
            '\u062C' -> "j"
            '\u0686' -> "ch"
            '\u062D', '\u06C1', '\u06BE', '\u06C3', '\u0647' -> "h"
            '\u062E' -> "kh"
            '\u062F', '\u0688' -> "d"
            '\u0630', '\u0632', '\u0698', '\u0636', '\u0638' -> "z"
            '\u0631', '\u0691' -> "r"
            '\u0634' -> "sh"
            '\u0639' -> "a"
            '\u063A' -> "gh"
            '\u0641' -> "f"
            '\u0642' -> "q"
            '\u06A9', '\u0643' -> "k"
            '\u06AF' -> "g"
            '\u0644' -> "l"
            '\u0645' -> "m"
            '\u0646', '\u06BA' -> "n"
            '\u0648' -> "o"
            '\u06CC', '\u064A', '\u06D2' -> "i"
            '\u0621' -> ""
            else -> character.toString()
        }

        builder.append(mappedValue)
    }

    return builder.toString()
}

private fun resolverPhoneticSkeleton(value: String): String {
    val romanized = resolverTransliterateContactText(value)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")

    if (romanized.isBlank()) {
        return ""
    }

    return romanized
        .replace(Regex("[aeiou]+"), "")
        .replace(Regex("(.)\\1+"), "$1")
        .trim()
}

private fun resolverPhoneIdentityKey(phoneNumber: String): String {
    val digits = phoneNumber.filter { character ->
        character.isDigit()
    }

    if (digits.isBlank()) {
        return ""
    }

    return if (digits.length >= 10) {
        digits.takeLast(10)
    } else {
        digits
    }
}

private fun resolverContactIdentityKey(contact: ContactResult): String {
    val phoneKey = resolverPhoneIdentityKey(contact.phoneNumber)

    if (phoneKey.isNotBlank()) {
        return "phone|$phoneKey"
    }

    val nameKey = resolverNormalizeWord(
        resolverTransliterateContactText(contact.name)
    )
        .replace(Regex("\\s+"), " ")
        .trim()

    return "name|$nameKey"
}

private fun resolverDistinctContacts(
    contacts: List<ContactResult>
): List<ContactResult> {
    return contacts.distinctBy { contact ->
        resolverContactIdentityKey(contact)
    }
}
private fun resolverContactWordVariants(word: String): Set<String> {
    val normalized = resolverNormalizeWord(word)

    if (normalized.isBlank()) {
        return emptySet()
    }

    val variants = mutableSetOf<String>()

    val romanized = resolverNormalizeWord(
        resolverTransliterateContactText(normalized)
    )

    variants.add(normalized)

    if (romanized.isNotBlank()) {
        variants.add(romanized)
    }

    val normalizedSkeleton = resolverPhoneticSkeleton(normalized)
    val romanizedSkeleton = resolverPhoneticSkeleton(romanized)

    if (normalizedSkeleton.length >= 2) {
        variants.add(normalizedSkeleton)
    }

    if (romanizedSkeleton.length >= 2) {
        variants.add(romanizedSkeleton)
    }

    val suffixGroups = listOf(
        listOf("wala", "wale", "wali"),
        listOf("shop", "shops"),
        listOf("office", "offices")
    )

    val currentVariants = variants.toList()

    currentVariants.forEach { variant ->
        suffixGroups.forEach { group ->
            group.forEach { suffix ->
                if (variant.endsWith(suffix)) {
                    val root = variant.removeSuffix(suffix)

                    group.forEach { alternateSuffix ->
                        variants.add(root + alternateSuffix)
                    }
                }
            }
        }
    }

    return variants
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun resolverContactWordsMatch(
    commandWord: String,
    contactWord: String
): Boolean {
    val commandVariants = resolverContactWordVariants(commandWord)
    val contactVariants = resolverContactWordVariants(contactWord)

    return commandVariants.any { it in contactVariants }
}

private fun resolverContactSequenceMatches(
    phraseTokens: List<String>,
    contactTokens: List<String>,
    contactStartIndex: Int
): Boolean {
    if (phraseTokens.isEmpty()) {
        return false
    }

    if (contactStartIndex < 0 || contactStartIndex + phraseTokens.size > contactTokens.size) {
        return false
    }

    return phraseTokens.indices.all { offset ->
        resolverContactWordsMatch(
            commandWord = phraseTokens[offset],
            contactWord = contactTokens[contactStartIndex + offset]
        )
    }
}

private fun resolverScorePhraseAgainstContact(
    phraseTokens: List<String>,
    contactTokens: List<String>
): Pair<Int, String>? {
    if (phraseTokens.isEmpty() || contactTokens.isEmpty()) {
        return null
    }

    if (phraseTokens.size > contactTokens.size) {
        return null
    }

    val matchesFromStart = resolverContactSequenceMatches(
        phraseTokens = phraseTokens,
        contactTokens = contactTokens,
        contactStartIndex = 0
    )
    val isFullName = phraseTokens.size == contactTokens.size && matchesFromStart

    if (isFullName) {
        return Pair(
            120 + phraseTokens.size * 20,
            "Full saved contact name matched."
        )
    }

    if (matchesFromStart) {
        return Pair(
            85 + phraseTokens.size * 15,
            "Contact prefix matched."
        )
    }

    for (startIndex in contactTokens.indices) {
        val isMiddleOrSuffixMatch =
            resolverContactSequenceMatches(
                phraseTokens = phraseTokens,
                contactTokens = contactTokens,
                contactStartIndex = startIndex
            )

        if (isMiddleOrSuffixMatch) {
            return Pair(
                70 + phraseTokens.size * 15,
                "Contact middle/suffix phrase matched."
            )
        }
    }

    return null
}

private fun resolverBlockedContactMatchTokens(): Set<String> {
    val blockedPhrases =
        resolverActionPhrases +
                resolverMessageMarkerPhrases +
                resolverReceiverConnectorPhrases +
                resolverSoftFillerPhrases +
                listOf(
                    "karo",
                    "kar",
                    "do",
                    "say",
                    "and",
                    "the",
                    "a",
                    "an"
                )

    return blockedPhrases
        .flatMap { resolverPhraseTokens(it) }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun resolverLooksLikeBadContactPhrase(phraseTokens: List<String>): Boolean {
    if (phraseTokens.isEmpty()) {
        return true
    }

    val blockedTokens = resolverBlockedContactMatchTokens()

    return phraseTokens.all { token ->
        token in blockedTokens
    }
}

private fun resolverBuildSpanFromTokens(
    rawCommand: String,
    tokens: List<ResolverToken>,
    startIndex: Int,
    endIndexExclusive: Int
): ResolverWordSpan {
    val startToken = tokens[startIndex]
    val endToken = tokens[endIndexExclusive - 1]

    return ResolverWordSpan(
        startWordIndex = startIndex,
        endWordIndexExclusive = endIndexExclusive,
        startChar = startToken.startChar,
        endChar = endToken.endChar,
        text = rawCommand.substring(startToken.startChar, endToken.endChar)
    )
}

private fun resolverFindContactMatchCandidates(
    rawCommand: String,
    contacts: List<ContactResult>
): List<ResolverContactMatchCandidate> {
    val commandTokens = resolverTokens(rawCommand)

    if (commandTokens.isEmpty() || contacts.isEmpty()) {
        return emptyList()
    }

    val candidates = mutableListOf<ResolverContactMatchCandidate>()
    val maxContactWordsToTry = minOf(6, commandTokens.size)

    for (startIndex in commandTokens.indices) {
        val maxEndIndex = minOf(commandTokens.size, startIndex + maxContactWordsToTry)

        for (endIndexExclusive in (startIndex + 1)..maxEndIndex) {
            val phraseTokens = commandTokens
                .subList(startIndex, endIndexExclusive)
                .map { it.normalized }
                .filter { it.isNotBlank() }

            if (resolverLooksLikeBadContactPhrase(phraseTokens)) {
                continue
            }

            val span = resolverBuildSpanFromTokens(
                rawCommand = rawCommand,
                tokens = commandTokens,
                startIndex = startIndex,
                endIndexExclusive = endIndexExclusive
            )

            contacts.forEach { contact ->
                val contactTokens = resolverPhraseTokens(contact.name)

                val scoreResult = resolverScorePhraseAgainstContact(
                    phraseTokens = phraseTokens,
                    contactTokens = contactTokens
                )

                if (scoreResult != null) {
                    candidates.add(
                        ResolverContactMatchCandidate(
                            contact = contact,
                            contactSpan = span,
                            matchedPhrase = span.text,
                            score = scoreResult.first,
                            reason = scoreResult.second,
                            matchedWordCount = phraseTokens.size,
                            contactWordCount = contactTokens.size
                        )
                    )
                }
            }
        }
    }

    return candidates
        .sortedWith(
            compareByDescending<ResolverContactMatchCandidate> { it.score }
                .thenByDescending { it.matchedWordCount }
                .thenBy { it.contactSpan.startChar }
        )
        .distinctBy {
            "${it.contact.name.lowercase(Locale.ROOT)}-${it.contact.phoneNumber}-${it.contactSpan.startChar}-${it.contactSpan.endChar}"
        }
}

private fun resolverConfidenceForScore(score: Int): SmartWhatsAppConfidence {
    return when {
        score >= 120 -> SmartWhatsAppConfidence.HIGH
        score >= 85 -> SmartWhatsAppConfidence.MEDIUM
        score > 0 -> SmartWhatsAppConfidence.LOW
        else -> SmartWhatsAppConfidence.NONE
    }
}

private fun resolverTopContactChoices(
    candidates: List<ResolverContactMatchCandidate>
): List<ResolverContactMatchCandidate> {
    if (candidates.isEmpty()) {
        return emptyList()
    }

    val topCandidate = candidates.first()
    val topScore = topCandidate.score

    val exactTopChoices = candidates
        .filter { candidate ->
            candidate.score == topScore
        }
        .distinctBy { candidate ->
            "${candidate.contact.name.lowercase(Locale.ROOT)}-${candidate.contact.phoneNumber}"
        }

    if (topCandidate.matchedWordCount == topCandidate.contactWordCount) {
        return exactTopChoices
    }

    val topPhraseTokens = resolverPhraseTokens(topCandidate.matchedPhrase)

    if (topPhraseTokens.isEmpty()) {
        return exactTopChoices
    }

    val sharedPhraseChoices = candidates
        .filter { candidate ->
            resolverPhraseTokens(candidate.matchedPhrase) == topPhraseTokens
        }
        .distinctBy { candidate ->
            "${candidate.contact.name.lowercase(Locale.ROOT)}-${candidate.contact.phoneNumber}"
        }

    return if (sharedPhraseChoices.size > 1) {
        sharedPhraseChoices
    } else {
        exactTopChoices
    }
}


private data class DirectContactResolution(
    val matches: List<ContactResult>,
    val confidence: SmartWhatsAppConfidence,
    val reason: String
)

private fun resolveDirectContactQuery(
    contactQuery: String,
    contacts: List<ContactResult>
): DirectContactResolution {
    val queryTokens = resolverPhraseTokens(contactQuery)

    if (queryTokens.isEmpty()) {
        return DirectContactResolution(
            matches = emptyList(),
            confidence = SmartWhatsAppConfidence.LOW,
            reason = "Contact query did not contain searchable words."
        )
    }

    val directMatches = resolverDistinctContacts(
        contacts.mapNotNull { contact ->
            val contactTokens = resolverPhraseTokens(contact.name)
            val matched = contactTokens.indices.any { startIndex ->
                resolverContactSequenceMatches(
                    phraseTokens = queryTokens,
                    contactTokens = contactTokens,
                    contactStartIndex = startIndex
                )
            }

            contact.takeIf { matched }
        }
    )

    val confidence = when {
        directMatches.size != 1 -> SmartWhatsAppConfidence.MEDIUM
        queryTokens.size == resolverPhraseTokens(directMatches.first().name).size ->
            SmartWhatsAppConfidence.HIGH
        else -> SmartWhatsAppConfidence.MEDIUM
    }

    val reason = when (directMatches.size) {
        0 -> "No saved contact matched the typed contact query."
        1 -> "Typed contact query matched a saved contact name."
        else -> "Multiple saved contacts matched the typed contact query."
    }

    return DirectContactResolution(
        matches = directMatches,
        confidence = confidence,
        reason = reason
    )
}

private fun contactNotFoundResolution(
    rawCommand: String,
    contactQuery: String = "",
    matchedContactPhrase: String = contactQuery,
    messageText: String = "",
    reason: String
): SmartWhatsAppCommandResolution {
    return SmartWhatsAppCommandResolution(
        rawCommand = rawCommand,
        status = SmartWhatsAppResolveStatus.CONTACT_NOT_FOUND,
        confidence = SmartWhatsAppConfidence.LOW,
        contactQuery = contactQuery,
        matchedContactPhrase = matchedContactPhrase,
        messageText = messageText,
        reason = reason
    )
}

private fun directContactSmartResolution(
    rawCommand: String,
    contactQuery: String,
    messageText: String,
    directResolution: DirectContactResolution,
    multipleReason: String = directResolution.reason,
    selectedReason: String = directResolution.reason
): SmartWhatsAppCommandResolution? {
    if (directResolution.matches.size > 1) {
        return SmartWhatsAppCommandResolution(
            rawCommand = rawCommand,
            status = SmartWhatsAppResolveStatus.NEEDS_CONTACT_SELECTION,
            confidence = directResolution.confidence,
            matchingContacts = directResolution.matches,
            contactQuery = contactQuery,
            matchedContactPhrase = contactQuery,
            messageText = messageText,
            reason = multipleReason
        )
    }

    val selectedContact = directResolution.matches.singleOrNull() ?: return null

    return SmartWhatsAppCommandResolution(
        rawCommand = rawCommand,
        status = SmartWhatsAppResolveStatus.READY_TO_PREFILL,
        confidence = directResolution.confidence,
        selectedContact = selectedContact,
        matchingContacts = listOf(selectedContact),
        contactQuery = contactQuery,
        matchedContactPhrase = contactQuery,
        messageText = messageText,
        reason = selectedReason
    )
}

fun resolveSmartWhatsAppChatCommand(
    rawCommand: String,
    contacts: List<ContactResult>
): SmartWhatsAppCommandResolution {
    val cleanedCommand = resolverTrimSeparators(rawCommand)

    if (cleanedCommand.isBlank()) {
        return SmartWhatsAppCommandResolution(
            rawCommand = rawCommand,
            status = SmartWhatsAppResolveStatus.NOT_WHATSAPP_MESSAGE,
            confidence = SmartWhatsAppConfidence.NONE,
            reason = "Empty command."
        )
    }

    val parsedContactQuery = extractWhatsAppContactQuery(cleanedCommand)
    val parsedQueryTokens = resolverPhraseTokens(parsedContactQuery)

    if (parsedQueryTokens.isNotEmpty()) {
        val directResolution = resolveDirectContactQuery(
            contactQuery = parsedContactQuery,
            contacts = contacts
        )

        directContactSmartResolution(
            rawCommand = rawCommand,
            contactQuery = parsedContactQuery,
            messageText = "",
            directResolution = directResolution
        )?.let { resolution ->
            return resolution
        }

        if (parsedQueryTokens.size > 1) {
            return contactNotFoundResolution(
                rawCommand = rawCommand,
                contactQuery = parsedContactQuery,
                // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
                reason = "Full typed contact query did not match any saved contact. Zyro did not open a partial contact match."
            )
        }
    }

    val contactCandidates = resolverFindContactMatchCandidates(
        rawCommand = cleanedCommand,
        contacts = contacts
    )

    if (contactCandidates.isEmpty()) {
        return contactNotFoundResolution(
            rawCommand = rawCommand,
            contactQuery = parsedContactQuery,
            reason = "No saved contact matched the chat command."
        )
    }

    val topChoices = resolverTopContactChoices(contactCandidates)

    val distinctTopContacts = resolverDistinctContacts(
        topChoices.map { candidate ->
            candidate.contact
        }
    )

    if (distinctTopContacts.size > 1) {
        return SmartWhatsAppCommandResolution(
            rawCommand = rawCommand,
            status = SmartWhatsAppResolveStatus.NEEDS_CONTACT_SELECTION,
            confidence = SmartWhatsAppConfidence.MEDIUM,
            matchingContacts = distinctTopContacts,
            contactQuery = topChoices.first().matchedPhrase,
            matchedContactPhrase = topChoices.first().matchedPhrase,
            reason = "Multiple saved contacts matched this chat command."
        )
    }

    if (distinctTopContacts.size == 1) {
        val selectedContact = distinctTopContacts.first()
        val selectedCandidate = topChoices.firstOrNull { candidate ->
            resolverContactIdentityKey(candidate.contact) == resolverContactIdentityKey(selectedContact)
        } ?: contactCandidates.first()

        return SmartWhatsAppCommandResolution(
            rawCommand = rawCommand,
            status = SmartWhatsAppResolveStatus.READY_TO_PREFILL,
            confidence = resolverConfidenceForScore(selectedCandidate.score),
            selectedContact = selectedContact,
            matchingContacts = listOf(selectedContact),
            contactQuery = selectedCandidate.matchedPhrase,
            matchedContactPhrase = selectedCandidate.matchedPhrase,
            messageText = "",
            reason = selectedCandidate.reason
        )
    }

    return contactNotFoundResolution(
        rawCommand = rawCommand,
        contactQuery = parsedContactQuery,
        reason = "No saved contact matched the chat command after duplicate filtering."
    )
}
fun extractWhatsAppMessageRequest(input: String): WhatsAppMessageRequest {
    val trimmedInput = input.trim()

    if (trimmedInput.isBlank()) {
        return WhatsAppMessageRequest(
            contactQuery = "",
            messageText = ""
        )
    }

    if (trimmedInput.contains(":")) {
        val parts = trimmedInput.split(":", limit = 2)
        val contactPart = parts.getOrElse(0) { "" }
        val messagePart = parts.getOrElse(1) { "" }

        return WhatsAppMessageRequest(
            contactQuery = cleanWhatsAppContactQuery(contactPart),
            messageText = cleanWhatsAppMessageText(messagePart)
        )
    }

    val explicitPatterns = listOf(
        Regex(
            pattern = "^(.+?)\\s+ko\\s+(.+?)\\s+(?:bhejo|bhajo|send\\s*karo)\\s*$",
            option = RegexOption.IGNORE_CASE
        ),
        Regex(
            pattern = "^(.+?)\\s+ko\\s+(?:message|msg)\\s+karo\\s*(?:ki|ke|kay|that)?\\s*[:\u060C,\\-\u2013\u2014]*\\s*(.+)$",
            option = RegexOption.IGNORE_CASE
        ),
        Regex(
            pattern = "^(.+?)\\s+ko\\s+(?:message|msg)\\s+(?:bhejo|bhajo|likho)\\s*(?:ki|ke|kay|that)?\\s*[:\u060C,\\-\u2013\u2014]*\\s*(.+)$",
            option = RegexOption.IGNORE_CASE
        ),
        Regex(
            pattern = "^(.+?)\\s+ko\\s+send\\s+karo\\s*(?:ki|ke|kay|that)?\\s*[:\u060C,\\-\u2013\u2014]*\\s*(.+)$",
            option = RegexOption.IGNORE_CASE
        ),
        Regex(
            pattern = "^(.+?)\\s+ko\\s+(?:bolo|kaho|kehdo|keh\\s*do)\\s*(?:ki|ke|kay|that)?\\s*[:\u060C,\\-\u2013\u2014]*\\s*(.+)$",
            option = RegexOption.IGNORE_CASE
        ),
        Regex(
            pattern = "^(?:tell)\\s+(.+?)\\s+(?:that\\s+)?(.+)$",
            option = RegexOption.IGNORE_CASE
        ),
        Regex(
            pattern = "^(?:message|msg)\\s+(.+?)\\s+(?:that\\s+)?(.+)$",
            option = RegexOption.IGNORE_CASE
        ),
        Regex(
            pattern = "^(?:send\\s+(?:a\\s+)?message\\s+to|write\\s+(?:a\\s+)?message\\s+to)\\s+(.+?)\\s+(?:that\\s+)?(.+)$",
            option = RegexOption.IGNORE_CASE
        )
    )

    explicitPatterns.forEach { pattern ->
        val match = pattern.find(trimmedInput)

        if (match != null) {
            val contactPart = match.groupValues.getOrElse(1) { "" }
            val messagePart = match.groupValues.getOrElse(2) { "" }

            return WhatsAppMessageRequest(
                contactQuery = cleanWhatsAppContactQuery(contactPart),
                messageText = cleanWhatsAppMessageText(messagePart)
            )
        }
    }

    return WhatsAppMessageRequest(
        contactQuery = "",
        messageText = ""
    )
}

fun parseOpenAppsStrategyCommand(
    input: String,
    command: String
): ZeaCommand {
    return parseOpenAppsStrategyCommand(
        context = null,
        input = input,
        command = command
    )
}

fun parseOpenAppsStrategyCommand(
    context: Context?,
    input: String,
    command: String
): ZeaCommand {
    val registry = availableAppRegistry(context)

    blockedAppCommandOrNull(
        input = input,
        command = command,
        registry = registry
    )?.let { blockedCommand ->
        return blockedCommand
    }

    val detectedApp = detectAppFromRegistry(
        command = command,
        registry = registry
    )

    if (detectedApp == null) {
        val supportedSummary = supportedAppsSummary(context)

        return ZeaCommand(
            rawCommand = input,
            action = "open_app",
            appKey = "unknown",
            appDisplayName = "Unknown",
            status = "error",
            message = "App not recognized among $supportedSummary. Use the exact installed app name or add it to Allowed Apps."
        )
    }

    return ZeaCommand(
        rawCommand = input,
        action = "open_app",
        appKey = detectedApp.key,
        appDisplayName = detectedApp.displayName,
        status = "success",
        message = "Opening ${detectedApp.displayName}."
    )
}

fun parseSendMessageStrategyCommand(input: String): ZeaCommand {
    return buildWhatsAppMessageCommand(input)
}

fun isFriendlyConversationCommand(command: String): Boolean {
    val cleanCommand = command.trim().lowercase(Locale.ROOT)

    if (cleanCommand.isBlank()) {
        return false
    }

    val exactPhrases = setOf(
        "hi",
        "hello",
        "hey",
        "salam",
        "assalam o alaikum",
        "assalamu alaikum",
        "how are you",
        "kaise ho",
        "kese ho",
        "kya haal hai",
        "kia haal hai",
        "thanks",
        "thank you",
        "shukriya"
    )

    if (cleanCommand in exactPhrases) {
        return true
    }

    return cleanCommand.startsWith("hello ") ||
            cleanCommand.startsWith("hi ") ||
            cleanCommand.startsWith("salam ") ||
            cleanCommand.contains("how are you") ||
            cleanCommand.contains("kya haal") ||
            cleanCommand.contains("kia haal")
}

fun buildFriendlyConversationCommand(input: String): ZeaCommand {
    val cleanCommand = input.trim().lowercase(Locale.ROOT)

    val message = when {
        cleanCommand.contains("thank") || cleanCommand.contains("shukriya") ->
            "You are welcome. I am here whenever you need help with commands, WhatsApp chats, or contacts."

        cleanCommand.contains("how are you") ||
                cleanCommand.contains("kaise ho") ||
                cleanCommand.contains("kese ho") ||
                cleanCommand.contains("kya haal") ||
                cleanCommand.contains("kia haal") ->
            "I am ready and working. Tell me what you want to do, for example open WhatsApp, open a contact chat, or show the contact list."

        else ->
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            "Hi, I am Zyro. You can use natural commands, and I will try to choose the right action."
    }

    return ZeaCommand(
        rawCommand = input,
        action = "conversation",
        appKey = "none",
        appDisplayName = "None",
        status = "success",
        message = message
    )
}

fun buildUnsupportedCommand(input: String): ZeaCommand {
    return buildUnsupportedCommand(
        context = null,
        input = input
    )
}

fun buildUnsupportedCommand(
    context: Context?,
    input: String
): ZeaCommand {
    val supportedSummary = supportedAppsSummary(context)

    return ZeaCommand(
        rawCommand = input,
        action = "unsupported_command",
        appKey = "unknown",
        appDisplayName = "Unknown",
        status = "error",
        // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
        message = "Command not recognized. Zyro currently supports $supportedSummary."
    )
}

private fun commandHasContactFirstMessageSignal(command: String): Boolean {
    val cleanCommand = command.trim().lowercase(Locale.ROOT)

    val messageSignals = listOf(
        " message ",
        " msg ",
        " send ",
        " bhejo",
        " bhajo",
        " likho",
        " bolo",
        " kaho",
        " kehdo",
        " keh do"
    )

    val padded = " $cleanCommand "
    return messageSignals.any { signal -> padded.contains(signal) }
}

fun resolveSmartWhatsAppPrefillCommand(
    rawCommand: String,
    parsedContactQuery: String,
    parsedMessageText: String,
    contacts: List<ContactResult>
): SmartWhatsAppCommandResolution {
    val contactQuery = resolverTrimSeparators(parsedContactQuery)
    val messageText = cleanWhatsAppMessageText(parsedMessageText)

    if (contactQuery.isBlank()) {
        return contactNotFoundResolution(
            rawCommand = rawCommand,
            reason = "Contact name was missing from the message command."
        )
    }

    if (messageText.isBlank()) {
        return SmartWhatsAppCommandResolution(
            rawCommand = rawCommand,
            status = SmartWhatsAppResolveStatus.MESSAGE_NOT_FOUND,
            confidence = SmartWhatsAppConfidence.LOW,
            contactQuery = contactQuery,
            matchedContactPhrase = contactQuery,
            reason = "Message text was missing from the message command."
        )
    }

    val directResolution = resolveDirectContactQuery(
        contactQuery = contactQuery,
        contacts = contacts
    )

    directContactSmartResolution(
        rawCommand = rawCommand,
        contactQuery = contactQuery,
        messageText = messageText,
        directResolution = directResolution,
        multipleReason = "Multiple saved contacts matched the recipient name.",
        selectedReason = "Recipient matched from the contact part only. Message text was kept separate from contact matching."
    )?.let { resolution ->
        return resolution
    }

    return contactNotFoundResolution(
        rawCommand = rawCommand,
        contactQuery = contactQuery,
        messageText = messageText,
        reason = directResolution.reason
    )
}

fun parseZeaCommand(
    context: Context,
    input: String,
    strategy: CommandStrategy
): ZeaCommand {
    return parseZeaCommandInternal(
        context = context.applicationContext,
        input = input,
        strategy = strategy
    )
}

private fun parseZeaCommandInternal(
    context: Context?,
    input: String,
    strategy: CommandStrategy
): ZeaCommand {
    val command = input.trim().lowercase(Locale.ROOT)

    if (command.isBlank()) {
        return ZeaCommand(
            rawCommand = input,
            action = "none",
            appKey = "none",
            appDisplayName = "None",
            status = "error",
            message = "Please type a command first."
        )
    }

    if (isFriendlyConversationCommand(command)) {
        return buildFriendlyConversationCommand(input)
    }

    if (isContactsAuditCommand(command)) {
        return ZeaCommand(
            rawCommand = input,
            action = "show_contacts_audit",
            appKey = "contacts",
            appDisplayName = "Contacts",
            status = "success",
            message = "Preparing contacts audit."
        )
    }

    if (isNaturalWhatsAppMessageCommand(command)) {
        return buildWhatsAppMessageCommand(input)
    }

    val detectedApp = if (context == null) {
        detectApp(command)
    } else {
        detectApp(context, command)
    }
    val explicitAppLaunch = hasAppOpenIntent(command)
    val naturalWhatsAppChat = isNaturalWhatsAppChatCommand(command)
    val shouldOpenWhatsAppChat = naturalWhatsAppChat &&
            (detectedApp == null || detectedApp.key == "whatsapp")

    if (shouldOpenWhatsAppChat) {
        return buildOpenWhatsAppChatCommand(input)
    }

    if (explicitAppLaunch) {
        return parseOpenAppsStrategyCommand(
            context = context,
            input = input,
            command = command
        )
    }

    if (looksLikeContactFirstMessage(command)) {
        return buildContactFirstMessageCommand(input)
    }

    return when (strategy) {
        CommandStrategy.OPEN_APPS -> parseOpenAppsStrategyCommand(
            context = context,
            input = input,
            command = command
        )

        CommandStrategy.SEND_MESSAGE -> parseSendMessageStrategyCommand(input)
    }
}


private inline fun <T> queryContactsSafely(
    context: Context,
    uri: Uri,
    projection: Array<String>,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?,
    defaultValue: T,
    readCursor: (Cursor) -> T
): T {
    return try {
        context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use(readCursor) ?: defaultValue
    } catch (_: SecurityException) {
        defaultValue
    } catch (_: RuntimeException) {
        defaultValue
    }
}

private inline fun <T> queryWhatsAppContactDataSafely(
    context: Context,
    projection: Array<String>,
    selection: String?,
    selectionArgs: Array<String>?,
    defaultValue: T,
    readCursor: (Cursor) -> T
): T {
    return queryContactsSafely(
        context = context,
        uri = ContactsContract.Data.CONTENT_URI,
        projection = projection,
        selection = selection,
        selectionArgs = selectionArgs,
        sortOrder = null,
        defaultValue = defaultValue,
        readCursor = readCursor
    )
}

fun hasContactsPermission(context: Context): Boolean {
    return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
}

fun findWhatsAppDataIdForContactId(
    context: Context,
    contactId: String
): String {
    if (!hasContactsPermission(context) || contactId.isBlank()) {
        return ""
    }

    val projection = arrayOf(ContactsContract.Data._ID)
    val selection =
        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
    val selectionArgs = arrayOf(contactId, WHATSAPP_PROFILE_MIME_TYPE)

    return queryWhatsAppContactDataSafely(
        context = context,
        projection = projection,
        selection = selection,
        selectionArgs = selectionArgs,
        defaultValue = ""
    ) { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.Data._ID)
        if (idIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(idIndex).orEmpty()
        } else {
            ""
        }
    }
}

private fun loadWhatsAppDataIdsByContactId(
    context: Context
): Map<String, String> {
    if (!hasContactsPermission(context)) {
        return emptyMap()
    }

    val projection = arrayOf(
        ContactsContract.Data.CONTACT_ID,
        ContactsContract.Data._ID
    )
    val selection = "${ContactsContract.Data.MIMETYPE} = ?"
    val selectionArgs = arrayOf(WHATSAPP_PROFILE_MIME_TYPE)

    return queryWhatsAppContactDataSafely(
        context,
        projection,
        selection,
        selectionArgs,
        emptyMap()
    ) { cursor ->
        val contactIdIndex = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
        val dataIdIndex = cursor.getColumnIndex(ContactsContract.Data._ID)
        val dataIds = linkedMapOf<String, String>()

        while (cursor.moveToNext()) {
            if (contactIdIndex < 0 || dataIdIndex < 0) {
                break
            }

            val contactId = cursor.getString(contactIdIndex).orEmpty()
            val dataId = cursor.getString(dataIdIndex).orEmpty()

            if (contactId.isNotBlank() && dataId.isNotBlank()) {
                dataIds.putIfAbsent(contactId, dataId)
            }
        }

        dataIds.toMap()
    }
}

fun loadAllContactsForAudit(
    context: Context,
    maxResults: Int = 500
): List<ContactResult> {
    if (!hasContactsPermission(context) || maxResults <= 0) {
        return emptyList()
    }

    val whatsAppDataIds = loadWhatsAppDataIdsByContactId(context)

    return queryPhoneContacts(
        context = context,
        selection = null,
        selectionArgs = null,
        maximumResults = maxResults,
        whatsAppDataIds = whatsAppDataIds
    )
}

fun contactsAuditToText(contacts: List<ContactResult>): String {
    if (contacts.isEmpty()) {
        return "No contacts were found."
    }

    val whatsAppLinkedContacts = contacts.count { contact ->
        contact.whatsAppDataId.isNotBlank()
    }
    val duplicateKeywordGroups = contacts
        .flatMap { contact ->
            resolverPhraseTokens(contact.name)
                .filter { token -> token.length >= 3 }
                .distinct()
                .map { token -> token to contact }
        }
        .groupBy(
            keySelector = { pair -> pair.first },
            valueTransform = { pair -> pair.second }
        )
        .mapValues { entry ->
            entry.value.distinctBy(::contactIdentity)
        }
        .filterValues { matchingContacts -> matchingContacts.size > 1 }
        .toSortedMap()
    val contactLines = contacts.mapIndexed { index, contact ->
        val linkedText = if (contact.whatsAppDataId.isNotBlank()) "Yes" else "No/Not detected"

        """
        ${index + 1}. ${contact.name}
           Number: ${contact.phoneNumber}
           WhatsApp linked: $linkedText
        """.trimIndent()
    }
    val duplicateLines = duplicateKeywordGroups.entries
        .take(30)
        .map { entry ->
            val names = entry.value.joinToString(", ") { contact -> contact.name }
            "- ${entry.key}: $names"
        }
    val duplicateSummary = duplicateLines
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString("\n")
        ?: "No duplicate/shared keyword groups detected in the loaded contact set."

    return """
        Contacts Audit

        Total contacts loaded: ${contacts.size}
        WhatsApp-linked contacts detected: $whatsAppLinkedContacts

        Duplicate/shared keyword groups:
        $duplicateSummary

        Saved contacts:
        ${contactLines.joinToString("\n\n")}
    """.trimIndent()
}

fun searchContacts(
    context: Context,
    query: String
): List<ContactResult> {
    val cleanQuery = query.trim()

    if (!hasContactsPermission(context) || cleanQuery.isBlank()) {
        return emptyList()
    }

    val whatsAppDataIds = loadWhatsAppDataIdsByContactId(context)
    val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
    val selectionArgs = arrayOf("%$cleanQuery%")

    return queryPhoneContacts(
        context = context,
        selection = selection,
        selectionArgs = selectionArgs,
        maximumResults = MAX_CONTACT_SEARCH_RESULTS,
        whatsAppDataIds = whatsAppDataIds
    )
}

private fun queryPhoneContacts(
    context: Context,
    selection: String?,
    selectionArgs: Array<String>?,
    maximumResults: Int,
    whatsAppDataIds: Map<String, String>
): List<ContactResult> {
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID
    )

    return queryContactsSafely(
        context = context,
        uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection = projection,
        selection = selection,
        selectionArgs = selectionArgs,
        sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
        defaultValue = emptyList()
    ) { cursor ->
        val results = mutableListOf<ContactResult>()
        val nameIndex = cursor.getColumnIndex(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val numberIndex = cursor.getColumnIndex(
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val contactIdIndex = cursor.getColumnIndex(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        )

        while (cursor.moveToNext() && results.size < maximumResults) {
            if (nameIndex < 0 || numberIndex < 0 || contactIdIndex < 0) {
                break
            }

            val name = cursor.getString(nameIndex).orEmpty().ifBlank { "Unknown" }
            val number = cursor.getString(numberIndex).orEmpty()
            val contactId = cursor.getString(contactIdIndex).orEmpty()

            if (number.isNotBlank()) {
                results += ContactResult(
                    name = name,
                    phoneNumber = number,
                    whatsAppDataId = whatsAppDataIds[contactId].orEmpty()
                )
            }
        }

        results
            .distinctBy(::contactIdentity)
            .take(maximumResults)
    }
}

private fun contactIdentity(contact: ContactResult): String {
    val normalizedNumber = contact.phoneNumber.filter(Char::isDigit).takeLast(10)
    return "${contact.name.trim().lowercase(Locale.ROOT)}|$normalizedNumber"
}

fun normalizePhoneForWhatsApp(phoneNumber: String): String {
    var digits = phoneNumber.filter(Char::isDigit)

    if (digits.isBlank()) {
        return ""
    }

    if (digits.startsWith("00")) {
        digits = digits.removePrefix("00")
    }

    return when {
        digits.startsWith("92") && digits.length >= 11 -> digits
        digits.startsWith("0") && digits.length >= 10 -> "92${digits.drop(1)}"
        digits.length == 10 && digits.startsWith("3") -> "92$digits"
        else -> digits
    }
}

fun getWhatsAppRegistryEntry(): AppRegistryEntry {
    return zeaAppRegistry.first { entry -> entry.key == "whatsapp" }
}

private fun whatsAppInputFailure(message: String): LaunchResult {
    return LaunchResult(
        success = false,
        message = message,
        failureReason = AppLaunchFailureReason.LAUNCH_FAILED
    )
}

fun openWhatsAppChat(
    context: Context,
    phoneNumber: String,
    whatsAppDataId: String = ""
): LaunchResult {
    val normalizedNumber = normalizePhoneForWhatsApp(phoneNumber)

    if (normalizedNumber.isBlank()) {
        return whatsAppInputFailure("Phone number is invalid.")
    }

    val intents = buildList {
        if (whatsAppDataId.isNotBlank()) {
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse("content://com.android.contacts/data/$whatsAppDataId"),
                        WHATSAPP_PROFILE_MIME_TYPE
                    )
                    setPackage(WHATSAPP_PACKAGE_NAME)
                }
            )
        }

        add(whatsAppUrlIntent("https://wa.me/$normalizedNumber"))
        add(whatsAppUrlIntent("https://api.whatsapp.com/send?phone=$normalizedNumber"))
    }

    return launchWhatsAppAction(
        context = context,
        intents = intents,
        successMessage = "WhatsApp chat opened successfully.",
        failureMessage = "WhatsApp could not open the selected chat."
    )
}

fun openWhatsAppMessagePrefill(
    context: Context,
    phoneNumber: String,
    messageText: String
): LaunchResult {
    val normalizedNumber = normalizePhoneForWhatsApp(phoneNumber)
    val cleanMessage = messageText.trim()

    if (normalizedNumber.isBlank()) {
        return whatsAppInputFailure("Phone number is invalid.")
    }

    if (cleanMessage.isBlank()) {
        return whatsAppInputFailure("Message text is empty.")
    }

    val encodedMessage = Uri.encode(cleanMessage)
    val intents = listOf(
        whatsAppUrlIntent("https://wa.me/$normalizedNumber?text=$encodedMessage"),
        whatsAppUrlIntent(
            "https://api.whatsapp.com/send?phone=$normalizedNumber&text=$encodedMessage"
        )
    )

    return launchWhatsAppAction(
        context = context,
        intents = intents,
        successMessage = "WhatsApp opened with the message prepared. You still press Send manually.",
        failureMessage = "WhatsApp could not open with the prepared message."
    )
}

private fun whatsAppUrlIntent(url: String): Intent {
    return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        setPackage(WHATSAPP_PACKAGE_NAME)
    }
}

private fun launchWhatsAppAction(
    context: Context,
    intents: List<Intent>,
    successMessage: String,
    failureMessage: String
): LaunchResult {
    val whatsAppEntry = getWhatsAppRegistryEntry()
    val policyResult = ZeaSafetyPolicy.evaluateRegistryEntry(whatsAppEntry)

    if (!policyResult.allowed || isAppBlockedBySettings(context, whatsAppEntry)) {
        return LaunchResult(
            success = false,
            // TEMPORARY BRAND NAME: "Zyro" is not the permanent app name and can be replaced with the original/permanent name "Zea Assistant" at any time.
            message = "WhatsApp is blocked by Zyro safety settings.",
            failureReason = AppLaunchFailureReason.BLOCKED
        )
    }

    if (!isAppAllowedBySettings(context, whatsAppEntry)) {
        return LaunchResult(
            success = false,
            message = "WhatsApp is not in the Allowed Apps list.",
            failureReason = AppLaunchFailureReason.NOT_ALLOWED
        )
    }

    val attempts = mutableListOf<LaunchAttempt>()
    var securityRejected = false

    intents.forEachIndexed { index, originalIntent ->
        val intent = Intent(originalIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
            attempts += LaunchAttempt(
                method = AppLaunchMethod.VERIFIED_APP_URI,
                success = true,
                detail = "WhatsApp action strategy ${index + 1} succeeded."
            )

            return LaunchResult(
                success = true,
                message = successMessage,
                method = AppLaunchMethod.VERIFIED_APP_URI,
                attempts = attempts
            )
        } catch (_: ActivityNotFoundException) {
            attempts += LaunchAttempt(
                method = AppLaunchMethod.VERIFIED_APP_URI,
                success = false,
                detail = "WhatsApp action strategy ${index + 1} had no compatible activity."
            )
        } catch (_: SecurityException) {
            securityRejected = true
            attempts += LaunchAttempt(
                method = AppLaunchMethod.VERIFIED_APP_URI,
                success = false,
                detail = "WhatsApp action strategy ${index + 1} was rejected by Android."
            )
        } catch (_: RuntimeException) {
            attempts += LaunchAttempt(
                method = AppLaunchMethod.VERIFIED_APP_URI,
                success = false,
                detail = "WhatsApp action strategy ${index + 1} failed at runtime."
            )
        }
    }

    val genericLaunch = ZeaAppLauncher.launchRegistryEntry(context, whatsAppEntry)
    val combinedAttempts = attempts + genericLaunch.attempts

    if (genericLaunch.success) {
        return LaunchResult(
            success = false,
            message = "$failureMessage WhatsApp itself was opened as a safe fallback, but the target chat or message was not selected.",
            method = genericLaunch.method,
            failureReason = AppLaunchFailureReason.NO_COMPATIBLE_HANDLER,
            attempts = combinedAttempts
        )
    }

    return LaunchResult(
        success = false,
        message = failureMessage,
        failureReason = if (securityRejected) {
            AppLaunchFailureReason.SECURITY_REJECTED
        } else {
            genericLaunch.failureReason.takeUnless { reason ->
                reason == AppLaunchFailureReason.NONE
            } ?: AppLaunchFailureReason.NO_COMPATIBLE_HANDLER
        },
        attempts = combinedAttempts
    )
}

private const val WHATSAPP_PACKAGE_NAME = "com.whatsapp"
private const val WHATSAPP_PROFILE_MIME_TYPE =
    "vnd.android.cursor.item/vnd.com.whatsapp.profile"
private const val MAX_CONTACT_SEARCH_RESULTS = 25


fun contactsToText(contacts: List<ContactResult>): String {
    if (contacts.isEmpty()) {
        return "Contact not found."
    }

    return contacts.mapIndexed { index, contact ->
        "${index + 1}. ${contact.name}\n   ${contact.phoneNumber}"
    }.joinToString("\n\n")
}
