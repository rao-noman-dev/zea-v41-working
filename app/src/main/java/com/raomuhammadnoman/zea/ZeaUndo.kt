package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ZeaUndoEntry(
    val operation: UndoOperation,
    val packageName: String,
    val displayName: String,
    val previousMode: ZeaHideMode,
    val timedEndEpochMillis: Long,
    val epochMillis: Long,
    /** State the operation actually produced, captured at apply time. Undo may
     *  reverse ONLY while the app still matches this exact state. */
    val appliedMode: ZeaHideMode = when (operation) {
        UndoOperation.HIDE -> ZeaHideMode.HIDDEN
        UndoOperation.UNHIDE -> ZeaHideMode.VISIBLE
        UndoOperation.TIMED_HIDE -> ZeaHideMode.TIMED
    },
    val appliedTimedEndEpochMillis: Long = 0L
)

enum class UndoOperation {
    HIDE,
    UNHIDE,
    TIMED_HIDE
}

/**
 * What a Safe Undo reversal must do, decided by pure logic so it is unit
 * testable without Android APIs.
 *
 * The critical invariant: a snapshot whose previous state was TIMED restores
 * the prior end time (re-arm), and never degrades into a plain permanent
 * hide; EXPIRED means the stored prior end is already past, so the reversal
 * falls back to the permanent/visible branch deliberately.
 */
enum class ZeaUndoPlan {
    UNHIDE,
    HIDE_PERMANENT,
    REARM_TIMER,
    CONVERT_TO_PERMANENT,
    EXPIRED
}

internal fun zeaUndoReversalPlan(
    entry: ZeaUndoEntry,
    nowEpochMillis: Long
): ZeaUndoPlan = when (entry.operation) {
    UndoOperation.HIDE -> ZeaUndoPlan.UNHIDE
    UndoOperation.UNHIDE -> {
        if (entry.previousMode == ZeaHideMode.TIMED) {
            if (entry.timedEndEpochMillis > nowEpochMillis) ZeaUndoPlan.REARM_TIMER
            else ZeaUndoPlan.EXPIRED
        } else ZeaUndoPlan.HIDE_PERMANENT
    }
    UndoOperation.TIMED_HIDE -> when (entry.previousMode) {
        ZeaHideMode.VISIBLE -> ZeaUndoPlan.UNHIDE
        ZeaHideMode.TIMED -> {
            if (entry.timedEndEpochMillis > nowEpochMillis) ZeaUndoPlan.REARM_TIMER
            else ZeaUndoPlan.CONVERT_TO_PERMANENT
        }
        ZeaHideMode.HIDDEN -> ZeaUndoPlan.CONVERT_TO_PERMANENT
    }
}

/**
 * Exact applied-state ownership check: an Undo entry may reverse ONLY the
 * precise state it produced. If another subsystem changed the mode (Hide ->
 * Timed) or re-armed a different timer end, the reversal is unsafe and must be
 * refused. Pure so the gate is covered by unit tests.
 */
internal fun zeaUndoIsSafe(
    entry: ZeaUndoEntry,
    currentMode: ZeaHideMode,
    currentTimedEndEpochMillis: Long,
    nowEpochMillis: Long
): Boolean {
    if (nowEpochMillis - entry.epochMillis > ZeaUndo.WINDOW_MILLIS) return false
    return when (entry.appliedMode) {
        ZeaHideMode.HIDDEN -> currentMode == ZeaHideMode.HIDDEN
        ZeaHideMode.VISIBLE -> currentMode == ZeaHideMode.VISIBLE
        ZeaHideMode.TIMED ->
            currentMode == ZeaHideMode.TIMED &&
                    currentTimedEndEpochMillis == entry.appliedTimedEndEpochMillis
    }
}

data class ZeaUndoBulkResult(
    val reversed: List<String>,
    val failed: List<Pair<String, String>>,
    /** Packages whose state changed independently after the original
     * operation; reversing them would destroy newer manual state. */
    val refused: List<String>
) {
    val allClean: Boolean get() = failed.isEmpty() && refused.isEmpty()
}

/**
 * Phase 3 Safe Undo: per-operation snapshots, valid for 5 minutes, always
 * reversed through the same verified hide/unhide engines. Only offered when
 * the reversal is currently safe.
 */
