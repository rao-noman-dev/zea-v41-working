package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ZeaProfile(
    val id: String,
    val name: String,
    /** Packages to keep hidden (permanent/timed applies at activation time). */
    val hiddenPackages: List<String>,
    val timedPackages: Map<String, Long>,
    val createdAtEpochMillis: Long
)

/** Snapshot of the current protection state used for capture-as-profile. */
data class ZeaProfileSnapshot(
    val hiddenPackages: List<String>,
    val timedPackages: Map<String, Long>
)

data class ZeaProfileApplyResult(
    val hiddenSucceeded: List<String>,
    val hiddenFailed: List<Pair<String, String>>,
    val timedSucceeded: List<String>,
    val timedFailed: List<Pair<String, String>>
)

/**
 * Phase 3 privacy profiles/modes. Profiles capture membership only; applying
 * reconciles differences transactionally through [ZeaAppHideService]. Never
 * disables security or touches unrelated manual state blindly.
 */
object ZeaProfiles {
    private const val KEY_PROFILES = "app_profiles_v1"

    suspend fun load(context: Context): List<ZeaProfile> = withContext(Dispatchers.IO) {
        val raw = getZeaPrefs(context.applicationContext)
            .getString(KEY_PROFILES, null) ?: return@withContext emptyList()
        decode(raw)
    }

    private suspend fun save(context: Context, profiles: List<ZeaProfile>): Boolean =
        withContext(Dispatchers.IO) {
            getZeaPrefs(context.applicationContext)
                .edit()
                .putString(KEY_PROFILES, encode(profiles))
                .commit()
        }

    /** Captures the current protection state into a new profile. */
    suspend fun captureCurrentState(context: Context, name: String): ZeaProfile? {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > 60) return null
        val profiles = load(context)
        if (profiles.any { it.name.equals(cleanName, ignoreCase = true) }) return null

        val hidden = withContext(Dispatchers.IO) {
            loadPrivateApps(context).map { it.packageName }
        }
        val timed = withContext(Dispatchers.IO) {
            loadTimedHides(context).associate { record ->
                record.packageName to record.hiddenUntilEpochMillis
            }
        }
        val profile = ZeaProfile(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            hiddenPackages = hidden,
            timedPackages = timed,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        return if (save(context, profiles + profile)) profile else null
    }

