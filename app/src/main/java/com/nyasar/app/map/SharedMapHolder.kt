package com.nyasar.app.map

import android.content.Context
import android.view.ViewGroup
import com.nyasar.app.gpx.model.GpxWaypoint
import org.maplibre.android.maps.MapView

/**
 * ONE MapView instance for the whole app process — shared by Home,
 * RoutePreview, and Recording (the 3 screens that each embed
 * NyasarMapView). Created once, on first use, and never destroyed until
 * the process dies.
 *
 * Why this exists: MapView.onCreate()/onDestroy() control the underlying
 * GL surface and tile cache. Each of the 3 screens used to create/destroy
 * its OWN MapView on every visit (a plain `remember(context) { MapView(context) }`
 * scoped to that one composable's lifetime) — meaning switching Home ->
 * RoutePreview -> Home re-loaded the style and re-fetched tiles from
 * scratch every single time. This holder exists so the instance itself
 * outlives any one screen; NyasarMapView.kt just borrows it.
 *
 * Deliberate tradeoff: onCreate()/onStart()/onResume() are called ONCE,
 * immediately, and NEVER paired with onPause()/onStop()/onDestroy() from
 * any screen — there is no single-Activity lifecycle boundary that's
 * correct to hook that up to when 3 different composables all borrow the
 * same instance. In exchange for zero-reload navigation between the 3 map
 * screens, the GL context + tile cache stay resident in memory for the
 * whole app session (not actively rendering/costing battery while
 * detached from any visible screen — Android's View system doesn't call
 * draw() on a detached View — just not released early). If this turns out
 * to be a real memory problem on low-end devices, the fix is a proper
 * ProcessLifecycleOwner-driven onPause/onResume pairing, not per-screen
 * disposal — flagged here for a future pass, not solved in this one.
 */
object SharedMapHolder {

    @Volatile
    private var instance: MapView? = null

    /**
     * Identity of the style currently loaded on the shared instance, or null
     * while a load is in flight / never completed. NyasarMapView computes a
     * key from (providerId, basemapId-or-styleVariant) before every style
     * effect; when the key already matches what's on screen, setStyle() is
     * SKIPPED entirely and only the content sources (track/waypoints/etc.)
     * are re-applied. That's the actual fix for "map reloads (style + tiles)
     * on every screen switch": the GL surface, tile cache, AND loaded style
     * all survive the navigation; only cheap GeoJSON source updates run.
     */
    @Volatile
    private var loadedStyleKey: String? = null

    fun isStyleLoaded(key: String): Boolean = loadedStyleKey == key

    /** Called right before setStyle() so a failed/interrupted load can never
     *  be mistaken for a loaded one (the next screen then reloads instead of
     *  rendering the previous style under a wrong assumption). */
    fun markStyleLoading() {
        loadedStyleKey = null
    }

    /** Called from inside setStyle()'s onStyleLoaded callback. */
    fun markStyleLoaded(key: String) {
        loadedStyleKey = key
    }

    /** Stable identity for a style choice: provider + basemap id (or the
     *  legacy StyleVariant when no 9-basemap entry is selected). Same inputs
     *  always produce the same style JSON — the inline raster styles are
     *  deterministic functions of the entry — so key equality is a fair
     *  proxy for "the style currently on screen is the one this screen wants". */
    fun styleKey(providerId: String, basemapEntry: BasemapEntry?, styleVariant: StyleVariant): String =
        "$providerId:${basemapEntry?.gpxKey ?: styleVariant.name}"

    /**
     * Per-screen input handlers for the shared instance, swapped on every
     * recomposition of whichever screen currently hosts it (NyasarMapView
     * writes them from its own rememberUpdatedState delegates).
     *
     * Why this indirection exists: MapLibre's addOnMapClickListener /
     * addOnCameraMoveListener family is ADDITIVE with no remove counterpart.
     * A shared map is adopted by a new AndroidView on every screen switch,
     * so registering fresh listeners per screen would stack N screens' worth
     * of handlers (taps firing 3 times after visiting 3 screens). Instead the
     * physical listeners are registered exactly ONCE (first-ever host) and
     * forward into these slots, which always point at the current host's
     * handlers.
     */
    data class TapHandlers(
        val onMapClick: (lat: Double, lon: Double) -> Unit = { _, _ -> },
        val onMapLongPress: (lat: Double, lon: Double) -> Unit = { _, _ -> },
        val onWaypointClick: (GpxWaypoint) -> Unit = {},
        val onUserWaypointClick: (String) -> Unit = {},
        val onUserGesture: () -> Unit = {},
        val onBearingChanged: (Float) -> Unit = {}
    )

    @Volatile
    var tapHandlers: TapHandlers = TapHandlers()

    // Two SEPARATE one-shot flags: tap listeners are installed from the
    // AndroidView factory, camera listeners from the style-load path — two
    // independent async entry points with no defined ordering. Sharing one
    // flag would let whichever ran first mark BOTH groups as installed and
    // silently drop the other group (e.g. camera listeners never registered
    // because the factory ran first).
    @Volatile
    private var tapListenersInstalled = false

    @Volatile
    private var cameraListenersInstalled = false

    /** True while the one-time physical tap listeners still need to be
     *  registered on the shared instance. */
    fun needsTapListenerInstall(): Boolean = synchronized(this) { !tapListenersInstalled }

    /** Called right after registering the physical tap listeners. */
    fun markTapListenersInstalled() {
        synchronized(this) { tapListenersInstalled = true }
    }

    /** True while the one-time physical camera listeners still need to be
     *  registered on the shared instance. */
    fun needsCameraListenerInstall(): Boolean = synchronized(this) { !cameraListenersInstalled }

    /** Called right after registering the physical camera listeners. */
    fun markCameraListenersInstalled() {
        synchronized(this) { cameraListenersInstalled = true }
    }

    fun get(context: Context): MapView {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = MapView(context.applicationContext)
            created.onCreate(null)
            created.onStart()
            created.onResume()
            instance = created
            return created
        }
    }

    /** Detach the shared MapView from whatever View currently parents it
     *  (the screen the user is navigating away from), so the screen being
     *  navigated TO can attach it as its own child without Android's
     *  "already has a parent" crash. Safe to call even if it has no
     *  parent yet (first-ever use). */
    fun detachFromCurrentParent(mapView: MapView) {
        (mapView.parent as? ViewGroup)?.removeView(mapView)
    }
}

