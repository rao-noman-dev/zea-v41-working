package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ZeaSearchResultKind(val label: String) {
    APP("App"),
    GROUP("Group"),
    PROFILE("Profile"),
    SCHEDULE("Schedule"),
    ACTION("Action")
}

data class ZeaSearchResult(
    val kind: ZeaSearchResultKind,
    val title: String,
    val subtitle: String,
    val targetId: String,
    val routeHint: String
)

/**
 * Phase 3 global search. Case-insensitive across apps, groups, profiles,
 * schedules, and quick actions. Results are status-aware and performant
 * against 200+ apps.
 */
object ZeaSearch {
    suspend fun search(
        context: Context,
        query: String
    ): List<ZeaSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()
        val lowerQuery = cleanQuery.lowercase()

        val results = mutableListOf<ZeaSearchResult>()

        // Apps — subtitle reports the coherent CURRENT status, not just the
        // package name: Visible / Hidden / Timed (+ remaining) / Protected.
        val apps = ZeaAppCatalog.loadManagedApps(context)
        val now = System.currentTimeMillis()
        apps.forEach { app ->
            if (app.displayName.lowercase().contains(lowerQuery) ||
                app.packageName.lowercase().contains(lowerQuery)
            ) {
                results += ZeaSearchResult(
                    kind = ZeaSearchResultKind.APP,
                    title = app.displayName,
                    subtitle = zeaSearchStatusLabel(app, now),
                    targetId = app.packageName,
                    routeHint = "app_details:${app.packageName}"
                )
            }
        }

        // Groups
        ZeaGroups.load(context).forEach { group ->
            if (group.name.lowercase().contains(lowerQuery)) {
                results += ZeaSearchResult(
                    kind = ZeaSearchResultKind.GROUP,
                    title = group.name,
                    subtitle = "${group.memberPackages.size} app(s)",
                    targetId = group.id,
                    routeHint = "groups"
                )
            }
        }

        // Profiles
        ZeaProfiles.load(context).forEach { profile ->
            if (profile.name.lowercase().contains(lowerQuery)) {
                results += ZeaSearchResult(
                    kind = ZeaSearchResultKind.PROFILE,
                    title = profile.name,
                    subtitle = "${profile.hiddenPackages.size} hidden, ${profile.timedPackages.size} timed",
                    targetId = profile.id,
                    routeHint = "profiles"
                )
            }
        }

        // Schedules
        ZeaSchedules.load(context).forEach { schedule ->
            if (schedule.name.lowercase().contains(lowerQuery)) {
                results += ZeaSearchResult(
                    kind = ZeaSearchResultKind.SCHEDULE,
                    title = schedule.name,
                    subtitle = schedule.kind.label,
                    targetId = schedule.id,
                    routeHint = "schedules"
                )
            }
        }

        // Quick actions
        listOf(
            "Groups" to "groups",
            "Profiles" to "profiles",
            "Schedules" to "schedules",
            "History" to "history",
            "Diagnostics" to "diagnostics",
            "Settings" to "settings"
        ).forEach { (label, route) ->
            if (label.lowercase().contains(lowerQuery)) {
                results += ZeaSearchResult(
                    kind = ZeaSearchResultKind.ACTION,
                    title = label,
                    subtitle = "Quick action",
                    targetId = route,
                    routeHint = route
                )
            }
        }

        results
    }
}

/**
 * Coherent CURRENT status line for one app, pure so it is unit testable:
 * Visible / Hidden / Timed (with remaining minutes) plus a Protected suffix
 * for non-manageable packages.
 */
internal fun zeaSearchStatusLabel(
    app: ZeaManagedApp,
    nowEpochMillis: Long
): String {
    val status = when (app.hideMode) {
        ZeaHideMode.VISIBLE -> "Visible"
        ZeaHideMode.HIDDEN -> "Hidden"
        ZeaHideMode.TIMED -> {
            val remainingMinutes =
                ((app.hiddenUntilEpochMillis - nowEpochMillis) / 60_000L).coerceAtLeast(0L)
            "Timed ($remainingMinutes min left)"
        }
    }
    val protection = if (!app.manageable) " • Protected" else ""
    return "$status$protection"
}
