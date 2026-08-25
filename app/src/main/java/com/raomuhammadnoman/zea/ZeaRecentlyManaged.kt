package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ZeaRecentlyManagedEntry(
    val packageName: String,
    val displayName: String,
    val operation: String,
    val epochMillis: Long
)

/**
 * Phase 3 Recently Managed ring buffer: last 20 hide/unhide/timed operations.
 * Append-only; FIFO eviction. Cleared when history is cleared.
 */
object ZeaRecentlyManaged {
    private const val KEY_ENTRIES = "recently_managed_v1"
    const val MAX_ENTRIES = 20

    suspend fun record(
        context: Context,
        packageName: String,
        displayName: String,
        operation: String
    ) = withContext(Dispatchers.IO) {
        val prefs = getZeaPrefs(context.applicationContext)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: "[]"
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }

        val entry = JSONObject()
            .put("pkg", packageName)
            .put("label", displayName.take(120))
            .put("op", operation)
            .put("ts", System.currentTimeMillis())

        array.put(entry)
        while (array.length() > MAX_ENTRIES) {
            array.remove(0)
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    suspend fun load(context: Context): List<ZeaRecentlyManagedEntry> =
        withContext(Dispatchers.IO) {
            val raw = getZeaPrefs(context.applicationContext)
                .getString(KEY_ENTRIES, null) ?: return@withContext emptyList()
            val array = try {
                JSONArray(raw)
            } catch (_: Exception) {
                return@withContext emptyList()
            }
            val entries = mutableListOf<ZeaRecentlyManagedEntry>()
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                entries += ZeaRecentlyManagedEntry(
                    packageName = obj.optString("pkg"),
                    displayName = obj.optString("label"),
                    operation = obj.optString("op"),
                    epochMillis = obj.optLong("ts")
                )
            }
            entries
        }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        getZeaPrefs(context.applicationContext)
            .edit()
            .remove(KEY_ENTRIES)
            .apply()
    }
}
