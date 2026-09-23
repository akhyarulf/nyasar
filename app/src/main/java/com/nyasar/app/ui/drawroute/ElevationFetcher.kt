package com.nyasar.app.ui.drawroute

import android.util.Log
import com.nyasar.app.BuildConfig
import com.nyasar.app.gpx.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Elevation for DRAWN routes (the "kok rute gambar nggak punya profil
 * elevasi" gap): Open-Meteo's Elevation API returns Copernicus DEM 90 m
 * ground elevation for arbitrary coordinates — free, NO API key, no
 * account (non-commercial). Same boring-by-design contract as
 * [PathSnapper]: any failure — offline, timeout, server error, malformed
 * body — resolves to the input unchanged, so finishing a route NEVER
 * waits on or breaks because of it. Elevation is an enhancement.
 *
 * Why this placement works with zero pipeline changes: a TrackPoint with
 * a non-null elevationM flows through the EXISTING paths —
 * RouteRepository.writeDrawnRouteGpx writes <ele> into the saved GPX,
 * ElevationStats.summarize computes gain/loss/min/max for the route row,
 * and ElevationStats.toElevationProfile feeds the chart the preview
 * screen already renders. Fill elevations in before importFromDrawnPoints
 * and every downstream feature lights up untouched.
 *
 * API etiquette: one coordinate list per request (up to 100), chunked,
 * no retries. A route-save caller (1-2 requests typically) is exactly
 * the intended scale.
 */
object ElevationFetcher {

    private const val TAG = "ElevationFetcher"

    /** Copernicus DEM 90m via Open-Meteo — free non-commercial, no key. */
    private const val URL = "https://api.open-meteo.com/v1/elevation"

    /** API limit: up to 100 coordinates per call. */
    private const val CHUNK = 100

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @return [points] with elevationM filled where the API answered;
     * identical list (same object) when there is nothing to do or the
     * service is unreachable — the pre-feature behavior.
     */
    suspend fun withElevations(points: List<TrackPoint>): List<TrackPoint> =
        withContext(Dispatchers.IO) {
            if (points.size < 2) return@withContext points
            try {
                val elevations = DoubleArray(points.size) { Double.NaN }
                var i = 0
                while (i < points.size) {
                    val end = minOf(i + CHUNK, points.size)
                    val chunk = points.subList(i, end)
                    // Locale.US is load-bearing: the device locale (id-ID)
                    // formats decimals with a COMMA, which would corrupt the
                    // query string ("latitude=-7,9" → 400).
                    val lat = chunk.joinToString(",") {
                        String.format(java.util.Locale.US, "%.6f", it.lat)
                    }
                    val lon = chunk.joinToString(",") {
                        String.format(java.util.Locale.US, "%.6f", it.lon)
                    }
                    val request = Request.Builder()
                        .url("$URL?latitude=$lat&longitude=$lon")
                        .header("User-Agent", "Nyasar/${BuildConfig.VERSION_NAME} (draw-route elevation)")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.i(TAG, "open-meteo http ${resp.code}")
                            return@use
                        }
                        val body = resp.body?.string() ?: return@use
                        val arr = JSONObject(body).getJSONArray("elevation")
                        for (k in 0 until arr.length()) {
                            val idx = i + k
                            if (idx < points.size && !arr.isNull(k)) {
                                elevations[idx] = arr.getDouble(k)
                            }
                        }
                    }
                    i = end
                }
                val out = ArrayList<TrackPoint>(points.size)
                var anyFilled = false
                for ((idx, p) in points.withIndex()) {
                    val e = elevations[idx]
                    if (e.isNaN()) out.add(p) else { anyFilled = true; out.add(p.copy(elevationM = e)) }
                }
                if (anyFilled) out else points
            } catch (e: Exception) {
                Log.i(TAG, "elevation fetch failed: ${e.message}")
                points
            }
        }
}
