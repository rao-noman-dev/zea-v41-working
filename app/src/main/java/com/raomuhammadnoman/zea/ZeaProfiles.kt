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
    val skipped: List<String> = emptyList(),
    /** True when apps were changed but the ownership snapshot failed to
     * persist; restoration ownership is at risk, never a clean success. */
    val ownershipPersistFailed: Boolean = false
)

/** What deactivation must do with one owned member. Pure, so every ownership
 *  transition is covered by deterministic unit tests. */
enum class ZeaProfileEndAction {
    /** Restore full visibility (member was VISIBLE before activation). */
    RESTORE_VISIBLE,
    /** Restore the permanent hide the member had before activation — the
     *  profile's temporary TIMED state must never outlive deactivation. */
    RESTORE_HIDDEN,
    /** Re-arm the member's ORIGINAL timer (deadline still in the future). */
    RESTORE_TIMED,
    /** The member's state changed independently after activation; reversing
     *  it would destroy newer manual state. Ownership is released. */
    SKIP_INDEPENDENT
}

/**
 * Deactivation decision for one member:
 *
 *  - current state must still match the state the profile APPLIED, otherwise
 *    the reversal is unsafe (independent change) → SKIP_INDEPENDENT;
 *  - previous VISIBLE → RESTORE_VISIBLE;
 *  - previous HIDDEN → RESTORE_HIDDEN (never leave the profile timer alive);
 *  - previous TIMED → RESTORE_TIMED when the original deadline is still in
 *    the future, otherwise RESTORE_VISIBLE (the timer would have expired
 *    anyway; leaving the app hidden past its own deadline is dishonest).
 */
