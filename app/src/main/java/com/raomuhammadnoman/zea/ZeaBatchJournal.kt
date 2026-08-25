package com.raomuhammadnoman.zea

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Durable record of one bulk batch operation.
 *
 * The journal is evidence, never truth: an interrupted batch must always be
 * classified against the actual platform state before it is shown to the user
 * or resumed. The active record is intentionally retained until every target
 * has either been verified and journaled or the user explicitly abandons it.
 */
data class ZeaBatchJournalRecord(
    val batchId: String,
    val operation: String,
    val startedAtEpochMillis: Long,
    val targets: List<String>,
    val processed: List<String>,
    val timedEndEpochMillis: Long = 0L,
    val timedLabel: String = ""
) {
    val isTimedHide: Boolean
        get() = operation == ZeaBatchJournal.OPERATION_TIMED_HIDE

    fun timedRequestOrNull(): ZeaTimedHideRequest? {
        if (!isTimedHide || timedEndEpochMillis <= 0L || timedLabel.isBlank()) {
            return null
        }
        return ZeaTimedHideRequest(
            label = timedLabel,
            endEpochMillis = timedEndEpochMillis
        )
    }
}

object ZeaBatchJournal {

    private const val TAG = ZEA_DEVICE_OWNER_LOG_TAG

    private const val KEY_ACTIVE = "batch_journal_active_json_v1"
    private const val KEY_HISTORY = "batch_journal_history_json_v1"
    private const val HISTORY_LIMIT = 10
    private const val SCHEMA_VERSION = 2

    const val OPERATION_HIDE = "hide"
    const val OPERATION_UNHIDE = "unhide"
    const val OPERATION_TIMED_HIDE = "timed_hide"

    private val supportedOperations = setOf(
        OPERATION_HIDE,
        OPERATION_UNHIDE,
        OPERATION_TIMED_HIDE
    )

    fun readActive(context: Context): ZeaBatchJournalRecord? {
        val prefs = getZeaPrefs(context)
        val stored = readRawActive(context)
        if (stored.isBlank()) {
            return null
        }

        val decoded = decode(stored)
        if (decoded != null) {
            return decoded
        }

        Log.w(TAG, "batch journal corrupt; preserving a diagnostic history entry")
        val archived = archive(
            context,
            JSONObject()
                .put("outcome", "corrupt")
                .put("endedAtEpochMillis", System.currentTimeMillis())
                .put("rawLength", stored.length)
        )
        if (archived) {
            val cleared = prefs.edit().putString(KEY_ACTIVE, "").commit()
            if (!cleared) {
                Log.w(TAG, "corrupt batch journal archived but active slot could not be cleared")
            }
        } else {
            // Do not overwrite an unreadable record when it could not first be
            // preserved in history. start() also checks the raw active slot.
            Log.e(TAG, "corrupt batch journal could not be archived; active slot remains fail-closed")
        }
        return null
    }

    suspend fun start(
        context: Context,
        operation: String,
        targetPackages: List<String>,
        timedRequest: ZeaTimedHideRequest? = null
    ): ZeaBatchJournalRecord? = withContext(Dispatchers.IO) {
        val normalizedOperation = operation.trim().lowercase(Locale.ROOT)
        val normalizedTargets = targetPackages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }

        if (normalizedOperation !in supportedOperations || normalizedTargets.isEmpty()) {
            Log.w(TAG, "batch journal rejected invalid start op=$operation targets=${normalizedTargets.size}")
            return@withContext null
        }
        if (normalizedOperation == OPERATION_TIMED_HIDE) {
            if (timedRequest == null || timedRequest.label.isBlank() || timedRequest.endEpochMillis <= System.currentTimeMillis()) {
                Log.w(TAG, "batch journal rejected timed hide without a valid durable timer contract")
                return@withContext null
            }
        } else if (timedRequest != null) {
            Log.w(TAG, "batch journal rejected timer metadata for non-timed operation=$normalizedOperation")
            return@withContext null
        }

