package com.nyasar.app

import android.app.Application
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class NyasarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initializes MapLibre's global state once. No API key needed here —
        // provider credentials (e.g. MapTiler) live in the style URL, built
        // by TileProvider implementations, not in the SDK init call.
        MapLibre.getInstance(this)
        installTileUserAgent()
    }

    /**
     * OSMF tile-usage policy requires every app hitting OSM infrastructure
     * to identify itself with a meaningful User-Agent (app name + version +
     * contact), not an HTTP-client default.
     *
     * MapLibre 12.0.1 has no direct "setUserAgent" API: it stamps EVERY map
     * resource request (styles, tiles, glyphs, sprites — including offline
     * downloads) with its own User-Agent built from HttpIdentifier
     * ("com.nyasar.app/<version> (<code>)") inside HttpRequestImpl, and only
     * then hands the request to a single global OkHttp Call.Factory that
     * apps may replace via HttpRequestUtil.setOkHttpClient (documented to
     * survive across MapView instances). Installing a client with an
     * OVERWRITING User-Agent interceptor here therefore covers all
     * basemaps and overlays (OSM, OpenTopoMap, CyclOSM, OpenHikingMap,
     * MapTiler, Waymarked Trails, Liberty Satellite, offline regions) at
     * the HTTP-client level — zero per-basemap wiring.
     */
    private fun installTileUserAgent() {
        val userAgent =
            "Nyasar/${com.nyasar.app.BuildConfig.VERSION_NAME} (Android; +https://github.com/akhyarulf/nyasar)"
        val client = OkHttpClient.Builder()
            .apply {
                // Mirror HttpRequestImpl's own DEFAULT_CLIENT dispatcher
                // sizing (20 requests/host on Lollipop+) so parallel tile
                // loading keeps its original throughput under this custom
                // client instead of OkHttp's default 5/host.
                dispatcher = Dispatcher().apply { maxRequestsPerHost = 20 }
            }
            .addInterceptor { chain ->
                // Overwrite, not add: HttpRequestImpl has already stamped
                // its own User-Agent header on the request before handing
                // it to this client. Request.Builder.header() replaces the
                // existing value, so exactly one User-Agent goes on the
                // wire — ours, never the package-name default.
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                )
            }
            .build()
        HttpRequestUtil.setOkHttpClient(client)
    }
}
