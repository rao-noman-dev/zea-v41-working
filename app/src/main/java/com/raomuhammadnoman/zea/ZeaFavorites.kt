package com.raomuhammadnoman.zea

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Phase 3 Favorites / Pinned Apps. Stores package ids only, ordered by the
 * user's own pinning order. Never affects protection state by itself.
 */
object ZeaFavorites {
    private const val KEY_FAVORITES = "app_favorites_v1"
    const val MAX_FAVORITES = 12

    private val prefsListeners = mutableListOf<(List<String>) -> Unit>()

    suspend fun load(context: Context): List<String> = withContext(Dispatchers.IO) {
        val raw = getZeaPrefs(context.applicationContext)
            .getString(KEY_FAVORITES, null) ?: return@withContext emptyList()
        decode(raw)
    }

    private suspend fun save(context: Context, favorites: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            val committed = getZeaPrefs(context.applicationContext)
                .edit()
                .putString(KEY_FAVORITES, encode(favorites))
                .commit()
            if (committed) {
                prefsListeners.toList().forEach { it(favorites) }
            }
            committed
        }

    suspend fun isFavorite(context: Context, packageName: String): Boolean =
        load(context).contains(packageName)

    suspend fun addFavorite(context: Context, packageName: String): Boolean {
        val favorites = load(context)
        if (favorites.contains(packageName)) return true
        if (favorites.size >= MAX_FAVORITES) return false
        return save(context, favorites + packageName)
    }

    suspend fun removeFavorite(context: Context, packageName: String): Boolean {
        val favorites = load(context)
        if (!favorites.contains(packageName)) return true
        return save(context, favorites - packageName)
    }

    suspend fun toggleFavorite(context: Context, packageName: String): Boolean {
        return if (isFavorite(context, packageName)) {
            removeFavorite(context, packageName)
        } else {
            addFavorite(context, packageName)
        }
    }

    /** Drops favorites whose package is no longer installed. */
    suspend fun pruneUninstalled(
        context: Context,
        installedPackages: Set<String>
    ): Boolean {
        val favorites = load(context)
        val retained = favorites.filter { it in installedPackages }
        return if (retained.size != favorites.size) {
            save(context, retained)
        } else {
            true
        }
    }

    internal fun addListener(listener: (List<String>) -> Unit) {
        prefsListeners += listener
    }

    internal fun removeListener(listener: (List<String>) -> Unit) {
        prefsListeners -= listener
    }

    private fun encode(favorites: List<String>): String =
        JSONArray(favorites).toString()

    private fun decode(raw: String): List<String> {
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val favorites = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index, "")
            if (value.isNotBlank()) favorites += value
        }
        return favorites
    }
}
