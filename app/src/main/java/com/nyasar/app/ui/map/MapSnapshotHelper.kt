package com.nyasar.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generates static map-style background bitmaps for activity track thumbnails
 * and share card backgrounds.
 *
 * Since MapLibre Android SDK 11.5.x does not include MapSnapshotter (that's
 * an iOS-only API), this helper renders a gradient+grid pattern on Android
 * Canvas as a lightweight map placeholder. Snapshots are cached to disk so
 * they are only generated once per activity and reused across scrolls.
 *
 * If MapSnapshotter becomes available in a future SDK version, this helper
 * can be upgraded to render real tile snapshots behind the route line.
 */
object MapSnapshotHelper {

    private const val CACHE_DIR = "map_snapshots"
    private val TOP_COLOR = Color.parseColor("#5A7562")
    private val BOT_COLOR = Color.parseColor("#2A3A30")
    private val GRID_COLOR = Color.parseColor("#1AFFFFFF")

    /**
     * Get a cached snapshot or generate a new one.
     * @param trackPoints lat/lon pairs from the activity's recorded points
     * @param widthPx pixel width of the output bitmap
     * @param heightPx pixel height of the output bitmap
     * @return Bitmap or null on failure
     */
    suspend fun getOrGenerate(
        context: Context,
        activityId: String,
        trackPoints: List<Pair<Double, Double>>,
        widthPx: Int,
        heightPx: Int,
        styleUrl: String = "" // unused, reserved for future MapSnapshotter integration
    ): Bitmap? {
        if (trackPoints.size < 2) return null

        val cached = loadFromDisk(context, activityId)
        if (cached != null) return cached

        val bitmap = withContext(Dispatchers.IO) {
            generateGradientGridBitmap(widthPx, heightPx)
        }

        if (bitmap != null) {
            saveToDisk(context, activityId, bitmap)
        }

        return bitmap
    }

    /**
     * Generate synchronously — called from IO/Default dispatcher.
     */
    suspend fun generateSync(
        context: Context,
        activityId: String,
        trackPoints: List<Pair<Double, Double>>,
        widthPx: Int,
        heightPx: Int,
        styleUrl: String = ""
    ): Bitmap? {
        if (trackPoints.size < 2) return null

        val cached = loadFromDisk(context, activityId)
        if (cached != null) return cached

        val bitmap = generateGradientGridBitmap(widthPx, heightPx)
        if (bitmap != null) {
            saveToDisk(context, activityId, bitmap)
        }
        return bitmap
    }

    /**
     * Render a gradient background with subtle grid lines — lightweight
     * map-style placeholder that suggests terrain without live tiles.
     */
    private fun generateGradientGridBitmap(widthPx: Int, heightPx: Int): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            // Gradient fill
            val gradient = LinearGradient(
                0f, 0f, 0f, heightPx.toFloat(),
                intArrayOf(TOP_COLOR, BOT_COLOR),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawPaint(Paint().apply { shader = gradient })

            // Subtle grid lines
            val gridPaint = Paint().apply {
                color = GRID_COLOR
                strokeWidth = 1f
            }
            val spacing = (widthPx / 18).toFloat().coerceAtLeast(30f)

            var x = 0f
            while (x <= widthPx) {
                c.drawLine(x, 0f, x, heightPx.toFloat(), gridPaint)
                x += spacing
            }
            var y = 0f
            while (y <= heightPx) {
                c.drawLine(0f, y, widthPx.toFloat(), y, gridPaint)
                y += spacing
            }

            bmp
        } catch (e: Exception) {
            null
        }
    }

    // ── Disk cache ──

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cacheFile(context: Context, activityId: String): File =
        File(cacheDir(context), "$activityId.png")

    private fun loadFromDisk(context: Context, activityId: String): Bitmap? {
        val file = cacheFile(context, activityId)
        return if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                file.delete()
                null
            }
        } else null
    }

    private fun saveToDisk(context: Context, activityId: String, bitmap: Bitmap) {
        try {
            val file = cacheFile(context, activityId)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            android.util.Log.w("MapSnapshotHelper", "Cache write failed: ${e.message}")
        }
    }
}