object ZeaUndo {
    private const val KEY_ENTRY = "undo_entry_v1"
    private const val KEY_BULK_ENTRIES = "undo_bulk_entries_v1"
    const val WINDOW_MILLIS = 5L * 60L * 1000L

    suspend fun record(
        context: Context,
        packageName: String,
        displayName: String,
        operation: UndoOperation,
        previousMode: ZeaHideMode,
        timedEndEpochMillis: Long = 0L,
        appliedTimedEndEpochMillis: Long = 0L
    ) = withContext(Dispatchers.IO) {
        val entry = ZeaUndoEntry(
            operation = operation,
            packageName = packageName,
            displayName = displayName,
            previousMode = previousMode,
            timedEndEpochMillis = timedEndEpochMillis,
            epochMillis = System.currentTimeMillis(),
            appliedTimedEndEpochMillis = appliedTimedEndEpochMillis
        )
        getZeaPrefs(context.applicationContext).edit()
            .putString(KEY_ENTRY, encode(entry))
            .apply()
    }

    /** Bulk snapshot: one entry per package, recorded together so a single
     *  [Undo] after a batch reverses every package independently. */
    suspend fun recordBulk(
        context: Context,
        entries: List<ZeaUndoEntry>
    ) = withContext(Dispatchers.IO) {
        getZeaPrefs(context.applicationContext).edit()
            .putString(KEY_BULK_ENTRIES, encodeBulk(entries))
            .apply()
    }

    suspend fun loadBulk(context: Context): List<ZeaUndoEntry> = withContext(Dispatchers.IO) {
        val raw = getZeaPrefs(context.applicationContext)
            .getString(KEY_BULK_ENTRIES, null) ?: return@withContext emptyList()
        decodeBulk(raw)
    }

    suspend fun load(context: Context): ZeaUndoEntry? = withContext(Dispatchers.IO) {
        val raw = getZeaPrefs(context.applicationContext)
            .getString(KEY_ENTRY, null) ?: return@withContext null
        decode(raw)
    }

