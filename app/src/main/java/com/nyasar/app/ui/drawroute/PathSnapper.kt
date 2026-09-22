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
 * Snap-to-path for the draw-route screen ("ikuti jalur"): asks a public
 * OSRM server (FOSSGIS, the same infrastructure openstreetmap.org routes
 * with — foot profile, so highway=path/trail steps AND ordinary roads are
 * both followed) to replace each straight tap-to-tap segment with the real
 * geometry of the trail/road between the two anchors.
 *
 * BORING BY DESIGN, like UpdateChecker: any failure — no network, timeout,
 * server error, unroutable pair (trail not mapped in OSM), malformed
 * body — resolves to null and the caller silently falls back to the
 * straight line it would have drawn anyway. Snapping is an enhancement,
 * never a hard dependency: offline drawing must keep working.
 *
 * Anchors vs path: the user's taps stay the source of truth (the ViewModel
 * keeps them as the undo/history model); only the RENDERED + SAVED
 * geometry gets densified by the returned points. If OSRM ever answers
 * with a detour that ignores a tap (can happen with a too-coarse road
 * network), the anchors still bound the shape, so undo behaves the same.
 *
 * Server etiquette: routing.data.osm.ch is a free community service — one
 * request per added point, short timeouts, and no retry storm. A tap-rate
 * caller is exactly the intended scale of "small app" usage.
 */
object PathSnapper {

    private const val TAG = "PathSnapper"

    /** FOSSGIS demo server, foot profile. Coordinates are lon,lat (OSRM order). */
    private const val OSRM_URL =
        "https://routing.openstreetmap.de/routed-foot/route/v1/foot/"

    /** Walk speed sanity bound: a segment OSRM claims is farther than a
     *  human could walk while the straight line is this long (5x) means it
     *  routed through some pathological detour — straight line is safer. */
    private const val MAX_PATH_VS_STRAIGHT_RATIO = 5.0

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @return the real path between [from] and [to] following mapped
     * ways (including both endpoints, in order), or null when snapping is
     * unavailable/worse than a straight line — in which case the caller
     * draws the straight segment exactly as before.
     */
    suspend fun snap(from: TrackPoint, to: TrackPoint): List<TrackPoint>? =
        withContext(Dispatchers.IO) {
            try {
                val url = OSRM_URL +
                    "${from.lon},${from.lat};" +
                    "${to.lon},${to.lat}" +
                    "?overview=full&geometries=geojson&annotations=false"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Nyasar/${BuildConfig.VERSION_NAME} (draw-route snap)")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.i(TAG, "osrm http ${resp.code}")
                        return@use null
                    }
                    val body = resp.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    // "Ok" requires at least one route; the first is the best.
                    if (json.optString("code") != "Ok") {
                        Log.i(TAG, "osrm code ${json.optString("code")}")
                        return@use null
                    }
                    val routes = json.optJSONArray("routes") ?: return@use null
                    val route = routes.optJSONObject(0) ?: return@use null
                    val coords = route.optJSONObject("geometry")
                        ?.optJSONArray("coordinates") ?: return@use null
                    if (coords.length() < 2) return@use null

                    // OSRM routes the pair through the road graph; if the
                    // found way is wildly longer than the straight line the
                    // tap pair is probably not walk-connected (island rule,
                    // unmapped gate) — straight line is the honest answer.
                    val straight = straightDistance(from, to)
                    val routed = route.optDouble("distance", Double.MAX_VALUE)
                    if (routed > straight * MAX_PATH_VS_STRAIGHT_RATIO) {
                        Log.i(TAG, "osrm detour ${routed}m vs straight ${straight}m")
                        return@use null
                    }

                    val points = ArrayList<TrackPoint>(coords.length())
                    for (i in 0 until coords.length()) {
                        val c = coords.optJSONArray(i) ?: continue
                        val lon = c.optDouble(0)
                        val lat = c.optDouble(1)
                        if (lon.isNaN() || lat.isNaN()) continue
                        points.add(TrackPoint(lat = lat, lon = lon, elevationM = null, timestampEpochMs = null))
                    }
                    if (points.size < 2) null else points
                }
            } catch (e: Exception) {
                // Offline, DNS failure, malformed JSON… all the same: no snap.
                Log.i(TAG, "snap unavailable: ${e.message}")
                null
            }
        }

    private fun straightDistance(a: TrackPoint, b: TrackPoint): Double =
        com.nyasar.app.navigation.GeoMath.distanceMeters(
            com.nyasar.app.navigation.LatLng(a.lat, a.lon),
            com.nyasar.app.navigation.LatLng(b.lat, b.lon)
        )
}
