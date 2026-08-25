package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ZeaGroup(
    val id: String,
    val name: String,
    val memberPackages: List<String>,
    val createdAtEpochMillis: Long
)

data class ZeaGroupBatchResult(
    val succeeded: List<String>,
    val failed: List<Pair<String, String>>
)

/**
 * Phase 3 App Groups / Collections. Members are referenced by package id only;
 * group data never duplicates the private/timed registries. Bulk operations
 * re-use the verified [ZeaAppHideService] transactional engine per app.
 */
object ZeaGroups {
    private const val KEY_GROUPS = "app_groups_v1"

    suspend fun load(context: Context): List<ZeaGroup> = withContext(Dispatchers.IO) {
        val raw = getZeaPrefs(context.applicationContext)
            .getString(KEY_GROUPS, null) ?: return@withContext emptyList()
        decode(raw)
    }

    private suspend fun save(context: Context, groups: List<ZeaGroup>): Boolean =
        withContext(Dispatchers.IO) {
            getZeaPrefs(context.applicationContext)
                .edit()
                .putString(KEY_GROUPS, encode(groups))
                .commit()
        }

    suspend fun createGroup(context: Context, name: String): ZeaGroup? {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > 60) return null
        val groups = load(context).toMutableList()
        if (groups.any { it.name.equals(cleanName, ignoreCase = true) }) return null
        val group = ZeaGroup(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            memberPackages = emptyList(),
            createdAtEpochMillis = System.currentTimeMillis()
        )
        groups += group
        return if (save(context, groups)) group else null
    }

    suspend fun renameGroup(context: Context, groupId: String, newName: String): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isEmpty() || cleanName.length > 60) return false
        val groups = load(context)
        val target = groups.firstOrNull { it.id == groupId } ?: return false
        if (groups.any { it.id != groupId && it.name.equals(cleanName, ignoreCase = true) }) {
            return false
        }
        val updated = groups.map { group ->
            if (group.id == groupId) group.copy(name = cleanName) else group
        }
        return save(context, updated)
    }

    suspend fun deleteGroup(context: Context, groupId: String): Boolean {
        // Deleting never hides/unhides members; only membership is dropped.
        val updated = load(context).filterNot { it.id == groupId }
        return save(context, updated)
    }

    suspend fun addMember(context: Context, groupId: String, packageName: String): Boolean {
        val groups = load(context)
        val target = groups.firstOrNull { it.id == groupId } ?: return false
        if (target.memberPackages.contains(packageName)) return true
        val updated = groups.map { group ->
            if (group.id == groupId) {
                group.copy(memberPackages = group.memberPackages + packageName)
            } else group
        }
        return save(context, updated)
    }

    suspend fun removeMember(context: Context, groupId: String, packageName: String): Boolean {
        val groups = load(context)
        val target = groups.firstOrNull { it.id == groupId } ?: return false
        if (!target.memberPackages.contains(packageName)) return true
        val updated = groups.map { group ->
            if (group.id == groupId) {
                group.copy(memberPackages = group.memberPackages - packageName)
            } else group
        }
        return save(context, updated)
    }

    suspend fun setMembers(
        context: Context,
        groupId: String,
        packages: List<String>
    ): Boolean {
        val updated = load(context).map { group ->
            if (group.id == groupId) {
                group.copy(memberPackages = packages.distinct())
            } else group
        }
        return save(context, updated)
    }

    /**
     * Hides every member through the verified per-app transaction engine.
     * Reports honest per-app failures; succeeds independently per member.
     */
    suspend fun hideGroup(context: Context, groupId: String): ZeaGroupBatchResult {
        val group = load(context).firstOrNull { it.id == groupId }
            ?: return ZeaGroupBatchResult(emptyList(), emptyList())
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (packageName in group.memberPackages) {
            val app = zeaManagedAppFromPackage(context, packageName)
            if (app == null) {
                failed += packageName to "No longer installed; removed from group."
                removeMember(context, groupId, packageName)
                continue
            }
            val outcome = ZeaAppHideService.hideApp(context, app)
            if (outcome.success) {
                succeeded += packageName
            } else {
                failed += packageName to outcome.message
            }
        }
        recordBatch(context, "hide", group, succeeded.size, failed.size)
        return ZeaGroupBatchResult(succeeded, failed)
    }

    suspend fun unhideGroup(context: Context, groupId: String): ZeaGroupBatchResult {
        val group = load(context).firstOrNull { it.id == groupId }
            ?: return ZeaGroupBatchResult(emptyList(), emptyList())
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (packageName in group.memberPackages) {
            val outcome = ZeaAppHideService.unhideApp(context, packageName)
            if (outcome.success) {
                succeeded += packageName
            } else {
                failed += packageName to outcome.message
            }
        }
        recordBatch(context, "unhide", group, succeeded.size, failed.size)
        return ZeaGroupBatchResult(succeeded, failed)
    }

    suspend fun hideGroupForTime(
        context: Context,
        groupId: String,
        request: ZeaTimedHideRequest
    ): ZeaGroupBatchResult {
        val group = load(context).firstOrNull { it.id == groupId }
            ?: return ZeaGroupBatchResult(emptyList(), emptyList())
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (packageName in group.memberPackages) {
            val app = zeaManagedAppFromPackage(context, packageName)
            if (app == null) {
                failed += packageName to "No longer installed; removed from group."
                removeMember(context, groupId, packageName)
                continue
            }
            val outcome = ZeaAppHideService.hideAppForTime(context, app, request)
            if (outcome.success) {
                succeeded += packageName
            } else {
                failed += packageName to outcome.message
            }
        }
        recordBatch(context, "hide for time (${request.label})", group, succeeded.size, failed.size)
        return ZeaGroupBatchResult(succeeded, failed)
    }

    private suspend fun recordBatch(
        context: Context,
        operation: String,
        group: ZeaGroup,
        succeeded: Int,
        failed: Int
    ) {
        val result = when {
            failed == 0 -> ZeaActivityResult.SUCCESS
            succeeded == 0 -> ZeaActivityResult.FAILURE
            else -> ZeaActivityResult.PARTIAL
        }
        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.GROUP_ACTION,
            group.name,
            "$operation: $succeeded succeeded, $failed failed",
            result
        )
    }

    private fun encode(groups: List<ZeaGroup>): String {
        val array = JSONArray()
        groups.forEach { group ->
            val obj = JSONObject()
                .put("id", group.id)
                .put("name", group.name)
                .put("createdAt", group.createdAtEpochMillis)
                .put("members", JSONArray(group.memberPackages))
            array.put(obj)
        }
        return array.toString()
    }

    private fun decode(raw: String): List<ZeaGroup> {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val groups = mutableListOf<ZeaGroup>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val members = mutableListOf<String>()
            val memberArray = obj.optJSONArray("members")
            if (memberArray != null) {
                for (memberIndex in 0 until memberArray.length()) {
                    members += memberArray.optString(memberIndex, "")
                }
            }
            groups += ZeaGroup(
                id = obj.optString("id"),
                name = obj.optString("name"),
                memberPackages = members.filter { it.isNotBlank() },
                createdAtEpochMillis = obj.optLong("createdAt")
            )
        }
        return groups
    }
}

/** Resolves a package into the managed-app model used by hide/unhide engines. */
suspend fun zeaManagedAppFromPackage(
    context: Context,
    packageName: String
): ZeaManagedApp? = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val catalog = ZeaAppCatalog.loadManagedApps(appContext)
    return@withContext catalog.firstOrNull {
        it.packageName.equals(packageName, ignoreCase = true)
    }
}