    private suspend fun currentStateOf(
        context: Context,
        packageName: String
    ): Pair<ZeaHideMode, Long> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val app = zeaManagedAppFromPackage(appContext, packageName)
        (app?.hideMode ?: ZeaHideMode.VISIBLE) to (app?.hiddenUntilEpochMillis ?: 0L)
    }

    /** True when at least one bulk entry is fresh and still matches the exact
     *  state the batch produced. */
    suspend fun canUndoBulk(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return loadBulk(context).any { entry ->
            val (mode, timedEnd) = currentStateOf(context, entry.packageName)
            zeaUndoIsSafe(entry, mode, timedEnd, now)
        }
    }

    /** True when the snapshot is fresh and the app still matches the exact
     *  state this operation produced. */
    suspend fun canUndo(context: Context): Boolean {
        val entry = load(context) ?: return false
        val (mode, timedEnd) = currentStateOf(context, entry.packageName)
        return zeaUndoIsSafe(entry, mode, timedEnd, System.currentTimeMillis())
    }

    suspend fun performUndo(context: Context): ZeaHideOutcome? {
        val entry = load(context) ?: return null
        if (!canUndo(context)) {
            clear(context)
            return ZeaHideOutcome(
                success = false,
                message = "Undo window expired or the app state changed; no reversal performed."
            )
        }
        return executeReversal(context, entry)?.also { outcome ->
            if (outcome.success) clear(context)
        }
    }

    /**
     * Bulk undo: each package is verified against its own recorded applied
     * state and reversed independently. Unsafe packages are refused rather
     * than blindly overwritten; failures are reported honestly.
     */
    suspend fun performBulkUndo(context: Context): ZeaUndoBulkResult {
        val entries = loadBulk(context)
        if (entries.isEmpty()) {
            return ZeaUndoBulkResult(emptyList(), emptyList(), emptyList())
        }
        val now = System.currentTimeMillis()
        val reversed = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val refused = mutableListOf<String>()
        for (entry in entries) {
            val (mode, timedEnd) = currentStateOf(context, entry.packageName)
            if (!zeaUndoIsSafe(entry, mode, timedEnd, now)) {
                refused += entry.packageName
                continue
            }
            val outcome = executeReversal(context, entry)
            if (outcome == null) {
                refused += entry.packageName
            } else if (outcome.success) {
                reversed += entry.packageName
            } else {
                failed += entry.packageName to outcome.message
            }
        }
        clearBulk(context)
        return ZeaUndoBulkResult(reversed, failed, refused)
    }

    private suspend fun executeReversal(
        context: Context,
        entry: ZeaUndoEntry
    ): ZeaHideOutcome? {
        val app = zeaManagedAppFromPackage(context, entry.packageName)
            ?: return ZeaHideOutcome(
                success = false,
                message = "The app is no longer installed; undo is not possible."
            )
        val plan = zeaUndoReversalPlan(entry, System.currentTimeMillis())
        return when (plan) {
            ZeaUndoPlan.UNHIDE ->
                ZeaAppHideService.unhideApp(context, entry.packageName)
            ZeaUndoPlan.HIDE_PERMANENT ->
                ZeaAppHideService.hideApp(context, app)
            ZeaUndoPlan.REARM_TIMER ->
                // Restoring a TIMED previous state re-arms the original end
                // time; it never degrades into a plain permanent hide.
                ZeaAppHideService.hideAppForTime(
                    context,
                    app,
                    ZeaTimedHideRequest(
                        label = "undo restore",
                        endEpochMillis = entry.timedEndEpochMillis
                    )
                )
            ZeaUndoPlan.CONVERT_TO_PERMANENT ->
                ZeaAppHideService.convertTimedHideToPermanent(
                    context,
                    entry.packageName
                )
            ZeaUndoPlan.EXPIRED -> ZeaHideOutcome(
                success = false,
                message = "The recorded timer has already expired; undo is no longer meaningful."
            )
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        getZeaPrefs(context.applicationContext).edit().remove(KEY_ENTRY).apply()
    }

    suspend fun clearBulk(context: Context) = withContext(Dispatchers.IO) {
        getZeaPrefs(context.applicationContext).edit().remove(KEY_BULK_ENTRIES).apply()
    }

    private fun encode(entry: ZeaUndoEntry): String = JSONObject()
        .put("operation", entry.operation.name)
        .put("packageName", entry.packageName)
        .put("displayName", entry.displayName)
        .put("previousMode", entry.previousMode.name)
        .put("timedEnd", entry.timedEndEpochMillis)
        .put("epoch", entry.epochMillis)
        .put("appliedMode", entry.appliedMode.name)
        .put("appliedTimedEnd", entry.appliedTimedEndEpochMillis)
        .toString()

    private fun encodeBulk(entries: List<ZeaUndoEntry>): String {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject(encode(it))) }
        return array.toString()
    }

    private fun decode(raw: String): ZeaUndoEntry? {
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        return decodeObject(obj)
    }

    private fun decodeBulk(raw: String): List<ZeaUndoEntry> {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val entries = mutableListOf<ZeaUndoEntry>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            decodeObject(obj)?.let { entries += it }
        }
        return entries
    }

    private fun decodeObject(obj: JSONObject): ZeaUndoEntry? {
        val operation = runCatching {
            UndoOperation.valueOf(obj.optString("operation"))
        }.getOrNull() ?: return null
        val previousMode = runCatching {
            ZeaHideMode.valueOf(obj.optString("previousMode"))
        }.getOrNull() ?: ZeaHideMode.VISIBLE
        val appliedMode = runCatching {
            ZeaHideMode.valueOf(obj.optString("appliedMode"))
        }.getOrNull() ?: when (operation) {
            UndoOperation.HIDE -> ZeaHideMode.HIDDEN
            UndoOperation.UNHIDE -> ZeaHideMode.VISIBLE
            UndoOperation.TIMED_HIDE -> ZeaHideMode.TIMED
        }
        return ZeaUndoEntry(
            operation = operation,
            packageName = obj.optString("packageName"),
            displayName = obj.optString("displayName"),
            previousMode = previousMode,
            timedEndEpochMillis = obj.optLong("timedEnd"),
            epochMillis = obj.optLong("epoch"),
            appliedMode = appliedMode,
            appliedTimedEndEpochMillis = obj.optLong("appliedTimedEnd")
        )
    }
}
