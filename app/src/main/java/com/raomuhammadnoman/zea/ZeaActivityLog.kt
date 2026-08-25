package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Structured catalog of event types recorded in the privacy preserving log. */
enum class ZeaActivityEventType(val label: String) {
    HIDE("Hide"),
    UNHIDE("Unhide"),
    TIMED_HIDE("Timed hide"),
    TIMER_EXPIRY("Timer expiry"),
    GROUP_ACTION("Group action"),
    PROFILE_ACTIVATED("Profile activated"),
    PROFILE_DEACTIVATED("Profile deactivated"),
    SCHEDULE_FIRED("Schedule execution"),
    RECOVERY("Recovery action"),
    PROTECTION_FAILURE("Protection failure"),
    PERMISSION_ISSUE("Permission issue"),
    LOCKOUT("PIN lockout"),
    BATCH_COMPLETED("Batch completed")
}

/** Outcome of the logged operation; failures must be distinguishable. */
enum class ZeaActivityResult(val label: String) {
    SUCCESS("Success"),
    FAILURE("Failure"),
    PARTIAL("Partial")
}

data class ZeaActivityEntry(
    val epochMillis: Long,
    val type: ZeaActivityEventType,
    val subject: String,
    val detail: String,
    val result: ZeaActivityResult
)

/**
 * Phase 3 privacy-preserving activity/security history.
 *
 * Stores structured metadata only (event type, subject name, short detail,
 * outcome). No PINs, no package payloads, no user-supplied text beyond labels
 * the feature itself generates. Bounded ring: oldest entries are dropped when
 * the cap is exceeded. Local only — never leaves the device.
 */
object ZeaActivityLog {
    private const val KEY_ENTRIES = "activity_log_entries_v1"
    const val MAX_ENTRIES = 500

    suspend fun record(
        context: Context,
        type: ZeaActivityEventType,
        subject: String,
        detail: String,
        result: ZeaActivityResult = ZeaActivityResult.SUCCESS
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = getZeaPrefs(appContext)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: "[]"
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }

        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("type", type.name)
            .put("subject", subject.take(160))
            .put("detail", detail.take(240))
            .put("result", result.name)

        array.put(entry)

        // Drop the oldest entries once the bounded cap is exceeded.
        while (array.length() > MAX_ENTRIES) {
            array.remove(0)
        }

        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    suspend fun read(context: Context): List<ZeaActivityEntry> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val raw = getZeaPrefs(appContext).getString(KEY_ENTRIES, null) ?: return@withContext emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        val entries = mutableListOf<ZeaActivityEntry>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val type = runCatching {
                ZeaActivityEventType.valueOf(obj.optString("type"))
            }.getOrNull() ?: continue
            val result = runCatching {
                ZeaActivityResult.valueOf(obj.optString("result"))
            }.getOrNull() ?: ZeaActivityResult.SUCCESS
            entries += ZeaActivityEntry(
                epochMillis = obj.optLong("ts"),
                type = type,
                subject = obj.optString("subject"),
                detail = obj.optString("detail"),
                result = result
            )
        }
        entries
    }

    suspend fun recentSubjects(context: Context, limit: Int = 20): List<String> =
        read(context)
            .sortedByDescending { it.epochMillis }
            .map { it.subject }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        getZeaPrefs(context.applicationContext)
            .edit()
            .remove(KEY_ENTRIES)
            .apply()
    }
}
