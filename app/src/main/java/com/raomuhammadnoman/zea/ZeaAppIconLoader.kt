package com.raomuhammadnoman.zea

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Loads launcher icons without pulling in an image library.
 *
 * Hidden packages still need an icon, so lookups use MATCH_UNINSTALLED_PACKAGES;
 * a hidden app is invisible to a normal query but its resources remain readable.
 */
object ZeaAppIconLoader {
    private const val MAX_ICON_EDGE_PX = 144
    private const val MAX_CACHED_ICONS = 240

    private val cacheLock = Any()
    private val cache = HashMap<String, ImageBitmap>()

    fun cached(packageName: String): ImageBitmap? {
        return synchronized(cacheLock) {
            cache[packageName]
        }
    }

    fun load(context: Context, packageName: String): ImageBitmap? {
        cached(packageName)?.let { return it }

        val icon = readLauncherIcon(context.applicationContext, packageName)
            ?.let(::toImageBitmap)
            ?: return null

        synchronized(cacheLock) {
            if (cache.size >= MAX_CACHED_ICONS) {
                cache.clear()
            }
            cache[packageName] = icon
        }

        return icon
    }

    fun clear() {
        synchronized(cacheLock) {
            cache.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun readLauncherIcon(
        context: Context,
        packageName: String
    ): Drawable? {
        val packageManager = context.packageManager

        return try {
            val applicationInfo = packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
            packageManager.getApplicationIcon(applicationInfo)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun toImageBitmap(drawable: Drawable): ImageBitmap? {
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap.asImageBitmap()
            }

            val width = resolveEdge(drawable.intrinsicWidth)
            val height = resolveEdge(drawable.intrinsicHeight)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            bitmap.asImageBitmap()
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun resolveEdge(intrinsicSize: Int): Int {
        if (intrinsicSize <= 0) {
            return MAX_ICON_EDGE_PX
        }

        return intrinsicSize.coerceAtMost(MAX_ICON_EDGE_PX)
    }
}
