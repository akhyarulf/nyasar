package com.nyasar.app.map

import android.content.Context
import android.view.ViewGroup
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
