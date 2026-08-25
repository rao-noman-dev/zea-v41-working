package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * What a profile did to one member, plus what that member looked like BEFORE
 * the profile activated. This is the ownership record: deactivation is only
 * allowed to reverse [appliedMode] back to [previousMode], and only while the
 * member still matches the applied state. Any independent change after
 * activation invalidates the reversal for that member.
 */
data class ZeaProfileOwnershipSnapshot(
    val previousMode: ZeaHideMode,
    val previousTimedEndEpochMillis: Long,
    val appliedMode: ZeaHideMode,
    val appliedTimedEndEpochMillis: Long
)

data class ZeaProfile(
    val id: String,
    val name: String,
    /** Packages to keep hidden (permanent/timed applies at activation time). */
    val hiddenPackages: List<String>,
    val timedPackages: Map<String, Long>,
    val createdAtEpochMillis: Long,
    /** Ownership snapshot per member; empty when the profile is not active. */
    val ownership: Map<String, ZeaProfileOwnershipSnapshot> = emptyMap()
) {
    val isActive: Boolean
        get() = ownership.isNotEmpty()
}

/** Snapshot of the current protection state used for capture-as-profile. */
data class ZeaProfileSnapshot(
    val hiddenPackages: List<String>,
    val timedPackages: Map<String, Long>
)

