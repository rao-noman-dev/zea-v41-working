package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ZeaUndoEntry(
    val operation: UndoOperation,
    val packageName: String,
    val displayName: String,
    val previousMode: ZeaHideMode,
    val timedEndEpochMillis: Long,
    val epochMillis: Long
)

enum class UndoOperation {
    HIDE,
    UNHIDE,
    TIMED_HIDE
}

/**
 * Phase 3 Safe Undo: a single operation snapshot, valid for 5 minutes, always
 * reversed through the same verified hide/unhide engines. Only offered when
 * the reversal is currently safe.
 */
object ZeaUndo {
    private const val KEY_ENTRY = "undo_entry_v1"
    const val WINDOW_MILLIS = 5L * 60L * 1000L

    suspend fun record(
        context: Context,
        packageName: String,
        displayName: String,
        operation: UndoOperation,
        previousMode: ZeaHideMode,
        timedEndEpochMillis: Long = 0L
    ) = withContext(Dispatchers.IO) {
        val entry = ZeaUndoEntry(
            operation = operation,
            packageName = packageName,
            displayName = displayName,
            previousMode = previousMode,
            timedEndEpochMillis = timedEndEpochMillis,
            epochMillis = System.currentTimeMillis()
        )
        getZeaPrefs(context.applicationContext).edit()
            .putString(KEY_ENTRY, encode(entry))
            .apply()
    }

    suspend fun load(context: Context): ZeaUndoEntry? = withContext(Dispatchers.IO) {
        val raw = getZeaPrefs(context.applicationContext)
            .getString(KEY_ENTRY, null) ?: return@withContext null
        decode(raw)
    }

    /** True when the snapshot is fresh and the reversal is still possible. */
    suspend fun canUndo(context: Context): Boolean {
        val entry = load(context) ?: return false
        if (System.currentTimeMillis() - entry.epochMillis > WINDOW_MILLIS) return false

        val currentMode = currentHideModeOf(context, entry.packageName)
        return when (entry.operation) {
            // A hide was applied; undo is only meaningful while the app is
            // actually hidden/timed now, otherwise a no-op unhide would lie.
            UndoOperation.HIDE ->
                currentMode == ZeaHideMode.HIDDEN || currentMode == ZeaHideMode.TIMED
            // An unhide was applied; undo re-hides, so the app must be
            // back to VISIBLE by now.
            UndoOperation.UNHIDE -> currentMode == ZeaHideMode.VISIBLE
            // A timed hide was applied; the timer must still be running.
            UndoOperation.TIMED_HIDE -> currentMode == ZeaHideMode.TIMED
        }
    }

    private suspend fun currentHideModeOf(
        context: Context,
        packageName: String
    ): ZeaHideMode = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        zeaManagedAppFromPackage(appContext, packageName)?.hideMode ?: ZeaHideMode.VISIBLE
    }

    suspend fun performUndo(context: Context): ZeaHideOutcome? {
        val entry = load(context) ?: return null
        if (!canUndo(context)) {
            clear(context)
            return ZeaHideOutcome(
                success = false,
                message = "Undo window expired. No reversal performed."
            )
        }

        val app = zeaManagedAppFromPackage(context, entry.packageName)
            ?: return ZeaHideOutcome(
                success = false,
                message = "The app is no longer installed; undo is not possible."
            )

        val outcome = when (entry.operation) {
            UndoOperation.HIDE -> ZeaAppHideService.unhideApp(context, entry.packageName)
            UndoOperation.UNHIDE -> ZeaAppHideService.hideApp(context, app)
            UndoOperation.TIMED_HIDE -> {
                // Undoing a timed hide means removing the timer entirely: the
                // app returns to the permanent state it had before the timer
                // was armed (or stays hidden if it was visible before).
                if (entry.previousMode == ZeaHideMode.VISIBLE) {
                    ZeaAppHideService.unhideApp(context, entry.packageName)
                } else {
                    ZeaAppHideService.convertTimedHideToPermanent(context, entry.packageName)
                }
            }
        }
        if (outcome.success) {
            clear(context)
        }
        return outcome
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        getZeaPrefs(context.applicationContext).edit().remove(KEY_ENTRY).apply()
    }

    private fun encode(entry: ZeaUndoEntry): String = JSONObject()
        .put("operation", entry.operation.name)
        .put("packageName", entry.packageName)
        .put("displayName", entry.displayName)
        .put("previousMode", entry.previousMode.name)
        .put("timedEnd", entry.timedEndEpochMillis)
        .put("epoch", entry.epochMillis)
        .toString()

    private fun decode(raw: String): ZeaUndoEntry? {
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        val operation = runCatching {
            UndoOperation.valueOf(obj.optString("operation"))
        }.getOrNull() ?: return null
        val previousMode = runCatching {
            ZeaHideMode.valueOf(obj.optString("previousMode"))
        }.getOrNull() ?: ZeaHideMode.VISIBLE
        return ZeaUndoEntry(
            operation = operation,
            packageName = obj.optString("packageName"),
            displayName = obj.optString("displayName"),
            previousMode = previousMode,
            timedEndEpochMillis = obj.optLong("timedEnd"),
            epochMillis = obj.optLong("epoch")
        )
    }
}
