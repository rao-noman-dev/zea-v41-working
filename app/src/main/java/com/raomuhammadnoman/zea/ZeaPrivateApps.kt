package com.raomuhammadnoman.zea

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale

private const val PRIVATE_APP_LOG_TAG = "ZeaLaunch"

private val privateAppOpenCommandPattern = Regex(
    pattern = "^\\s*(open|launch)\\s+private\\s+(.+?)\\s*$",
    option = RegexOption.IGNORE_CASE
)

private val privateAppNameSuffixPattern = Regex(
    pattern = "\\s+(app|application)\\s*$",
    option = RegexOption.IGNORE_CASE
)

internal data class PrivateAppOpenRequest(
    val rawCommand: String,
    val requestedName: String,
    val record: PrivateAppRecord?
)

private data class PrivateAppLookupSnapshot(
    val apps: List<PrivateAppRecord>,
    val recordsByExactName: Map<String, PrivateAppRecord>
)

internal object ZeaPrivateAppLookupCache {
    private val rebuildLock = Any()

    @Volatile
    private var snapshot: PrivateAppLookupSnapshot? = null

    fun warm(context: Context) {
        snapshot(context.applicationContext)
    }

    fun apps(context: Context): List<PrivateAppRecord> {
        return snapshot(context.applicationContext).apps
    }

    fun findByExactName(
        context: Context,
        requestedName: String
    ): PrivateAppRecord? {
        val normalizedName = normalizePrivateAppName(requestedName)
        if (normalizedName.isBlank()) return null
        return snapshot(context.applicationContext).recordsByExactName[normalizedName]
    }

    fun invalidate(reason: String) {
        synchronized(rebuildLock) {
            snapshot = null
        }
        Log.i(PRIVATE_APP_LOG_TAG, "private app lookup cache invalidated reason=$reason")
    }

    private fun snapshot(context: Context): PrivateAppLookupSnapshot {
        snapshot?.let { cached ->
            Log.i(PRIVATE_APP_LOG_TAG, "private app lookup cache hit apps=${cached.apps.size}")
            return cached
        }

        return synchronized(rebuildLock) {
            snapshot?.let { cached ->
                Log.i(
                    PRIVATE_APP_LOG_TAG,
                    "private app lookup cache hit after lock apps=${cached.apps.size}"
                )
                return@synchronized cached
            }

            val started = SystemClock.elapsedRealtime()
            Log.i(PRIVATE_APP_LOG_TAG, "private app lookup cache rebuild start")

            val apps = loadPrivateApps(context)
            val recordsByName = LinkedHashMap<String, PrivateAppRecord>()
            val ambiguousNames = HashSet<String>()

            apps.forEach { app ->
                sequenceOf(app.displayName)
                    .plus(app.aliases.asSequence())
                    .map(::normalizePrivateAppName)
                    .filter(String::isNotBlank)
                    .distinct()
                    .forEach { normalizedName ->
                        if (normalizedName in ambiguousNames) return@forEach

                        val existing = recordsByName[normalizedName]
                        if (existing == null || existing.packageName == app.packageName) {
                            recordsByName[normalizedName] = app
                        } else {
                            recordsByName.remove(normalizedName)
                            ambiguousNames += normalizedName
                        }
                    }
            }

            PrivateAppLookupSnapshot(
                apps = apps.toList(),
                recordsByExactName = recordsByName.toMap()
            ).also { rebuilt ->
                snapshot = rebuilt
                Log.i(
                    PRIVATE_APP_LOG_TAG,
                    "private app lookup cache rebuild end apps=${rebuilt.apps.size} names=${rebuilt.recordsByExactName.size} elapsedMs=${SystemClock.elapsedRealtime() - started}"
                )
            }
        }
    }
}

internal fun resolvePrivateAppOpenCommand(
    context: Context,
    input: String
): PrivateAppOpenRequest? {
    val started = SystemClock.elapsedRealtime()
    val match = privateAppOpenCommandPattern.matchEntire(input) ?: return null
    val requestedName = match.groupValues[2]
        .replace(privateAppNameSuffixPattern, "")
        .trim()
    val record = ZeaPrivateAppLookupCache.findByExactName(context, requestedName)

    Log.i(
        PRIVATE_APP_LOG_TAG,
        "private app target resolution name=${normalizePrivateAppName(requestedName)} found=${record != null} elapsedMs=${SystemClock.elapsedRealtime() - started}"
    )

    return PrivateAppOpenRequest(
        rawCommand = input,
        requestedName = requestedName,
        record = record
    )
}

internal fun PrivateAppRecord.toPrivateRegistryEntry(): AppRegistryEntry {
    return AppRegistryEntry(
        key = "private_${packageName.lowercase(Locale.ROOT).replace('.', '_')}",
        displayName = displayName,
        aliases = aliases,
        packageName = packageName,
        launcherActivityName = launcherActivityName,
        source = AppRegistrySource.PRIVATE,
        category = AppCategory.OTHER
    )
}

internal fun UserAllowedApp.toPrivateAppRecord(): PrivateAppRecord {
    return PrivateAppRecord(
        displayName = displayName,
        packageName = packageName,
        launcherActivityName = launcherActivityName,
        aliases = aliases
    )
}

internal fun normalizePrivateAppName(value: String): String {
    return value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