        // readActive() may repair a corrupt slot. The second raw check prevents
        // a new batch from overwriting a corrupt slot if its archive failed.
        if (readActive(context) != null || readRawActive(context).isNotBlank()) {
            return@withContext null
        }

        val record = ZeaBatchJournalRecord(
            batchId = UUID.randomUUID().toString(),
            operation = normalizedOperation,
            startedAtEpochMillis = System.currentTimeMillis(),
            targets = normalizedTargets,
            processed = emptyList(),
            timedEndEpochMillis = timedRequest?.endEpochMillis ?: 0L,
            timedLabel = timedRequest?.label.orEmpty()
        )
        val committed = getZeaPrefs(context).edit()
            .putString(KEY_ACTIVE, encode(record).toString())
            .commit()
        if (!committed) {
            Log.w(TAG, "batch journal start failed to persist")
            return@withContext null
        }
        Log.i(
            TAG,
            "batch journal started id=${record.batchId} op=$normalizedOperation targets=${record.targets.size}"
        )
        record
    }

    suspend fun markProcessed(
        context: Context,
        batchId: String,
        packageName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val current = readActive(context) ?: return@withContext false
        if (current.batchId != batchId) {
            return@withContext false
        }
        if (current.processed.any { it.equals(packageName, ignoreCase = true) }) {
            // Idempotence is essential during recovery: a crash after the
            // journal commit but before the caller sees the return value must
            // not convert a successful write into a false failure on retry.
            return@withContext true
        }
        if (current.targets.none { it.equals(packageName, ignoreCase = true) }) {
            Log.w(TAG, "batch journal rejected progress outside target set id=$batchId package=$packageName")
            return@withContext false
        }

        val updated = current.copy(processed = current.processed + packageName)
        val committed = getZeaPrefs(context).edit()
            .putString(KEY_ACTIVE, encode(updated).toString())
            .commit()
        if (!committed) {
            Log.w(TAG, "batch journal progress write failed id=$batchId package=$packageName")
        }
        committed
    }

    fun allTargetsProcessed(record: ZeaBatchJournalRecord): Boolean {
        val processedKeys = record.processed.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        return record.targets.all { it.lowercase(Locale.ROOT) in processedKeys }
    }

    /**
     * Closes a finished batch. The active slot is cleared only after the
     * summary is durably archived, so failure of the history write leaves the
     * active record recoverable rather than silently deleting evidence.
     */
    suspend fun complete(context: Context, batchId: String): Boolean =
        close(context, batchId, outcome = "completed")

    suspend fun abandon(context: Context, batchId: String): Boolean =
        close(context, batchId, outcome = "abandoned")

    private suspend fun close(
        context: Context,
        batchId: String,
        outcome: String
    ): Boolean = withContext(Dispatchers.IO) {
        val current = readActive(context)
        if (current == null || current.batchId != batchId) {
            return@withContext false
        }
        if (outcome == "completed" && !allTargetsProcessed(current)) {
            Log.w(
                TAG,
                "batch journal refused premature completion id=$batchId processed=${current.processed.size}/${current.targets.size}"
            )
            return@withContext false
        }

        val summary = JSONObject()
            .put("batchId", current.batchId)
            .put("operation", current.operation)
            .put("startedAtEpochMillis", current.startedAtEpochMillis)
            .put("endedAtEpochMillis", System.currentTimeMillis())
            .put("outcome", outcome)
            .put("targetCount", current.targets.size)
            .put("processedCount", current.processed.size)
        if (current.isTimedHide) {
            summary
                .put("timedEndEpochMillis", current.timedEndEpochMillis)
                .put("timedLabel", current.timedLabel)
        }

        if (!archive(context, summary)) {
            Log.w(TAG, "batch journal close deferred because history archive failed id=$batchId")
            return@withContext false
        }

        val committed = getZeaPrefs(context).edit()
            .putString(KEY_ACTIVE, "")
            .commit()
        if (committed) {
            Log.i(
                TAG,
                "batch journal closed id=$batchId outcome=$outcome processed=${current.processed.size}/${current.targets.size}"
            )
        } else {
            Log.w(TAG, "batch journal history archived but active-slot clear failed id=$batchId")
        }
        committed
    }

    private fun archive(context: Context, entry: JSONObject): Boolean {
        return try {
            val prefs = getZeaPrefs(context)
            val existing = try {
                prefs.getString(KEY_HISTORY, "") ?: ""
            } catch (_: ClassCastException) {
                ""
            }
            val array = if (existing.isBlank()) {
                JSONArray()
            } else {
                try {
                    JSONArray(existing)
                } catch (_: Exception) {
                    JSONArray()
                }
            }
            val trimmed = JSONArray()
            val keepFrom = maxOf(0, array.length() - (HISTORY_LIMIT - 1))
            for (index in keepFrom until array.length()) {
                trimmed.put(array.get(index))
            }
            trimmed.put(entry)
            val committed = prefs.edit().putString(KEY_HISTORY, trimmed.toString()).commit()
            if (!committed) {
                Log.w(TAG, "batch journal history commit returned false")
            }
            committed
        } catch (error: Exception) {
            Log.w(TAG, "batch journal archive failed", error)
            false
        }
    }

    fun readHistory(context: Context): List<JSONObject> {
        val stored = try {
            getZeaPrefs(context).getString(KEY_HISTORY, "") ?: ""
        } catch (_: ClassCastException) {
            ""
        }
        if (stored.isBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readRawActive(context: Context): String {
        return try {
            getZeaPrefs(context).getString(KEY_ACTIVE, "") ?: ""
        } catch (_: ClassCastException) {
            ""
        }
    }

    private fun encode(record: ZeaBatchJournalRecord): JSONObject {
        val targets = JSONArray()
        record.targets.forEach { targets.put(it) }
        val processed = JSONArray()
        record.processed.forEach { processed.put(it) }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("batchId", record.batchId)
            .put("operation", record.operation)
            .put("startedAtEpochMillis", record.startedAtEpochMillis)
            .put("targets", targets)
            .put("processed", processed)
            .put("timedEndEpochMillis", record.timedEndEpochMillis)
            .put("timedLabel", record.timedLabel)
    }

    /**
     * Backward compatible with the v1 record that did not store timed metadata.
     * Old timed journals decode successfully but timedRequestOrNull() returns
     * null; recovery then safely releases/abandons rather than converting them
     * into permanent hides.
     */
    private fun decode(raw: String): ZeaBatchJournalRecord? {
        return try {
            val obj = JSONObject(raw)
            val batchId = obj.optString("batchId").trim()
            val operation = obj.optString("operation").trim().lowercase(Locale.ROOT)
            val targetsJson = obj.optJSONArray("targets") ?: JSONArray()
            val processedJson = obj.optJSONArray("processed") ?: JSONArray()
            val targets = (0 until targetsJson.length())
                .mapNotNull { index -> targetsJson.optString(index).trim().ifBlank { null } }
                .distinctBy { it.lowercase(Locale.ROOT) }
            val targetKeys = targets.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
            val processed = (0 until processedJson.length())
                .mapNotNull { index -> processedJson.optString(index).trim().ifBlank { null } }
                .filter { it.lowercase(Locale.ROOT) in targetKeys }
                .distinctBy { it.lowercase(Locale.ROOT) }
            if (batchId.isBlank() || operation !in supportedOperations || targets.isEmpty()) {
                null
            } else {
                ZeaBatchJournalRecord(
                    batchId = batchId,
                    operation = operation,
                    startedAtEpochMillis = obj.optLong("startedAtEpochMillis", 0L),
                    targets = targets,
                    processed = processed,
                    timedEndEpochMillis = obj.optLong("timedEndEpochMillis", 0L),
                    timedLabel = obj.optString("timedLabel", "").trim()
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