fun zeaProfileDeactivatePlan(
    snapshot: ZeaProfileOwnershipSnapshot,
    currentMode: ZeaHideMode,
    currentTimedEndEpochMillis: Long,
    nowEpochMillis: Long
): ZeaProfileEndAction {
    val stillOwned = when (snapshot.appliedMode) {
        ZeaHideMode.HIDDEN -> currentMode == ZeaHideMode.HIDDEN
        ZeaHideMode.TIMED ->
            currentMode == ZeaHideMode.TIMED &&
                    currentTimedEndEpochMillis == snapshot.appliedTimedEndEpochMillis
        ZeaHideMode.VISIBLE -> currentMode == ZeaHideMode.VISIBLE
    }
    if (!stillOwned) return ZeaProfileEndAction.SKIP_INDEPENDENT
    return when (snapshot.previousMode) {
        ZeaHideMode.VISIBLE -> ZeaProfileEndAction.RESTORE_VISIBLE
        ZeaHideMode.HIDDEN -> ZeaProfileEndAction.RESTORE_HIDDEN
        ZeaHideMode.TIMED ->
            if (snapshot.previousTimedEndEpochMillis > nowEpochMillis) {
                ZeaProfileEndAction.RESTORE_TIMED
            } else {
                ZeaProfileEndAction.RESTORE_VISIBLE
            }
    }
}

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

        val timed = withContext(Dispatchers.IO) {
            loadTimedHides(context).associate { record ->
                record.packageName to record.hiddenUntilEpochMillis
            }
        }
        // Timed apps keep their TIMED identity and deadline; they are never
        // duplicated into the hidden list. hidden = private minus timed.
        val timedKeys = timed.keys.mapTo(mutableSetOf()) { it.lowercase() }
        val hidden = withContext(Dispatchers.IO) {
            loadPrivateApps(context)
                .map { it.packageName }
                .filter { it.lowercase() !in timedKeys }
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

    /**
     * Edit never destroys recovery metadata: the persisted ownership snapshot
     * always wins over whatever the caller passed in, so editing membership of
     * an ACTIVE profile cannot orphan the restoration record.
     */
    suspend fun updateProfile(context: Context, profile: ZeaProfile): Boolean {
        val updated = load(context).map { existing ->
            if (existing.id == profile.id) {
                profile.copy(ownership = existing.ownership)
            } else {
                existing
            }
        }
        return save(context, updated)
    }

    /**
     * Delete is blocked while the profile is active: its ownership snapshot
     * must be consumed (via deactivate) before the record may disappear,
     * otherwise restoration state would be lost with owned changes applied.
     */
    suspend fun deleteProfile(context: Context, profileId: String): Boolean {
        val profiles = load(context)
        val target = profiles.firstOrNull { it.id == profileId } ?: return false
        if (target.isActive) return false
        val updated = profiles.filterNot { it.id == profileId }
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
        // Repeated activation must never overwrite the original pre-profile
        // snapshot: existing ownership is the restoration source of truth.
        val ownership = profile.ownership.toMutableMap()
        val now = System.currentTimeMillis()

        // Timed takes precedence: a member listed in BOTH collections is a
        // timed member, never both. The same original state must never be
        // classified as HIDDEN and TIMED at once.
        val liveTimed = profile.timedPackages.filterValues { it > now }
        val timedKeys = liveTimed.keys.mapTo(mutableSetOf()) { it.lowercase() }
        val hiddenMembers = profile.hiddenPackages.distinct().filter {
            it.lowercase() !in timedKeys
        }

        // Permanent members: capture prior state, then hide. Ownership is
        // recorded ONLY when the member actually ends up in the applied
        // state — a failed application must not fabricate a claim.
        for (packageName in hiddenMembers) {
            val app = zeaManagedAppFromPackage(context, packageName) ?: continue
            if (ownership.containsKey(packageName)) {
                // Repeated activation is idempotent: the original pre-profile
                // snapshot is the restoration source of truth and is never
                // overwritten by a re-activation.
                continue
            }
            if (app.hideMode == ZeaHideMode.HIDDEN) {
                ownership[packageName] = ZeaProfileOwnershipSnapshot(
                    previousMode = app.hideMode,
                    previousTimedEndEpochMillis = app.hiddenUntilEpochMillis,
                    appliedMode = ZeaHideMode.HIDDEN,
                    appliedTimedEndEpochMillis = 0L
                )
                continue
            }
            val outcome = ZeaAppHideService.hideApp(context, app)
            if (outcome.success) {
                ownership[packageName] = ZeaProfileOwnershipSnapshot(
                    previousMode = app.hideMode,
                    previousTimedEndEpochMillis = app.hiddenUntilEpochMillis,
                    appliedMode = ZeaHideMode.HIDDEN,
                    appliedTimedEndEpochMillis = 0L
                )
                hiddenSucceeded += packageName
            } else {
                hiddenFailed += packageName to outcome.message
            }
        }

        // Timed members: capture prior state, then arm the stored end.
        for ((packageName, endEpoch) in liveTimed) {
            val app = zeaManagedAppFromPackage(context, packageName) ?: continue
            if (ownership.containsKey(packageName)) {
                // Idempotent repeated activation (see above).
                continue
            }
            val label = "until ${zeaSnapshotLabel(endEpoch)}"
            val outcome = ZeaAppHideService.hideAppForTime(
                context,
                app,
                ZeaTimedHideRequest(label = label, endEpochMillis = endEpoch)
            )
            if (outcome.success) {
                ownership[packageName] = ZeaProfileOwnershipSnapshot(
                    previousMode = app.hideMode,
                    previousTimedEndEpochMillis = app.hiddenUntilEpochMillis,
                    appliedMode = ZeaHideMode.TIMED,
                    appliedTimedEndEpochMillis = endEpoch
                )
                timedSucceeded += packageName
            } else {
                timedFailed += packageName to outcome.message
            }
        }

        // Persist ownership so deactivation can restore safely. A failed
        // persist while apps were actually changed is reported as PARTIAL:
        // the applied state is real but restoration ownership is at risk.
        val updatedProfiles = load(context).map { existing ->
            if (existing.id == profileId) existing.copy(ownership = ownership) else existing
        }
        val ownershipPersisted = save(context, updatedProfiles)
        val ownershipPersistFailed =
            !ownershipPersisted && (hiddenSucceeded.isNotEmpty() || timedSucceeded.isNotEmpty())

        val totalFailures = hiddenFailed.size + timedFailed.size
        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.PROFILE_ACTIVATED,
            profile.name,
            "applied: ${hiddenSucceeded.size} hidden, ${timedSucceeded.size} timed; $totalFailures failures" +
                    if (ownershipPersistFailed) "; ownership persistence FAILED" else "",
            if (totalFailures == 0 && !ownershipPersistFailed) ZeaActivityResult.SUCCESS
            else ZeaActivityResult.PARTIAL
        )

        return ZeaProfileApplyResult(
            hiddenSucceeded = hiddenSucceeded,
            hiddenFailed = hiddenFailed,
            timedSucceeded = timedSucceeded,
            timedFailed = timedFailed,
            ownershipPersistFailed = ownershipPersistFailed
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
        val hiddenSucceeded = mutableListOf<String>()
        val hiddenFailed = mutableListOf<Pair<String, String>>()
        val timedSucceeded = mutableListOf<String>()
        val timedFailed = mutableListOf<Pair<String, String>>()
        val skipped = mutableListOf<String>()
        val now = System.currentTimeMillis()

        // Members whose restore FAILED keep their ownership so deactivation
        // can be retried safely; everything else is consumed.
        val retainedOwnership = mutableMapOf<String, ZeaProfileOwnershipSnapshot>()

        for ((packageName, snapshot) in profile.ownership) {
            val app = zeaManagedAppFromPackage(context, packageName)
            if (app == null) {
                // App vanished: keep the claim so a reinstall/retry can still
                // reconcile instead of silently dropping recovery metadata.
                retainedOwnership[packageName] = snapshot
                continue
            }
            when (zeaProfileDeactivatePlan(
                snapshot,
                app.hideMode,
                app.hiddenUntilEpochMillis,
                now
            )) {
                ZeaProfileEndAction.SKIP_INDEPENDENT -> {
                    // State changed independently after activation; never
                    // overwrite. The claim is released because the profile no
                    // longer owns this state.
                    skipped += packageName
                }
                ZeaProfileEndAction.RESTORE_VISIBLE -> {
                    val outcome = ZeaAppHideService.unhideApp(context, packageName)
                    if (outcome.success) unhiddenSucceeded += packageName
                    else {
                        unhiddenFailed += packageName to outcome.message
                        retainedOwnership[packageName] = snapshot
                    }
                }
                ZeaProfileEndAction.RESTORE_HIDDEN -> {
                    // Was permanently hidden before activation: the profile's
                    // temporary state must be reversed back to a PERMANENT
                    // hide, never left as a profile timer that later exposes
                    // the app.
                    if (app.hideMode == ZeaHideMode.HIDDEN) {
                        // Already in the restored state.
                        hiddenSucceeded += packageName
                    } else {
                        val outcome = ZeaAppHideService.hideApp(context, app)
                        if (outcome.success) hiddenSucceeded += packageName
                        else {
                            hiddenFailed += packageName to outcome.message
                            retainedOwnership[packageName] = snapshot
                        }
                    }
                }
                ZeaProfileEndAction.RESTORE_TIMED -> {
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
                    else {
                        timedFailed += packageName to outcome.message
                        retainedOwnership[packageName] = snapshot
                    }
                }
            }
        }

        // Ownership is consumed ONLY for members that restored cleanly or were
        // consciously released; failed restores stay owned for a safe retry.
        val updatedProfiles = load(context).map { existing ->
            if (existing.id == profileId) {
                existing.copy(ownership = retainedOwnership)
            } else {
                existing
            }
        }
        save(context, updatedProfiles)

        val totalFailures = unhiddenFailed.size + timedFailed.size + hiddenFailed.size
        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.PROFILE_DEACTIVATED,
            profile.name,
            "restored: ${unhiddenSucceeded.size} unhidden, ${timedSucceeded.size} timed restored, " +
                    "${skipped.size} skipped (independent state); $totalFailures failures" +
                    if (retainedOwnership.isNotEmpty()) {
                        "; ${retainedOwnership.size} kept owned for retry"
                    } else {
                        ""
                    },
            if (totalFailures == 0) ZeaActivityResult.SUCCESS else ZeaActivityResult.PARTIAL
        )
        return ZeaProfileApplyResult(
            hiddenSucceeded = hiddenSucceeded,
            hiddenFailed = hiddenFailed,
            timedSucceeded = timedSucceeded,
            timedFailed = timedFailed,
            unhiddenSucceeded = unhiddenSucceeded,
            unhiddenFailed = unhiddenFailed,
            skipped = skipped
        )
    }

    /**
     * Drops members that are no longer installed from every profile's
     * membership lists. Ownership snapshots are NEVER touched: they are the
     * restoration record for currently applied state, not membership.
     * Returns the number of stale references removed.
     */
    suspend fun pruneStaleMembership(context: Context): Int = withContext(Dispatchers.IO) {
        val installed = ZeaAppCatalog.loadManagedApps(context)
            .map { it.packageName.lowercase() }
            .toSet()
        val profiles = load(context)
        var removed = 0
        val updated = profiles.map { profile ->
            val hidden = profile.hiddenPackages.filter {
                val keep = it.lowercase() in installed
                if (!keep) removed++
                keep
            }
            val timed = profile.timedPackages.filterKeys {
                val keep = it.lowercase() in installed
                if (!keep) removed++
                keep
            }
            if (hidden.size != profile.hiddenPackages.size || timed.size != profile.timedPackages.size) {
                profile.copy(hiddenPackages = hidden, timedPackages = timed)
            } else {
                profile
            }
        }
        if (removed > 0) save(context, updated)
        removed
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

internal fun zeaSnapshotLabel(endEpochMillis: Long): String {
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