    suspend fun renameProfile(context: Context, profileId: String, newName: String): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isEmpty() || cleanName.length > 60) return false
        val profiles = load(context)
        if (profiles.any { it.id != profileId && it.name.equals(cleanName, ignoreCase = true) }) {
            return false
        }
        val updated = profiles.map { profile ->
            if (profile.id == profileId) profile.copy(name = cleanName) else profile
        }
        return save(context, updated)
    }

    suspend fun duplicateProfile(context: Context, profileId: String, newName: String): ZeaProfile? {
        val source = load(context).firstOrNull { it.id == profileId } ?: return null
        val cleanName = newName.trim()
        if (cleanName.isEmpty() || cleanName.length > 60) return null
        val profiles = load(context)
        if (profiles.any { it.name.equals(cleanName, ignoreCase = true) }) return null
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        return if (save(context, profiles + copy)) copy else null
    }

    suspend fun updateProfile(context: Context, profile: ZeaProfile): Boolean {
        val updated = load(context).map { existing ->
            if (existing.id == profile.id) profile else existing
        }
        return save(context, updated)
    }

    suspend fun deleteProfile(context: Context, profileId: String): Boolean {
        val updated = load(context).filterNot { it.id == profileId }
        return save(context, updated)
    }

    /**
     * Applies a profile: members in the profile become hidden/timed; packages
     * currently protected but not in the profile are unhidden. Reuses the same
     * verified engines and reports per-app failures honestly.
     */
    suspend fun activateProfile(
        context: Context,
        profileId: String
    ): ZeaProfileApplyResult {
        val profile = load(context).firstOrNull { it.id == profileId }
            ?: return ZeaProfileApplyResult(emptyList(), emptyList(), emptyList(), emptyList())

        val hiddenSucceeded = mutableListOf<String>()
        val hiddenFailed = mutableListOf<Pair<String, String>>()
        val timedSucceeded = mutableListOf<String>()
        val timedFailed = mutableListOf<Pair<String, String>>()

        val currentHidden = withContext(Dispatchers.IO) {
            loadPrivateApps(context).map { it.packageName }
        }
        val profileHidden = profile.hiddenPackages.toSet()
        val profileTimed = profile.timedPackages

        // Packages protected now but not in the profile get unhidden.
        for (packageName in currentHidden.filterNot { profileHidden.contains(it) }) {
            val outcome = ZeaAppHideService.unhideApp(context, packageName)
            if (outcome.success) {
                hiddenSucceeded += packageName
            } else {
                hiddenFailed += packageName to outcome.message
            }
        }

        // Profile members become permanently hidden (or stay if already).
        for (packageName in profileHidden) {
            val app = zeaManagedAppFromPackage(context, packageName) ?: continue
            if (currentHidden.contains(packageName)) continue
            val outcome = ZeaAppHideService.hideApp(context, app)
            if (outcome.success) {
                hiddenSucceeded += packageName
            } else {
                hiddenFailed += packageName to outcome.message
            }
        }

        // Timed members are re-armed with their stored end-time if still future.
        val now = System.currentTimeMillis()
        for ((packageName, endEpoch) in profileTimed) {
            if (endEpoch <= now) continue
            val app = zeaManagedAppFromPackage(context, packageName) ?: continue
            val label = "until ${zeaSnapshotLabel(endEpoch)}"
            val outcome = ZeaAppHideService.hideAppForTime(
                context,
                app,
                ZeaTimedHideRequest(label = label, endEpochMillis = endEpoch)
            )
            if (outcome.success) {
                timedSucceeded += packageName
            } else {
                timedFailed += packageName to outcome.message
            }
        }

        val totalFailures = hiddenFailed.size + timedFailed.size
        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.PROFILE_ACTIVATED,
            profile.name,
            "applied: ${hiddenSucceeded.size} hidden, ${timedSucceeded.size} timed; $totalFailures failures",
            if (totalFailures == 0) ZeaActivityResult.SUCCESS else ZeaActivityResult.PARTIAL
        )

        return ZeaProfileApplyResult(
            hiddenSucceeded = hiddenSucceeded,
            hiddenFailed = hiddenFailed,
            timedSucceeded = timedSucceeded,
            timedFailed = timedFailed
        )
    }

    /** Deactivating a profile simply leaves current state as-is (no-op). */
    suspend fun deactivateProfile(context: Context, profileId: String): Boolean {
        val profile = load(context).firstOrNull { it.id == profileId } ?: return false
        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.PROFILE_DEACTIVATED,
            profile.name,
            "profile left active state untouched",
            ZeaActivityResult.SUCCESS
        )
        return true
    }

    private fun encode(profiles: List<ZeaProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val timed = JSONObject()
            profile.timedPackages.forEach { (pkg, end) -> timed.put(pkg, end) }
            val obj = JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("hidden", JSONArray(profile.hiddenPackages))
                .put("timed", timed)
                .put("createdAt", profile.createdAtEpochMillis)
            array.put(obj)
        }
        return array.toString()
    }

    private fun decode(raw: String): List<ZeaProfile> {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val profiles = mutableListOf<ZeaProfile>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val hidden = mutableListOf<String>()
            val hiddenArray = obj.optJSONArray("hidden")
            if (hiddenArray != null) {
                for (hiddenIndex in 0 until hiddenArray.length()) {
                    val value = hiddenArray.optString(hiddenIndex, "")
                    if (value.isNotBlank()) hidden += value
                }
            }
            val timed = mutableMapOf<String, Long>()
            val timedObj = obj.optJSONObject("timed")
            if (timedObj != null) {
                val keys = timedObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    timed[key] = timedObj.optLong(key)
                }
            }
            profiles += ZeaProfile(
                id = obj.optString("id"),
                name = obj.optString("name"),
                hiddenPackages = hidden,
                timedPackages = timed,
                createdAtEpochMillis = obj.optLong("createdAt")
            )
        }
        return profiles
    }
}

private fun zeaSnapshotLabel(endEpochMillis: Long): String {
    val remaining = endEpochMillis - System.currentTimeMillis()
    val minutes = remaining / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        days > 0 -> "$days day(s)"
        hours > 0 -> "$hours hour(s)"
        else -> "$minutes minute(s)"
    }
}