data class ZeaProfileApplyResult(
    val hiddenSucceeded: List<String>,
    val hiddenFailed: List<Pair<String, String>>,
    val timedSucceeded: List<String>,
    val timedFailed: List<Pair<String, String>>,
    val unhiddenSucceeded: List<String> = emptyList(),
    val unhiddenFailed: List<Pair<String, String>> = emptyList(),
    /** Members skipped because their state changed independently after the
     * profile claimed them; reversing them would destroy manual user state. */
    val skipped: List<String> = emptyList()
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
            createdAtEpochMillis = System.currentTimeMillis(),
            // Ownership belongs to the source profile's activation only;
            // a duplicate must never inherit another profile's claim.
            ownership = emptyMap()
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
     * Applies a profile with true ownership semantics:
     *
     *  - ONLY the profile's own members are touched. Apps protected outside
     *    the profile are NEVER unhidden by activation.
     *  - Before a member is hidden/timed, its prior state is captured into
     *    [ZeaProfile.ownership] so deactivation can restore it exactly.
     *  - Hide, timed and failure results are reported separately and honestly.
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
        val ownership = mutableMapOf<String, ZeaProfileOwnershipSnapshot>()
        val now = System.currentTimeMillis()

        // Permanent members: capture prior state, then hide.
        for (packageName in profile.hiddenPackages.distinct()) {
            val app = zeaManagedAppFromPackage(context, packageName) ?: continue
            val prior = ZeaProfileOwnershipSnapshot(
                previousMode = app.hideMode,
                previousTimedEndEpochMillis = app.hiddenUntilEpochMillis,
                appliedMode = ZeaHideMode.HIDDEN,
                appliedTimedEndEpochMillis = 0L
            )
            if (app.hideMode == ZeaHideMode.HIDDEN) {
                // Already hidden: owned for restore purposes, no op needed.
                ownership[packageName] = prior
                continue
            }
            val outcome = ZeaAppHideService.hideApp(context, app)
            if (outcome.success) {
                hiddenSucceeded += packageName
                ownership[packageName] = prior
            } else {
                hiddenFailed += packageName to outcome.message
            }
        }

        // Timed members: capture prior state, then re-arm with stored end.
        for ((packageName, endEpoch) in profile.timedPackages) {
            if (endEpoch <= now) continue
            val app = zeaManagedAppFromPackage(context, packageName) ?: continue
            val prior = ZeaProfileOwnershipSnapshot(
                previousMode = app.hideMode,
                previousTimedEndEpochMillis = app.hiddenUntilEpochMillis,
                appliedMode = ZeaHideMode.TIMED,
                appliedTimedEndEpochMillis = endEpoch
            )
            val label = "until ${zeaSnapshotLabel(endEpoch)}"
            val outcome = ZeaAppHideService.hideAppForTime(
                context,
                app,
                ZeaTimedHideRequest(label = label, endEpochMillis = endEpoch)
            )
            if (outcome.success) {
                timedSucceeded += packageName
                ownership[packageName] = prior
            } else {
                timedFailed += packageName to outcome.message
            }
        }

        // Persist ownership so deactivation can restore safely.
        val updatedProfiles = load(context).map { existing ->
            if (existing.id == profileId) existing.copy(ownership = ownership) else existing
        }
        save(context, updatedProfiles)

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

    /**
     * Deactivates a profile with true ownership semantics:
     *
     *  - ONLY members the profile previously claimed are considered.
     *  - A member is restored to its pre-activation state ONLY when its
     *    current state still matches what the profile applied. If the user
     *    changed it independently (unhid, extended, re-timed, hid again),
     *    the member is SKIPPED — the profile never destroys manual state.
     *  - Members that were already hidden before activation STAY hidden.
     *  - Prior timed state is restored with its original deadline when still
     *    in the future; an expired prior timer simply means "leave visible".
     */
    suspend fun deactivateProfile(
        context: Context,
        profileId: String
    ): ZeaProfileApplyResult {
        val profile = load(context).firstOrNull { it.id == profileId }
            ?: return ZeaProfileApplyResult(emptyList(), emptyList(), emptyList(), emptyList())

        val unhiddenSucceeded = mutableListOf<String>()
        val unhiddenFailed = mutableListOf<Pair<String, String>>()
        val timedSucceeded = mutableListOf<String>()
        val timedFailed = mutableListOf<Pair<String, String>>()
        val skipped = mutableListOf<String>()
        val now = System.currentTimeMillis()

        for ((packageName, snapshot) in profile.ownership) {
            val app = zeaManagedAppFromPackage(context, packageName) ?: continue
            val stillOwned = when (snapshot.appliedMode) {
                ZeaHideMode.HIDDEN -> app.hideMode == ZeaHideMode.HIDDEN
                ZeaHideMode.TIMED ->
                    app.hideMode == ZeaHideMode.TIMED &&
                            app.hiddenUntilEpochMillis == snapshot.appliedTimedEndEpochMillis
                ZeaHideMode.VISIBLE -> app.hideMode == ZeaHideMode.VISIBLE
            }
            if (!stillOwned) {
                // State changed independently after activation; never overwrite.
                skipped += packageName
                continue
            }

            when (snapshot.previousMode) {
                ZeaHideMode.VISIBLE -> {
                    val outcome = ZeaAppHideService.unhideApp(context, packageName)
                    if (outcome.success) unhiddenSucceeded += packageName
                    else unhiddenFailed += packageName to outcome.message
                }
                ZeaHideMode.HIDDEN -> {
                    // Was already hidden before activation: preserve it.
                }
                ZeaHideMode.TIMED -> {
                    if (snapshot.previousTimedEndEpochMillis > now) {
                        val label = "until ${zeaSnapshotLabel(snapshot.previousTimedEndEpochMillis)}"
                        val outcome = ZeaAppHideService.hideAppForTime(
                            context,
                            app,
                            ZeaTimedHideRequest(
                                label = label,
                                endEpochMillis = snapshot.previousTimedEndEpochMillis
                            )
                        )
                        if (outcome.success) timedSucceeded += packageName
                        else timedFailed += packageName to outcome.message
                    } else {
                        // Prior timer had already expired: leave the app visible.
                        val outcome = ZeaAppHideService.unhideApp(context, packageName)
                        if (outcome.success) unhiddenSucceeded += packageName
                        else unhiddenFailed += packageName to outcome.message
                    }
                }
            }
        }

        // Ownership is consumed: the profile is no longer active.
        val updatedProfiles = load(context).map { existing ->
            if (existing.id == profileId) existing.copy(ownership = emptyMap()) else existing
        }
        save(context, updatedProfiles)

        val totalFailures = unhiddenFailed.size + timedFailed.size
        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.PROFILE_DEACTIVATED,
            profile.name,
            "restored: ${unhiddenSucceeded.size} unhidden, ${timedSucceeded.size} timed restored, " +
                    "${skipped.size} skipped (independent state); $totalFailures failures",
            if (totalFailures == 0) ZeaActivityResult.SUCCESS else ZeaActivityResult.PARTIAL
        )
        return ZeaProfileApplyResult(
            hiddenSucceeded = emptyList(),
            hiddenFailed = emptyList(),
            timedSucceeded = timedSucceeded,
            timedFailed = timedFailed,
            unhiddenSucceeded = unhiddenSucceeded,
            unhiddenFailed = unhiddenFailed,
            skipped = skipped
        )
    }

    private fun encode(profiles: List<ZeaProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val timed = JSONObject()
            profile.timedPackages.forEach { (pkg, end) -> timed.put(pkg, end) }
            val ownership = JSONObject()
            profile.ownership.forEach { (pkg, snapshot) ->
                ownership.put(
                    pkg,
                    JSONObject()
                        .put("previousMode", snapshot.previousMode.name)
                        .put("previousTimedEnd", snapshot.previousTimedEndEpochMillis)
                        .put("appliedMode", snapshot.appliedMode.name)
                        .put("appliedTimedEnd", snapshot.appliedTimedEndEpochMillis)
                )
            }
            val obj = JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("hidden", JSONArray(profile.hiddenPackages))
                .put("timed", timed)
                .put("createdAt", profile.createdAtEpochMillis)
                .put("ownership", ownership)
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
            val ownership = mutableMapOf<String, ZeaProfileOwnershipSnapshot>()
            val ownershipObj = obj.optJSONObject("ownership")
            if (ownershipObj != null) {
                val keys = ownershipObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val snapshotObj = ownershipObj.optJSONObject(key) ?: continue
                    val previousMode = runCatching {
                        ZeaHideMode.valueOf(snapshotObj.optString("previousMode"))
                    }.getOrNull() ?: continue
                    val appliedMode = runCatching {
                        ZeaHideMode.valueOf(snapshotObj.optString("appliedMode"))
                    }.getOrNull() ?: continue
                    ownership[key] = ZeaProfileOwnershipSnapshot(
                        previousMode = previousMode,
                        previousTimedEndEpochMillis = snapshotObj.optLong("previousTimedEnd"),
                        appliedMode = appliedMode,
                        appliedTimedEndEpochMillis = snapshotObj.optLong("appliedTimedEnd")
                    )
                }
            }
            profiles += ZeaProfile(
                id = obj.optString("id"),
                name = obj.optString("name"),
                hiddenPackages = hidden,
                timedPackages = timed,
                createdAtEpochMillis = obj.optLong("createdAt"),
                ownership = ownership
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
