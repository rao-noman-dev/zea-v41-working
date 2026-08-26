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
    val failed: List<Pair<String, String>>,
    /** False when the durable journal could not be closed even though member
     *  states changed; the batch stays open for safe recovery and the UI must
     *  present the operation as partial/recoverable, never as completed. */
    val journalClosed: Boolean = true
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
        val saved = save(context, updated)
        if (saved) {
            // Any schedule that targeted this group is now dead; prune its
            // target and rearm so enabled schedules cannot point at a ghost.
            ZeaSchedules.pruneTargetsForGroup(context, groupId)
            ZeaSchedules.rearm(context)
        }
        return saved
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

    /** Removes members whose package is no longer installed. Consistent
     *  across every operation type and safe to call from load/refresh paths. */
    suspend fun pruneStaleMembers(context: Context): Int = withContext(Dispatchers.IO) {
        val installed = ZeaAppCatalog.loadManagedApps(context)
            .map { it.packageName.lowercase() }
            .toSet()
        val groups = load(context)
        var removed = 0
        val updated = groups.map { group ->
            val kept = group.memberPackages.filter {
                val keep = it.lowercase() in installed
                if (!keep) removed++
                keep
            }
            if (kept.size != group.memberPackages.size) {
                group.copy(memberPackages = kept)
            } else {
                group
            }
        }
        if (removed > 0) save(context, updated)
        removed
    }

    /**
     * Hides every member through the verified per-app transaction engine,
     * wrapped in the durable Phase-1 batch journal. If the process dies after
     * 5/20 members, the journal preserves exactly which members were verified
     * so MainActivity's interrupted-batch recovery can resume — a group batch
     * is never a silent partial operation.
     */
    suspend fun hideGroup(context: Context, groupId: String): ZeaGroupBatchResult =
        runGroupBatch(context, groupId, ZeaBatchJournal.OPERATION_HIDE, null)

    suspend fun unhideGroup(context: Context, groupId: String): ZeaGroupBatchResult =
        runGroupBatch(context, groupId, ZeaBatchJournal.OPERATION_UNHIDE, null)

    suspend fun hideGroupForTime(
        context: Context,
        groupId: String,
        request: ZeaTimedHideRequest
    ): ZeaGroupBatchResult =
        runGroupBatch(context, groupId, ZeaBatchJournal.OPERATION_TIMED_HIDE, request)

    private suspend fun runGroupBatch(
        context: Context,
        groupId: String,
        operation: String,
        request: ZeaTimedHideRequest?
    ): ZeaGroupBatchResult {
        // Stale cleanup is uniform: every operation type drops ghost members
        // first instead of only cleaning opportunistically in one path.
        pruneStaleMembers(context)
        val group = load(context).firstOrNull { it.id == groupId }
            ?: return ZeaGroupBatchResult(emptyList(), emptyList())
        val journal = ZeaBatchJournal.start(
            context,
            operation,
            group.memberPackages,
            request
        ) ?: return ZeaGroupBatchResult(
            emptyList(),
            group.memberPackages.map { it to "Another batch is already in progress; try again after it resolves." }
        )
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        // Bulk undo snapshot: per-package previous + applied state, recorded
        // once for the whole batch so member N never overwrites member N-1.
        val undoEntries = mutableListOf<ZeaUndoEntry>()
        for (packageName in group.memberPackages) {
            val app = zeaManagedAppFromPackage(context, packageName)
            if (app == null) {
                failed += packageName to "No longer installed; removed from group."
                removeMember(context, groupId, packageName)
                ZeaBatchJournal.markProcessed(context, journal.batchId, packageName)
                continue
            }
            val outcome = when (operation) {
                ZeaBatchJournal.OPERATION_UNHIDE ->
                    ZeaAppHideService.unhideApp(context, packageName, suppressUndoRecord = true)
                ZeaBatchJournal.OPERATION_TIMED_HIDE ->
                    ZeaAppHideService.hideAppForTime(
                        context, app, request!!, suppressUndoRecord = true
                    )
                else ->
                    ZeaAppHideService.hideApp(context, app, suppressUndoRecord = true)
            }
            if (outcome.success && ZeaBatchJournal.markProcessed(context, journal.batchId, packageName)) {
                succeeded += packageName
                undoEntries += ZeaUndoEntry(
                    operation = when (operation) {
                        ZeaBatchJournal.OPERATION_UNHIDE -> UndoOperation.UNHIDE
                        ZeaBatchJournal.OPERATION_TIMED_HIDE -> UndoOperation.TIMED_HIDE
                        else -> UndoOperation.HIDE
                    },
                    packageName = app.packageName,
                    displayName = app.displayName,
                    previousMode = app.hideMode,
                    timedEndEpochMillis = app.hiddenUntilEpochMillis,
                    epochMillis = System.currentTimeMillis(),
                    appliedTimedEndEpochMillis = if (operation == ZeaBatchJournal.OPERATION_TIMED_HIDE) {
                        request?.endEpochMillis ?: 0L
                    } else {
                        0L
                    }
                )
            } else {
                failed += packageName to (if (outcome.success) "Journal durability failed." else outcome.message)
            }
        }
        // Journal truth: the batch is reported as completed ONLY when the
        // durable journal actually closed. A journal that stayed open means
        // the operation is partial/recoverable, and history must say so.
        val closed = ZeaBatchJournal.complete(context, journal.batchId)
        val label = when (operation) {
            ZeaBatchJournal.OPERATION_UNHIDE -> "unhide"
            ZeaBatchJournal.OPERATION_TIMED_HIDE -> "hide for time (${request?.label})"
            else -> "hide"
        }
        recordBatch(context, label, group, succeeded.size, failed.size, closed)
        if (closed && undoEntries.isNotEmpty()) {
            ZeaUndo.recordBulk(context, undoEntries)
        }
        return ZeaGroupBatchResult(succeeded, failed, journalClosed = closed)
    }

    private suspend fun recordBatch(
        context: Context,
        operation: String,
        group: ZeaGroup,
        succeeded: Int,
        failed: Int,
        journalClosed: Boolean
    ) {
        val result = when {
            !journalClosed -> ZeaActivityResult.PARTIAL
            failed == 0 -> ZeaActivityResult.SUCCESS
            succeeded == 0 -> ZeaActivityResult.FAILURE
            else -> ZeaActivityResult.PARTIAL
        }
        ZeaActivityLog.record(
            context,
            ZeaActivityEventType.GROUP_ACTION,
            group.name,
            "$operation: $succeeded succeeded, $failed failed" +
                    if (journalClosed) "" else "; journal left open for recovery",
            result
        )
        // Batch summary event: BATCH_COMPLETED is written ONLY when the
        // durable journal actually closed. An open journal means the batch is
        // still recoverable, so history records it as such instead of lying.
        if (journalClosed) {
            ZeaActivityLog.record(
                context,
                ZeaActivityEventType.BATCH_COMPLETED,
                group.name,
                "batch closed: $operation, $succeeded/${succeeded + failed} verified",
                result
            )
        } else {
            ZeaActivityLog.record(
                context,
                ZeaActivityEventType.RECOVERY,
                group.name,
                "batch NOT durably closed: $operation, $succeeded/${succeeded + failed} verified; recovery pending",
                ZeaActivityResult.PARTIAL
            )
        }
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
