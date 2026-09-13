package com.nyasar.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.nyasar.app.gpx.model.GpxWaypoint
import com.nyasar.app.gpx.model.TrackPoint
import com.nyasar.app.map.SharedMapHolder
import com.nyasar.app.map.StyleVariant
import com.nyasar.app.map.TileProvider
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SOURCE_TRACK = "nyasar-track-source"
private const val LAYER_TRACK = "nyasar-track-layer"
private const val SOURCE_ACTUAL_TRACK = "nyasar-actual-track-source"
private const val LAYER_ACTUAL_TRACK = "nyasar-actual-track-layer"
private const val SOURCE_DRAWN_POINTS = "nyasar-drawn-points-source"
private const val LAYER_DRAWN_POINTS = "nyasar-drawn-points-layer"
private const val SOURCE_WAYPOINTS = "nyasar-waypoints-source"
private const val LAYER_WAYPOINTS = "nyasar-waypoints-layer"
private const val SOURCE_USER_WAYPOINTS = "nyasar-user-waypoints-source"
private const val LAYER_USER_WAYPOINTS = "nyasar-user-waypoints-layer"
// Pins and labels are SEPARATE symbol layers (see addWaypointLabelLayer):
// a symbol layer carrying any text property requires the style's "glyphs"
// endpoint, and a glyph-less style rejects the layer WHOLE — icon
// included. Icon-only layers keep pins glyph-free; labels degrade
// gracefully (hidden) on glyph-less styles instead of taking pins down.
private const val LAYER_LABEL_WAYPOINTS = "nyasar-waypoints-label-layer"
private const val LAYER_LABEL_USER_WAYPOINTS = "nyasar-user-waypoints-label-layer"
private const val SOURCE_USER = "nyasar-user-source"
private const val SOURCE_ACCURACY = "nyasar-accuracy-source"
private const val LAYER_ACCURACY_FILL = "nyasar-accuracy-fill-layer"
private const val LAYER_USER_HALO = "nyasar-user-halo-layer"
private const val LAYER_USER_DOT = "nyasar-user-dot-layer"
private const val LAYER_USER_HEADING = "nyasar-user-heading-layer"
private const val SOURCE_OFFLINE_COVERAGE = "nyasar-offline-coverage-source"
private const val SOURCE_OFFLINE_AREAS = "nyasar-offline-areas-source"
private const val LAYER_OFFLINE_COVERAGE_FILL = "nyasar-offline-coverage-fill-layer"
private const val LAYER_OFFLINE_COVERAGE_OUTLINE = "nyasar-offline-coverage-outline-layer"
private const val LAYER_OFFLINE_AREAS_FILL = "nyasar-offline-areas-fill-layer"
private const val LAYER_OFFLINE_AREAS_OUTLINE = "nyasar-offline-areas-outline-layer"
private const val PROP_WP_NAME = "name"
private const val PROP_WP_ELEVATION = "elevationM"
private const val PROP_WP_DESCRIPTION = "description"
private const val PROP_WP_LAT = "lat"
private const val PROP_WP_LON = "lon"
private const val PROP_UWP_ID = "id"
private const val PROP_UWP_CATEGORY = "category"
private const val SOURCE_HIGHLIGHT = "nyasar-highlight-source"
private const val LAYER_HIGHLIGHT_CIRCLE = "nyasar-highlight-circle-layer"
private const val LAYER_HIGHLIGHT_OUTLINE = "nyasar-highlight-outline-layer"
// "Jalur Saya" overlay (MyRoutesOverlay) — all saved Library routes. One
// GeoJSON source, two layers: gray/dashed for every route, solid accent for
// the active one (activeRouteId). Filters — not separate sources — express
// the split, so toggling the active route never re-uploads the geometry.
private const val SOURCE_MY_ROUTES = "nyasar-my-routes-source"
private const val LAYER_MY_ROUTES = "nyasar-my-routes-layer"
private const val LAYER_MY_ROUTES_ACTIVE = "nyasar-my-routes-active-layer"

/**
 * The map is the center of the app (spec section 21/6) — this composable
 * is the single place track lines, waypoints, and the user marker are
 * drawn, on top of whatever style URL the active [TileProvider] returns.
 * Swapping providers only changes the argument passed here.
 */
@Composable
fun NyasarMapView(
    modifier: Modifier = Modifier,
    provider: TileProvider,
    /** Standard/Satellite/Terrain (spec P3 §11). Resolved through the same
     *  [TileProvider] abstraction — no provider-specific branching here.
     *  Superseded by [basemapEntry] when that's non-null; kept as the
     *  fallback for every screen that doesn't offer the 9-basemap picker
     *  (DrawRoute, ActivityDetail, Navigation, offline map screens,
     *  WaypointCrosshair — none of those were touched here). */
    styleVariant: StyleVariant = StyleVariant.OUTDOOR,
    /** One of the 9 World basemaps (BasemapCatalog) — takes priority over
     *  [styleVariant] when supplied. Resolved via
     *  [TileProvider.styleUrlFor], which already knows how to build every
     *  entry's style (hosted vector style URL, inline raster style, or the
     *  Liberty Satellite composite) — this composable doesn't need to know
     *  which. Null (the default) preserves every existing call site's
     *  behavior exactly as before this param was added. */
    basemapEntry: com.nyasar.app.map.BasemapEntry? = null,
    /** Waymarked Trails overlay layers (Hiking/Cycling/MTB), on top of
     *  whichever basemap is active. Deliberately kept OUT of the giant
     *  style-setup LaunchedEffect below (which fully reloads the style
     *  via setStyle on every key change) — toggling an overlay checkbox
     *  must not reset/re-fetch the entire basemap. Applied in its own
     *  effect further down instead, using getStyle() to add/remove just
     *  these sources/layers on the currently-loaded style. */
    activeOverlays: Set<com.nyasar.app.map.OverlayLayer> = emptySet(),
    /** "Jalur Saya" overlay — every saved Library route drawn as a line,
     *  with the route currently picked for recording/navigation
     *  ([activeRouteId]) accented. Data comes from
     *  RouteRepository.observeOverlayLines (parsed from the same local GPX
     *  files the Library already owns — no new storage), already decimated
     *  to a bounded vertex count. Empty list (the default) leaves the map
     *  untouched, so every existing call site keeps its exact behavior. */
    myRoutes: List<com.nyasar.app.map.MyRouteLine> = emptyList(),
    /** Route id active for recording/navigation ("Pilih Jalur" flow) —
     *  rendered solid accent, all other [myRoutes] gray/dashed. Null: no
     *  route is active, every line renders inactive. */
    activeRouteId: String? = null,
    track: List<TrackPoint>,
    /** Explicit color for the [track] line. Null (default) = legacy
     *  heuristic — green when [actualTrack] is empty ("this list is the
     *  walked path", ActivityDetail/Navigation legend semantics), blue
     *  otherwise. RoutePreview passes the app blue: its track is ALWAYS
     *  the planned route, and that is the color the screen has always
     *  shown (the shared fast path it migrated from hardcoded blue).
     *  Without this, migrating RoutePreview onto the full-load pipeline
     *  would silently recolor every route green. */
    trackColorOverride: String? = null,
    /** The track actually walked so far (recording), drawn as a second line in
     *  a different color from [track] (the planned route). Updates on every
     *  GPS fix — kept in its own source/effect so it never touches the
     *  planned-route source or re-runs the (expensive) style setup below. */
    actualTrack: List<TrackPoint> = emptyList(),
    /** Draw-route feature: points the user has tapped so far, building a
     *  route by hand before it's saved. Same wiring pattern as
     *  [actualTrack] on purpose — updates on every tap and must NOT
     *  re-trigger the style-setup effect below (that effect re-fits the
     *  camera to bounds on every run, which would zoom/jump the map after
     *  every single tap — exactly the kind of camera feedback loop
     *  already fixed elsewhere in this file for a different screen). Its
     *  own distinct color/dash rather than reusing actualTrack's green
     *  (which means "actually walked" everywhere else in the app) or
     *  track's route color (a drawn-but-unsaved line isn't a confirmed
     *  route yet either). */
    drawnPoints: List<TrackPoint> = emptyList(),
    waypoints: List<GpxWaypoint> = emptyList(),
    /** Master visibility for ALL waypoint pins (the GPX [waypoints] above
     *  AND [userWaypoints] below) — the "Waypoint" toggle in the picker
     *  sheet's Data section, persisted app-wide. When false the pins are
     *  dropped at the data level (empty feature lists), so both the shared
     *  fast path and the full-load path simply render nothing — no extra
     *  visibility machinery on the layers themselves. Default TRUE so
     *  call sites that don't opt into the toggle (DrawRoute, Offline
     *  screens, WaypointCrosshair) keep showing pins exactly as before. */
    waypointsVisible: Boolean = true,
    /** User-created waypoints (spec P3E2) — rendered as a distinct layer
     *  from [waypoints] (GPX-parsed, read-only) so this feature never
     *  shares rendering/tap-hit state with the existing route-waypoint
     *  path. Each entry is (id, lat, lon, category storage value, label). */
    userWaypoints: List<com.nyasar.app.data.db.WaypointEntity> = emptyList(),
    onUserWaypointClick: (String) -> Unit = {},
    /** Long-press to drop a new waypoint (spec P3E2: "Tap map → Add
     *  Waypoint"). Separate listener from [onWaypointClick]/the short-tap
     *  detail lookup above — MapLibre supports both callbacks
     *  independently, so a normal tap on empty map still does nothing
     *  (as before) and only a long-press starts the add flow. */
    onMapLongPress: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    userLocation: LatLng? = null,
    /** Meters — drawn as a real geographic circle around the user (spec P3A
     *  §GPS UX: "accuracy circle jika tersedia"), not just a fixed-size
     *  pixel halo, so it actually shrinks/grows with real GPS accuracy and
     *  scales correctly as the map zooms. Null omits the circle. */
    accuracyMeters: Float? = null,
    /** Degrees clockwise from true north. Null = no heading available; the
     *  small direction arrow is simply omitted, the dot still shows. */
    userHeadingDeg: Float? = null,
    followUser: Boolean = false,
    /** When true AND followUser is true, the camera rotates so "up" on
     *  screen matches the user's GPS heading (classic turn-by-turn feel).
     *  When false, the camera stays north-up regardless of heading — the
     *  user marker itself still rotates (see the heading arrow layer
     *  below), only the map frame doesn't. Independent of followUser: a
     *  user can be north-up AND following position, or heading-up AND not
     *  following (rotates in place without recentering). */
    rotateWithHeading: Boolean = false,
    focusBounds: org.maplibre.android.geometry.LatLngBounds? = null,
    /** Downloaded offline-map areas, drawn as translucent rectangles (spec
     *  §24, WAJIB — "user harus bisa melihat bagian map mana yang sudah
     *  didownload"). Empty list omits the layer entirely. */
    offlineCoverage: List<org.maplibre.android.geometry.LatLngBounds> = emptyList(),
    /** Offline coverage with completion state for the live overlay — green
     *  fill = complete area (usable offline right now), gray = still
     *  downloading. Only meaningful when [offlineAreas] is set; the legacy
     *  [offlineCoverage] rectangles (Offline Maps screen) render in the
     *  old blue and take priority when both are passed. */
    offlineAreas: List<com.nyasar.app.map.OfflineCoverageArea> = emptyList(),
    onWaypointClick: (GpxWaypoint) -> Unit = {},
    /** Fires for a plain map tap that didn't hit an existing waypoint —
     *  i.e. the same fallthrough case the waypoint-click listener below
     *  already returns `false` for, just exposed to callers that want it.
     *  Added for the draw-route feature (tap empty map -> add a point);
     *  existing waypoint-tap-to-detail behavior is completely unchanged,
     *  this only fires in the case that already did nothing before. */
    onMapClick: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    /** Fires when the user physically drags/pinches the map (a gesture, not
     *  our own animateCamera calls). Callers use this to drop out of follow
     *  mode — see the camera-move listener below for why this has to be a
     *  native MapLibre callback rather than a Compose pointerInput overlay:
     *  a pointerInput/detectDragGestures modifier sitting on top of this
     *  AndroidView intercepts the touch stream before MapLibre's own
     *  pan/pinch handling ever sees it, which silently breaks native
     *  map gestures. */
    /** Highlight marker shown when user scrubs the elevation chart — a circle
     *  at the corresponding lat/lon on the track. Null hides the marker. */
    highlightPoint: LatLng? = null,
    onUserGesture: () -> Unit = {},
    /** Fires whenever the camera's bearing changes — gesture (2-finger
     *  rotate) or programmatic (heading-up follow). Drives CompassButton's
     *  needle; null-checked by callers that don't show a compass. */
    onBearingChanged: (Float) -> Unit = {},
    /** Comfortable outdoor zoom level used whenever we programmatically
     *  move the camera to the user (follow tick, recenter, first fix). */
    followZoom: Double = 16.5,
    /** Opt-in to the process-wide shared MapView (SharedMapHolder) instead of
     *  owning a private instance. Used by Home, RoutePreview, and Recording —
     *  the 3 screens the user switches between constantly — so switching
     *  between them reuses the same GL surface + tile cache + loaded style
     *  instead of rebuilding everything from scratch on every visit.
     *
     *  Default false: every other call site (DrawRoute, ActivityDetail,
     *  Navigation, OfflineDownload, OfflineMaps, WaypointCrosshair) keeps
     *  owning a private MapView with its own full lifecycle, exactly as
     *  before — they didn't need the optimization and keeping them off the
     *  shared instance means a navigation/offline screen can setStyle freely
     *  without fighting whatever the 3 tab screens last rendered.
     *
     *  What "shared" changes here:
     *  1. Instance: borrowed from SharedMapHolder (created once per process,
     *     never destroyed), detached from the outgoing screen's view tree and
     *     re-attached by the incoming screen's AndroidView.
     *  2. Style: setStyle() is SKIPPED when the requested style key already
     *     matches what's loaded — re-entering Home with the same basemap
     *     performs zero style/tile work; only the GeoJSON content sources
     *     (track/waypoints/user marker) are cheaply re-applied. A genuinely
     *     different basemap still reloads the style, once.
     *  3. Listeners: MapLibre's add*Listener calls are additive with no
     *     remove counterpart, so tap/gesture/bearing listeners for the shared
     *     instance are registered ONCE (first host ever) and forward into
     *     SharedMapHolder.TapHandlers slots that each screen swaps on entry —
     *     no stacking, no stale handlers from a previous screen. */
    shared: Boolean = false,
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Shared mode: one process-wide MapView (see SharedMapHolder + [shared]).
    // Private mode: unchanged remember-scoped instance with full lifecycle.
    val mapView = if (shared) {
        remember { SharedMapHolder.get(context) }
    } else {
        remember(context) { MapView(context) }
    }

    DisposableEffect(mapView) {
        // Shared mode intentionally does NOTHING here — parenting is handled
        // inside the AndroidView factory below. Lifecycle calls were made
        // once by SharedMapHolder.get(); onDestroy is never called (that's
        // the whole point of the shared instance).
        //
        // BUG FIX (black map): the detach used to live in this effect block.
        // DisposableEffect runs AFTER the composition is applied — i.e. AFTER
        // AndroidView has already attached the MapView to its holder — so
        // every screen entry detached the just-attached map and left it
        // orphaned with no parent: GL surface alive but never displayed,
        // rendering as a solid black rectangle under the (still visible)
        // Compose UI overlays. Detaching must happen synchronously BEFORE
        // AndroidView attaches, which is exactly what the factory block is
        // for (see below).
        if (!shared) {
            mapView.onCreate(null)
            mapView.onStart()
            mapView.onResume()
        }
        onDispose {
            if (!shared) {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
            // Shared mode: keep the instance alive — see SharedMapHolder doc.
        }
    }

    // Shared mode only: keep the holder's handler slots pointed at THIS
    // screen's lambdas on every recomposition (cheap field writes; the
    // physical MapLibre listeners forward into them). Private mode never
    // touches the slots.
    if (shared) {
        SharedMapHolder.tapHandlers = SharedMapHolder.TapHandlers(
            onMapClick = onMapClick,
            onMapLongPress = onMapLongPress,
            onWaypointClick = onWaypointClick,
            onUserWaypointClick = onUserWaypointClick,
            onUserGesture = onUserGesture,
            onBearingChanged = onBearingChanged
        )
    }

    // Identity of the LAST COMPLETED full style load on this map instance.
    // The overlay/my-routes effects below used to be keyed only on their own
    // inputs + basemapEntry — but a basemap switch is a race: those effects
    // (re)run against the OLD style while the new setStyle() is still
    // loading, then the fresh style arrives WITHOUT them (its completion
    // happens later, out of band). Visible symptom: after changing the
    // basemap every Waymarked overlay AND "Jalur Saya" silently vanished
    // until the user toggled them off+on (which re-ran the effects against
    // the now-live style). Bumping [styleGeneration] from inside the
    // setStyle() completion callback re-keys those effects exactly when the
    // new style is live, so overlays are re-applied on top of it — and NOT
    // re-run on the shared fast path (style unchanged ⇒ nothing wiped ⇒
    // nothing to re-apply).
    var styleGeneration by remember { mutableStateOf(0) }

    // Waymarked Trails overlay toggle — see the parameter doc on
    // [activeOverlays] for why this is a separate effect from the big
    // style-setup one below. getStyle() (not setStyle()) so this never
    // touches the base map's own sources/layers; only adds/removes the
    // 3 possible overlay source+layer pairs. Runs on every re-composition
    // where activeOverlays changed AND whenever basemapEntry/styleVariant
    // changes too (a fresh setStyle() call wipes ALL sources/layers,
    // overlays included, so they need to be re-applied after any full
    // style reload — keying on those here as well, not just
    // activeOverlays, is what makes overlays survive a basemap switch
    // instead of silently disappearing the next time the user picks a
    // different basemap). [styleGeneration] closes the race: the effect
    // runs once more, AFTER the new style has fully loaded, against THAT
    // style.
    LaunchedEffect(activeOverlays, basemapEntry, provider.id, styleVariant, styleGeneration) {
        mapView.getMapAsync { map ->
            map.getStyle { style ->
                com.nyasar.app.map.OverlayLayer.entries.forEach { overlay ->
                    val sourceId = "nyasar-overlay-${overlay.id}-source"
                    val layerId = "nyasar-overlay-${overlay.id}-layer"
                    val shouldBeOn = overlay in activeOverlays
                    val isOn = style.getLayer(layerId) != null
                    if (shouldBeOn && !isOn) {
                        if (style.getSourceAs<org.maplibre.android.style.sources.RasterSource>(sourceId) == null) {
                            style.addSource(
                                org.maplibre.android.style.sources.RasterSource(
                                    sourceId,
                                    org.maplibre.android.style.sources.TileSet("tilejson", overlay.rasterUrl).apply {
                                        setMaxZoom(overlay.maxZoom.toFloat())
                                        // Attribution intentionally not set here — an
                                        // earlier attempt called a setAttribution(String)
                                        // that doesn't exist on this TileSet API and
                                        // failed to compile; rather than guess a second
                                        // unverified method name, the map stays fully
                                        // functional without it (overlay.attribution is
                                        // still defined on OverlayLayer for use in UI
                                        // text, e.g. the picker sheet, just not fed into
                                        // MapLibre's own attribution control here).
                                    },
                                    256
                                )
                            )
                        }
                        // Overlay must sit above the basemap but below
                        // Nyasar's own route/track/waypoint/user layers —
                        // insert right above the basemap's own bottom
                        // layer (index 0) rather than appending at the
                        // very top, so a route line drawn on the map is
                        // never hidden underneath a trail overlay.
                        val bottomLayerId = style.layers.firstOrNull()?.id
                        val overlayLayer = org.maplibre.android.style.layers.RasterLayer(layerId, sourceId)
                        if (bottomLayerId != null) {
                            style.addLayerAbove(overlayLayer, bottomLayerId)
                        } else {
                            style.addLayer(overlayLayer)
                        }
                    } else if (!shouldBeOn && isOn) {
                        try { style.removeLayer(layerId) } catch (_: Exception) {}
                        try { style.removeSource(sourceId) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    // "Jalur Saya" overlay — see the [myRoutes]/[activeRouteId] param docs.
    // Same effect pattern as the Waymarked Trails effect above: deliberately
    // OUTSIDE the big style-setup effect (toggling this overlay must never
    // reload the basemap), keyed on basemapEntry/provider.id/styleVariant so
    // the layers are re-applied after any full style reload, and on
    // (myRoutes, activeRouteId) so import/delete/active-route changes redraw
    // immediately — the lines flow is reactive over Room's observeAll, so no
    // manual refresh path exists or is needed. Nothing here runs at all
    // while the data and style are unchanged. [styleGeneration] re-keys this
    // after every completed style load for the same basemap-switch race the
    // Waymarked effect documents above (symptom: "Jalur Saya" vanishing on
    // basemap change until toggled off+on).
    LaunchedEffect(myRoutes, activeRouteId, basemapEntry, provider.id, styleVariant, styleGeneration) {
        mapView.getMapAsync { map ->
            map.getStyle { style ->
                // Skip degenerate geometry: a LineString needs >= 2 points,
                // so 0/1-point routes (e.g. a GPX holding only waypoints)
                // would build an invalid feature. Everything else renders.
                val features = myRoutes.filter { it.points.size >= 2 }.map { it.toFeature() }
                val source = style.getSourceAs<GeoJsonSource>(SOURCE_MY_ROUTES)
                    ?: GeoJsonSource(SOURCE_MY_ROUTES).also { style.addSource(it) }
                source.setGeoJson(FeatureCollection.fromFeatures(features))
                // Layers are rebuilt on every run — this effect only fires on
                // data/style changes (never per frame), and rebuilding makes
                // the active-route split trivially correct (no incremental
                // filter patching to get wrong). With activeRouteId == null
                // the sentinel "" matches no real UUID id, so the base layer
                // keeps everything and the accent layer renders nothing.
                try { style.removeLayer(LAYER_MY_ROUTES_ACTIVE) } catch (_: Exception) {}
                try { style.removeLayer(LAYER_MY_ROUTES) } catch (_: Exception) {}
                // Insert above the topmost Waymarked overlay layer when one
                // is on (user data reads better above raster trails). When
                // no trail overlay is on, anchor just BELOW the planned-
                // track layer: the track/waypoint/user-marker layers all sit
                // at the very top of the stack, so this keeps the lines
                // above EVERY basemap layer (anchoring above the bottom-most
                // basemap layer instead would hide them under water/roads
                // in vector styles) while still never covering app markers.
                // Fallback append-at-top only fires when neither exists yet
                // (fresh style, app layers not created) — app layers created
                // afterwards are appended above, preserving the order.
                val anchorId = com.nyasar.app.map.OverlayLayer.entries
                    .map { "nyasar-overlay-${it.id}-layer" }
                    .lastOrNull { style.getLayer(it) != null }
                val routeIdProp = com.nyasar.app.map.MyRouteLine.PROP_ROUTE_ID
                val activeId = activeRouteId ?: ""
                val baseLayer = LineLayer(LAYER_MY_ROUTES, SOURCE_MY_ROUTES)
                    .withFilter(
                        org.maplibre.android.style.expressions.Expression.not(
                            org.maplibre.android.style.expressions.Expression.eq(
                                org.maplibre.android.style.expressions.Expression.get(routeIdProp),
                                org.maplibre.android.style.expressions.Expression.literal(activeId)
                            )
                        )
                    )
                    .withProperties(
                        PropertyFactory.lineColor("#8A8A8A"),
                        PropertyFactory.lineWidth(3f),
                        PropertyFactory.lineCap("round"),
                        PropertyFactory.lineJoin("round"),
                        PropertyFactory.lineDasharray(arrayOf(2f, 2f))
                    )
                val activeLayer = LineLayer(LAYER_MY_ROUTES_ACTIVE, SOURCE_MY_ROUTES)
                    .withFilter(
                        org.maplibre.android.style.expressions.Expression.eq(
                            org.maplibre.android.style.expressions.Expression.get(routeIdProp),
                            org.maplibre.android.style.expressions.Expression.literal(activeId)
                        )
                    )
                    .withProperties(
                        // Same blue as the planned-track layer (LAYER_TRACK):
                        // "the route I'm following" reads as blue app-wide.
                        PropertyFactory.lineColor("#42A5F5"),
                        PropertyFactory.lineWidth(4.5f),
                        PropertyFactory.lineCap("round"),
                        PropertyFactory.lineJoin("round")
                    )
                when {
                    anchorId != null -> {
                        style.addLayerAbove(baseLayer, anchorId)
                        style.addLayerAbove(activeLayer, LAYER_MY_ROUTES)
                    }
                    style.getLayer(LAYER_TRACK) != null -> {
                        style.addLayerBelow(baseLayer, LAYER_TRACK)
                        style.addLayerAbove(activeLayer, LAYER_MY_ROUTES)
                    }
                    else -> {
                        style.addLayer(baseLayer)
                        style.addLayer(activeLayer)
                    }
                }
            }
        }
    }

    // Camera-fit gate for the shared fast path below. refreshSharedContent
    // refits the camera to the track bounds on every invocation — correct on
    // screen entry / route switch, WRONG on waypoint-only updates: dbWaypoints
    // arrives asynchronously from Room (typically right AFTER first render),
    // so without this gate every waypoint add/edit/delete emission yanked the
    // camera back to the full-route view, throwing away wherever the user had
    // panned/zoomed (the "map kaku / lompat sendiri" complaint). The signature
    // only changes when the planned track itself changes — list size plus
    // first/last coordinates is enough to tell "new route loaded" from "same
    // route, waypoints updated" at O(1) cost. Remembered per host composition:
    // re-entering the screen starts fresh, so the desired once-per-entry fit
    // still happens.
    var lastFittedTrackSignature by remember { mutableStateOf<String?>(null) }

    // focusBounds intentionally NOT a key here. OfflineDownloadScreen feeds
    // this from state that it itself updates on every camera-idle event
    // (recomputeBoundsFromViewport) — if focusBounds re-triggered this
    // effect, every idle would re-run moveCamera below, which triggers
    // another idle, which re-triggers this effect again: an infinite
    // camera feedback loop (the "download ngezoom terus" issue). The very
    // first composition's focusBounds value is applied once inside this
    // effect and never again after — see the one-shot effect further below
    // for handling subsequent focusBounds changes intentionally.
    LaunchedEffect(provider.id, styleVariant, basemapEntry, track, waypoints, userWaypoints, waypointsVisible) {
        // Compute the style identity FIRST. In shared mode this is compared
        // against what the shared instance currently has loaded: a match means
        // the whole setStyle pipeline below is skipped and the effect only
        // refreshes the GeoJSON content sources. This is the line that makes
        // Home -> Recording -> Home perform zero style/tile work — the exact
        // "map reloads every screen switch" problem this whole fix targets.
        val styleKey = SharedMapHolder.styleKey(provider.id, basemapEntry, styleVariant)
        val styleAlreadyLoaded = shared && SharedMapHolder.isStyleLoaded(styleKey)
        mapView.getMapAsync { map ->
            // MapLibre's own built-in compass widget is separate from our
            // Compose CompassButton (NavigationScreen/RecordingScreen) and
            // Home's recenter-button heading toggle. Left at its default it
            // renders at a fixed small margin with no awareness of the
            // status bar/edge-to-edge insets this app draws under, so it
            // ends up half-hidden behind the status bar on real devices
            // ("kompas ketutup") — and it's redundant with our own compass
            // UI everywhere it would show up anyway.
            map.uiSettings.isCompassEnabled = false
            val styleUri = basemapEntry?.let { provider.styleUrlFor(it, context) } ?: provider.styleUrl(styleVariant)
            // P3K audit fix (root cause of "peta gak berubah" — the map's
            // OWN setStyle, not just MapSnapshotter, had the same problem):
            // map.setStyle(String) resolves through Style.Builder().
            // fromUri(uri) internally — that path never reliably completes
            // for a data:application/json;base64 URI (it's meant for real
            // URLs/asset paths), so for every entry using an inline style
            // (OpenStreetMap, OpenTopoMap, OpenHikingMap, CyclOSM, Liberty
            // Satellite) this LaunchedEffect *did* re-run and *did* call
            // setStyle every time basemapEntry changed, but the load never
            // finished — no crash, no visible error, the style callback
            // below just never fired, so the live map silently kept
            // whatever style was already loaded. Confirmed on-device with
            // a temporary debug Toast in both branches: the "called" toast
            // fired for all 9 entries, the "completed" toast only fired
            // for the 4 using a real remote styleUrl. Same underlying
            // MapLibre quirk as the MapSnapshotter fix above, same
            // resolution: detect the data: URI, decode it back to raw
            // JSON, and load it via Style.Builder().fromJson(json) —
            // fromJson is the API MapLibre actually documents for
            // in-memory/inline styles. Non-data-URI entries (remote
            // styleUrl, or a plain http(s) URL/asset URI) keep using
            // fromUri exactly as before, since that path was never broken
            // for them.
            val dataUriPrefix = "data:application/json;base64,"
            // Shared-mode style skip: only skip when the holder confirms the
            // requested style is the one currently live on the shared GL
            // surface. markStyleLoading() runs BEFORE the call so a failed or
            // interrupted load can never be mistaken for a completed one (the
            // key resets to null and the next screen re-runs setStyle).
            if (styleAlreadyLoaded) {
                // Shared fast path: the requested style is EXACTLY what's on
                // screen — zero setStyle/tile work. Only refresh the sources
                // this screen owns (planned track, waypoints, user waypoints)
                // and refit the camera — but ONLY when the track itself changed
                // (see lastFittedTrackSignature above for why per-call refit
                // was wrong). actualTrack/drawnPoints don't need this: their
                // dedicated effects below run on every fresh composition with
                // the current list anyway.
                val trackSignature = if (track.isEmpty()) "0" else
                    "${track.size}:${track.first().lat},${track.first().lon}:${track.last().lat},${track.last().lon}"
                val needsCameraFit = lastFittedTrackSignature != trackSignature
                refreshSharedContent(
                    map, mapView, track,
                    if (waypointsVisible) waypoints else emptyList(),
                    if (waypointsVisible) userWaypoints else emptyList(),
                    focusBounds, refitCamera = needsCameraFit
                )
                if (needsCameraFit) lastFittedTrackSignature = trackSignature
                onMapReady(map)
                return@getMapAsync
            }
            val styleBuilder = if (styleUri.startsWith(dataUriPrefix)) {
                val json = String(
                    android.util.Base64.decode(styleUri.removePrefix(dataUriPrefix), android.util.Base64.DEFAULT),
                    Charsets.UTF_8
                )
                org.maplibre.android.maps.Style.Builder().fromJson(json)
            } else {
                org.maplibre.android.maps.Style.Builder().fromUri(styleUri)
            }
            if (shared) SharedMapHolder.markStyleLoading()
            map.setStyle(styleBuilder) { style ->
                // Success — only now is the key allowed to read as loaded.
                if (shared) SharedMapHolder.markStyleLoaded(styleKey)
                // New style is LIVE: bump the generation so the overlay /
                // "Jalur Saya" effects re-run against it (the fresh style
                // has neither). Without this they'd only re-apply on the
                // next unrelated recomposition — or never, which was the
                // "overlay hilang setelah ganti basemap" bug.
                styleGeneration++
                if (style.getImage("nyasar-heading-arrow") == null) {
                    style.addImage("nyasar-heading-arrow", headingArrowBitmap())
                }
                // GPX waypoint pin — an in-code bitmap instead of the style
                // sprite "marker-15". MapLibre silently draws NO symbol when
                // iconImage names an image the style doesn't carry, and our
                // inline raster styles (RasterStyleJson) ship no sprite at
                // all — so iconImage("marker-15") rendered nothing on them.
                // Registering our own bitmap makes the layer independent of
                // the style's sprite contents (same approach as the category
                // pins below).
                if (style.getImage("nyasar-marker") == null) {
                    style.addImage("nyasar-marker", userWaypointMarkerBitmap(android.graphics.Color.parseColor("#42A5F5")))
                }
                // Note on fonts: MapLibre Android SDK does not support custom
                // font registration via style.addFont(). The waypoint label
                // layers below (see addWaypointLabelLayer) therefore pin
                // textFont to "Noto Sans Regular" — the family every glyphs
                // endpoint in this app serves (vector basemaps ship Noto;
                // the inline raster styles borrow OpenFreeMap's font server).
                // Omitting textFont is NOT an option: MapLibre's spec-default
                // stack ("Open Sans Regular" etc.) 404s on that endpoint and
                // textOptional then silently hides the label. To use Inter
                // on-map, the font must be baked into the style's font stack
                // at the tile-server level.
                // Track line (planned route = blue, actual/recorded = green)
                // When there's no planned route but actualTrack has data,
                // the caller passes actualTrack via the `track` param —
                // detect this and route it to the green actual-track layer
                // so it never shows up in blue.
                val lineString = LineString.fromLngLats(track.map { Point.fromLngLat(it.lon, it.lat) })
                // When there's a planned route, track goes in blue (#42A5F5);
                // when there's no planned route, track is actual walked path —
                // shown in the same muted green (#5A7562) everywhere.
                val trackColor = trackColorOverride
                    ?: if (track.isNotEmpty() && actualTrack.isEmpty()) "#5A7562" else "#42A5F5"
                val trackSource = style.getSourceAs<GeoJsonSource>(SOURCE_TRACK)
                if (trackSource != null) {
                    trackSource.setGeoJson(lineString)
                } else {
                    style.addSource(GeoJsonSource(SOURCE_TRACK, lineString))
                    style.addLayer(
                        LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                            PropertyFactory.lineColor(trackColor),
                            PropertyFactory.lineWidth(4f),
                            PropertyFactory.lineCap("round"),
                            PropertyFactory.lineJoin("round")
                        )
                    )
                }

                // Actual/recorded track (spec section 3: "jejak yang sudah
                // dilewati digambar realtime di map"). Separate source from
                // the planned-route track above so live updates during
                // recording (every GPS fix, via the dedicated effect below)
                // never re-trigger this whole style-setup block.
                if (style.getSourceAs<GeoJsonSource>(SOURCE_ACTUAL_TRACK) == null) {
                    val actualLine = LineString.fromLngLats(
                        actualTrack.map { Point.fromLngLat(it.lon, it.lat) }
                    )
                    style.addSource(GeoJsonSource(SOURCE_ACTUAL_TRACK, actualLine))
                    style.addLayer(
                        LineLayer(LAYER_ACTUAL_TRACK, SOURCE_ACTUAL_TRACK).withProperties(
                            PropertyFactory.lineColor("#5A7562"),
                            PropertyFactory.lineWidth(5f),
                            PropertyFactory.lineCap("round"),
                            PropertyFactory.lineJoin("round")
                        )
                    )
                }

                // Draw-route feature: same "start empty, update via its own
                // effect below" pattern as SOURCE_ACTUAL_TRACK above, so
                // tapping a new point never re-runs this whole block (see
                // drawnPoints param doc for why that matters here
                // specifically — camera refit on every tap). Distinct
                // orange/dashed styling: this is an unconfirmed draft, not
                // a walked track (green) or a loaded route (the track
                // param's own color).
                if (style.getSourceAs<GeoJsonSource>(SOURCE_DRAWN_POINTS) == null) {
                    val drawnLine = LineString.fromLngLats(
                        drawnPoints.map { Point.fromLngLat(it.lon, it.lat) }
                    )
                    style.addSource(GeoJsonSource(SOURCE_DRAWN_POINTS, drawnLine))
                    style.addLayer(
                        LineLayer(LAYER_DRAWN_POINTS, SOURCE_DRAWN_POINTS).withProperties(
                            PropertyFactory.lineColor("#42A5F5"),
                            PropertyFactory.lineWidth(4f),
                            PropertyFactory.lineCap("round"),
                            PropertyFactory.lineJoin("round")
                        )
                    )
                }

                // Waypoint markers — properties carry everything needed for a
                // detail view (spec section 13) so a tap doesn't need a second lookup.
                // Data-section toggle: when OFF the pins are dropped at the data
                // level (empty features) — sources/layers stay registered but
                // render nothing, so toggling back ON is a cheap setGeoJson.
                val features = if (waypointsVisible) waypoints.map { wp ->
                    Feature.fromGeometry(Point.fromLngLat(wp.lon, wp.lat)).apply {
                        addStringProperty(PROP_WP_NAME, wp.name)
                        addNumberProperty(PROP_WP_LAT, wp.lat)
                        addNumberProperty(PROP_WP_LON, wp.lon)
                        wp.elevationM?.let { addNumberProperty(PROP_WP_ELEVATION, it) }
                        wp.description?.let { addStringProperty(PROP_WP_DESCRIPTION, it) }
                    }
                } else emptyList()
                // REUSE the source/layer — same rule as refreshSharedContent
                // below. removeLayer/removeSource + re-add inside one style
                // callback can throw mid-batch ("id already exists" against a
                // removal still queued) and silently kill the REST of this
                // callback. setGeoJson never goes stale: the layer's text
                // properties are static (data-independent), and a full style
                // reload recreates both from scratch anyway.
                val gpxWpSource = style.getSourceAs<GeoJsonSource>(SOURCE_WAYPOINTS)
                if (gpxWpSource != null) {
                    gpxWpSource.setGeoJson(FeatureCollection.fromFeatures(features))
                } else {
                    style.addSource(GeoJsonSource(SOURCE_WAYPOINTS, FeatureCollection.fromFeatures(features)))
                }
                if (style.getLayer(LAYER_WAYPOINTS) == null) {
                    // ICON and LABEL are SEPARATE layers on purpose — the
                    // FINAL root cause of "pin gak muncul di Route Viewer,
                    // padahal di Activity Detail muncul". A symbol layer with
                    // ANY text property requires the style's "glyphs"
                    // endpoint; the inline raster basemaps used to ship none,
                    // so MapLibre's style validation REJECTED this whole
                    // layer — hiding the icon too, not just the label. (The
                    // earlier Inter-*/textOptional fixes couldn't work: no
                    // text setting rescues a layer that never validates.) An
                    // icon-only layer has zero glyph dependency, so pins
                    // render on EVERY basemap; labels live in their own
                    // text-only layer (see addWaypointLabelLayer) that merely
                    // hides on glyph-less styles instead of taking pins down.
                    style.addLayer(
                        SymbolLayer(LAYER_WAYPOINTS, SOURCE_WAYPOINTS).withProperties(
                            PropertyFactory.iconImage("nyasar-marker"),
                            PropertyFactory.iconAllowOverlap(true)
                        )
                    )
                    addWaypointLabelLayer(style, LAYER_LABEL_WAYPOINTS, SOURCE_WAYPOINTS, LAYER_WAYPOINTS)
                }

                // User-created waypoints (spec P3E2) — own source/layer, one
                // colored pin bitmap per category (registered once, keyed by
                // name) so markers are visually distinguishable at a glance,
                // not just on tap — spec: "gunakan icon yang mudah dibedakan".
                com.nyasar.app.data.db.WaypointCategory.entries.forEach { cat ->
                    val imageName = "nyasar-uwp-${cat.name}"
                    if (style.getImage(imageName) == null) {
                        style.addImage(imageName, userWaypointMarkerBitmap(cat.color.toArgb()))
                    }
                }
                val userWpFeatures = if (waypointsVisible) userWaypoints.map { wp ->
                    Feature.fromGeometry(Point.fromLngLat(wp.lon, wp.lat)).apply {
                        addStringProperty(PROP_UWP_ID, wp.id)
                        addStringProperty(PROP_WP_NAME, wp.name)
                        addStringProperty(PROP_UWP_CATEGORY, wp.category)
                    }
                } else emptyList()
                // Same reuse rule as the GPX block above — update the existing
                // source, add the layer only when genuinely missing. remove/re-add
                // here can throw mid-batch and silently kill the rest of the callback.
                val userWpSource = style.getSourceAs<GeoJsonSource>(SOURCE_USER_WAYPOINTS)
                if (userWpSource != null) {
                    userWpSource.setGeoJson(FeatureCollection.fromFeatures(userWpFeatures))
                } else {
                    style.addSource(GeoJsonSource(SOURCE_USER_WAYPOINTS, FeatureCollection.fromFeatures(userWpFeatures)))
                }
                if (style.getLayer(LAYER_USER_WAYPOINTS) == null) {
                    val iconMatchStops = com.nyasar.app.data.db.WaypointCategory.entries.flatMap { cat ->
                        listOf(
                            org.maplibre.android.style.expressions.Expression.literal(cat.name),
                            org.maplibre.android.style.expressions.Expression.literal("nyasar-uwp-${cat.name}")
                        )
                    }.toTypedArray()
                    // Same icon/label split as the GPX layer above — pins
                    // must not depend on the style's glyphs endpoint.
                    style.addLayer(
                        SymbolLayer(LAYER_USER_WAYPOINTS, SOURCE_USER_WAYPOINTS).withProperties(
                            PropertyFactory.iconImage(
                                org.maplibre.android.style.expressions.Expression.match(
                                    org.maplibre.android.style.expressions.Expression.get(PROP_UWP_CATEGORY),
                                    org.maplibre.android.style.expressions.Expression.literal("nyasar-uwp-${com.nyasar.app.data.db.WaypointCategory.CUSTOM.name}"),
                                    *iconMatchStops
                                )
                            ),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconSize(1f)
                        )
                    )
                    addWaypointLabelLayer(style, LAYER_LABEL_USER_WAYPOINTS, SOURCE_USER_WAYPOINTS, LAYER_USER_WAYPOINTS)
                }

                // User location marker (spec section 6/22): a soft halo behind a
                // solid dot, drawn as its own source/layers so position updates
                // (every GPS fix) never touch the track/waypoint sources above.
                if (style.getSourceAs<GeoJsonSource>(SOURCE_ACCURACY) == null) {
                    style.addSource(GeoJsonSource(SOURCE_ACCURACY, FeatureCollection.fromFeatures(emptyArray())))
                    style.addLayer(
                        org.maplibre.android.style.layers.FillLayer(LAYER_ACCURACY_FILL, SOURCE_ACCURACY).withProperties(
                            PropertyFactory.fillColor("#2979FF"),
                            PropertyFactory.fillOpacity(0.12f)
                        )
                    )
                }
                if (style.getSourceAs<GeoJsonSource>(SOURCE_USER) == null) {
                    style.addSource(GeoJsonSource(SOURCE_USER, FeatureCollection.fromFeatures(emptyArray())))
                    style.addLayer(
                        CircleLayer(LAYER_USER_HALO, SOURCE_USER).withProperties(
                            PropertyFactory.circleRadius(14f),
                            PropertyFactory.circleColor("#2979FF"),
                            PropertyFactory.circleOpacity(0.25f)
                        )
                    )
                    style.addLayer(
                        CircleLayer(LAYER_USER_DOT, SOURCE_USER).withProperties(
                            PropertyFactory.circleRadius(7f),
                            PropertyFactory.circleColor("#2979FF"),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#FFFFFF")
                        )
                    )
                    // Small heading wedge, offset ahead of the dot and rotated by
                    // bearing. Filtered to only features carrying "hasHeading" —
                    // when GPS reports no bearing we omit that property entirely
                    // (see the position-update effect below), so the icon simply
                    // isn't drawn instead of snapping to a fake 0° default.
                    style.addLayer(
                        SymbolLayer(LAYER_USER_HEADING, SOURCE_USER).withProperties(
                            PropertyFactory.iconImage("nyasar-heading-arrow"),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("heading")),
                            PropertyFactory.iconRotationAlignment("map"),
                            PropertyFactory.iconSize(1f),
                            PropertyFactory.iconOffset(arrayOf(0f, -22f))
                        ).withFilter(org.maplibre.android.style.expressions.Expression.has("heading"))
                    )
                }

                // Highlight marker (elevation chart scrub) — own source/layer,
                // updated by its own effect below so chart interaction never
                // re-runs the whole style setup.
                if (style.getSourceAs<GeoJsonSource>(SOURCE_HIGHLIGHT) == null) {
                    style.addSource(GeoJsonSource(SOURCE_HIGHLIGHT, FeatureCollection.fromFeatures(emptyArray())))
                    style.addLayer(
                        CircleLayer(LAYER_HIGHLIGHT_CIRCLE, SOURCE_HIGHLIGHT).withProperties(
                            PropertyFactory.circleRadius(12f),
                            PropertyFactory.circleColor("#42A5F5"),
                            PropertyFactory.circleOpacity(0.35f)
                        )
                    )
                    style.addLayer(
                        CircleLayer(LAYER_HIGHLIGHT_OUTLINE, SOURCE_HIGHLIGHT).withProperties(
                            PropertyFactory.circleRadius(6f),
                            PropertyFactory.circleColor("#42A5F5"),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#FFFFFF")
                        )
                    )
                }

                // Offline coverage rectangles (spec §24, WAJIB). Built once
                // here; content refreshed by its own LaunchedEffect below so
                // toggling coverage doesn't retrigger the whole style setup.
                if (style.getSourceAs<GeoJsonSource>(SOURCE_OFFLINE_COVERAGE) == null) {
                    style.addSource(GeoJsonSource(SOURCE_OFFLINE_COVERAGE, FeatureCollection.fromFeatures(emptyArray())))
                    style.addLayerBelow(
                        org.maplibre.android.style.layers.FillLayer(LAYER_OFFLINE_COVERAGE_FILL, SOURCE_OFFLINE_COVERAGE).withProperties(
                            PropertyFactory.fillColor("#2979FF"),
                            PropertyFactory.fillOpacity(0.12f)
                        ),
                        LAYER_TRACK
                    )
                    style.addLayerBelow(
                        LineLayer(LAYER_OFFLINE_COVERAGE_OUTLINE, SOURCE_OFFLINE_COVERAGE).withProperties(
                            PropertyFactory.lineColor("#2979FF"),
                            PropertyFactory.lineWidth(2f),
                            PropertyFactory.lineDasharray(arrayOf(2f, 2f))
                        ),
                        LAYER_TRACK
                    )
                }

                // Camera positioning MUST happen after the view has its final
                // layout dimensions — MapLibre uses the view's width/height to
                // compute the camera position for newLatLngBounds. Inside a
                // LazyColumn or any container with dynamic sizing, the style
                // callback can fire before layout is complete, producing a
                // distorted/"penyet" map. The camera fit is deferred to after
                // the current layout pass (see applyCamera below).
                // Gated on the same track-signature rule as the shared fast
                // path: this callback re-runs whenever track/waypoints/
                // userWaypoints change, and on a PRIVATE instance (e.g.
                // ActivityDetail/RoutePreview) dbWaypoints is a Room flow that
                // keeps emitting while the screen is open — every add/edit/
                // delete/categorize of a waypoint used to yank the camera back
                // to the full-route bounds, discarding the user's pan/zoom
                // (the "map lompat sendiri" complaint). Runnable via a null
                // var (not a captured local val): Kotlin would otherwise
                // force it to final and the assignment below wouldn't
                // compile; null keeps "no camera work this run" explicit.
                var applyCamera: Runnable? = null
                val trackSignature = if (track.isEmpty()) "0" else
                    "${track.size}:${track.first().lat},${track.first().lon}:${track.last().lat},${track.last().lon}"
                if (lastFittedTrackSignature != trackSignature) {
                    lastFittedTrackSignature = trackSignature
                    applyCamera = Runnable {
                        if (track.isNotEmpty()) {
                            val bounds = boundsOf(track)
                            val hasRealSpan = bounds.latitudeSpan > 0.0005 || bounds.longitudeSpan > 0.0005
                            if (hasRealSpan) {
                                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                            } else {
                                map.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 17.5))
                            }
                        } else if (focusBounds != null) {
                            map.moveCamera(CameraUpdateFactory.newLatLngBounds(focusBounds, 40))
                        }
                    }
                }
                applyCamera?.let { mapView.post(it) }

                // Native gesture detection (spec: "1 jari drag = PAN", "saat
                // user menggeser/zoom manual -> Follow GPS harus OFF"). This
                // is registered on the MapLibreMap itself, not via a Compose
                // pointerInput overlay, so it never steals touch events from
                // MapLibre's own pan/pinch/rotate handling.
                // Private map only: direct lambdas on a fresh instance, exactly
                // the original behavior. A shared map must NOT register these
                // here — add*Listener is additive with no remove API, so every
                // style load would stack another pair; the physical forwarding
                // listeners are installed exactly once below instead.
                if (!shared) {
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            onUserGesture()
                        }
                    }
                    // Compass needle source (spec complaint: "kompas gaada") —
                    // fires on every camera move regardless of cause (gesture
                    // rotate, or our own animateCamera heading-up calls), so the
                    // needle always reflects what's actually rendered.
                    map.addOnCameraMoveListener {
                        onBearingChanged(map.cameraPosition.bearing.toFloat())
                    }
                }
            }
            // Shared instance, physical listener install — runs at most ONCE per
            // process (first style load of the first shared screen ever).
            // These forward into SharedMapHolder.tapHandlers, which every shared
            // host re-points at its own handlers on entry, so taps/gestures
            // always reach the screen currently on display without any listener
            // accumulation. Camera listeners don't need the style loaded, so
            // registering here (possibly before the load finishes) is safe.
            if (shared && SharedMapHolder.needsCameraListenerInstall()) {
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        SharedMapHolder.tapHandlers.onUserGesture()
                    }
                }
                map.addOnCameraMoveListener {
                    SharedMapHolder.tapHandlers.onBearingChanged(map.cameraPosition.bearing.toFloat())
                }
                SharedMapHolder.markCameraListenersInstalled()
            }
            onMapReady(map)
        }
    }

    // Runs on every new recorded point — deliberately separate from the style
    // setup effect above so redrawing the actual track never re-adds
    // sources/layers, re-fits the camera, or touches the planned route.
    //
    // P3I audit fix (§15, large track): actualTrack.map{}+LineString.
    // fromLngLats() is O(n) work over the WHOLE track so far, not just the
    // new point — RecordingService republishes the full recordedTrack list
    // on every accepted fix, and getMapAsync's callback runs on the main
    // thread. Over a 3-8hr hike (~3600-9600 fixes at the ~3s GPS interval),
    // that means progressively more main-thread work every single fix as
    // the track grows into the thousands of points — real, worsening jank
    // by the later hours of a long recording, even though it never
    // crashes. The list mapping + LineString construction now happens on
    // Dispatchers.Default; only the final setGeoJson call (which MapLibre
    // requires on the map/main thread) still runs via getMapAsync.
    LaunchedEffect(actualTrack) {
        val lineString = withContext(Dispatchers.Default) {
            LineString.fromLngLats(actualTrack.map { Point.fromLngLat(it.lon, it.lat) })
        }
        mapView.getMapAsync { map ->
            val source = map.style?.getSourceAs<GeoJsonSource>(SOURCE_ACTUAL_TRACK) ?: return@getMapAsync
            source.setGeoJson(lineString)
        }
    }

    // Draw-route feature: fires on every tapped point. Same isolation
    // reasoning as actualTrack above — must not touch the style-setup
    // effect (camera refit on every tap otherwise). Point counts here are
    // small (a hand-drawn route, not thousands of GPS fixes), so this
    // stays synchronous rather than needing actualTrack's
    // Dispatchers.Default offload.
    LaunchedEffect(drawnPoints) {
        mapView.getMapAsync { map ->
            val source = map.style?.getSourceAs<GeoJsonSource>(SOURCE_DRAWN_POINTS) ?: return@getMapAsync
            source.setGeoJson(LineString.fromLngLats(drawnPoints.map { Point.fromLngLat(it.lon, it.lat) }))
        }
    }

    // Offline coverage rectangles (spec §24) — separate effect so a refresh
    // of the downloaded-regions list never touches the route/track sources.
    // Live overlay (offlineAreas): green fill = downloaded & complete for the
    // ACTIVE basemap's style, gray = still downloading. Style-URL matching
    // means the overlay never claims an area is usable offline when only a
    // different map style's tiles were stored. Both visual states live in
    // ONE source (per-feature fillColor); the source/layers are ensured
    // idempotently here so the shared MapView — which skips the full-load
    // path when the style is already loaded — still gets them.
    LaunchedEffect(offlineAreas, basemapEntry, provider.id, styleVariant, styleGeneration) {
        if (offlineAreas.isEmpty()) return@LaunchedEffect
        mapView.getMapAsync { map ->
            map.getStyle { style ->
                if (style.getSourceAs<GeoJsonSource>(SOURCE_OFFLINE_AREAS) == null) {
                    style.addSource(GeoJsonSource(SOURCE_OFFLINE_AREAS, FeatureCollection.fromFeatures(emptyArray())))
                    style.addLayerBelow(
                        org.maplibre.android.style.layers.FillLayer(LAYER_OFFLINE_AREAS_FILL, SOURCE_OFFLINE_AREAS).withProperties(
                            PropertyFactory.fillColor("#2E7D32"),
                            PropertyFactory.fillOpacity(0.14f)
                        ),
                        LAYER_TRACK
                    )
                    style.addLayerBelow(
                        org.maplibre.android.style.layers.LineLayer(LAYER_OFFLINE_AREAS_OUTLINE, SOURCE_OFFLINE_AREAS).withProperties(
                            PropertyFactory.lineColor("#2E7D32"),
                            PropertyFactory.lineWidth(2f),
                            PropertyFactory.lineDasharray(arrayOf(3f, 2f))
                        ),
                        LAYER_TRACK
                    )
                }
                // Per-feature color: getBoolean("complete") decides green vs
                // gray — both states in one source so a partial download
                // flipping to complete only updates GeoJSON, never layers.
                val fillLayer = style.getLayerAs<org.maplibre.android.style.layers.FillLayer>(LAYER_OFFLINE_AREAS_FILL)
                val lineLayer = style.getLayerAs<org.maplibre.android.style.layers.LineLayer>(LAYER_OFFLINE_AREAS_OUTLINE)
                if (fillLayer != null && lineLayer != null &&
                    fillLayer.getFillColorAsInt() == null && lineLayer.getLineColorAsInt() == null
                ) {
                    fillLayer.setProperties(
                        org.maplibre.android.style.layers.PropertyFactory.fillColor(
                            org.maplibre.android.style.expressions.Expression.switchCase(
                                org.maplibre.android.style.expressions.Expression.get("complete"),
                                org.maplibre.android.style.expressions.Expression.rgb(46, 125, 50),
                                org.maplibre.android.style.expressions.Expression.rgb(117, 117, 117)
                            )
                        )
                    )
                    lineLayer.setProperties(
                        org.maplibre.android.style.layers.PropertyFactory.lineColor(
                            org.maplibre.android.style.expressions.Expression.switchCase(
                                org.maplibre.android.style.expressions.Expression.get("complete"),
                                org.maplibre.android.style.expressions.Expression.rgb(46, 125, 50),
                                org.maplibre.android.style.expressions.Expression.rgb(117, 117, 117)
                            )
                        )
                    )
                }
                val features = offlineAreas.map { area ->
                    val b = area.bounds
                    val ring = listOf(
                        Point.fromLngLat(b.longitudeWest, b.latitudeSouth),
                        Point.fromLngLat(b.longitudeEast, b.latitudeSouth),
                        Point.fromLngLat(b.longitudeEast, b.latitudeNorth),
                        Point.fromLngLat(b.longitudeWest, b.latitudeNorth),
                        Point.fromLngLat(b.longitudeWest, b.latitudeSouth)
                    )
                    Feature.fromGeometry(
                        org.maplibre.geojson.Polygon.fromLngLats(listOf(ring)),
                        com.google.gson.JsonObject().apply { addProperty("complete", area.complete) }
                    )
                }
                style.getSourceAs<GeoJsonSource>(SOURCE_OFFLINE_AREAS)
                    ?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
        }
    }

    LaunchedEffect(offlineCoverage) {
        mapView.getMapAsync { map ->
            val source = map.style?.getSourceAs<GeoJsonSource>(SOURCE_OFFLINE_COVERAGE) ?: return@getMapAsync
            val features = offlineCoverage.map { bounds ->
                val ring = listOf(
                    Point.fromLngLat(bounds.longitudeWest, bounds.latitudeSouth),
                    Point.fromLngLat(bounds.longitudeEast, bounds.latitudeSouth),
                    Point.fromLngLat(bounds.longitudeEast, bounds.latitudeNorth),
                    Point.fromLngLat(bounds.longitudeWest, bounds.latitudeNorth),
                    Point.fromLngLat(bounds.longitudeWest, bounds.latitudeSouth)
                )
                Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(listOf(ring)))
            }
            source.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }

    // Highlight marker (elevation chart scrub) — separate effect so
    // chart interaction never re-runs the style setup or camera refit.
    LaunchedEffect(highlightPoint) {
        mapView.getMapAsync { map ->
            val source = map.style?.getSourceAs<GeoJsonSource>(SOURCE_HIGHLIGHT) ?: return@getMapAsync
            if (highlightPoint != null) {
                val feature = Feature.fromGeometry(
                    Point.fromLngLat(highlightPoint.longitude, highlightPoint.latitude)
                )
                source.setGeoJson(feature)
            } else {
                source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            }
        }
    }

    // Camera re-animation (heading-up rotation, follow ticks) is throttled
    // independently of marker updates — the marker/accuracy-circle redraw
    // above is cheap (setGeoJson) and can run on every heading tick, but
    // calling animateCamera() at sensor rate (even the UI-rate ~16Hz
    // HeadingProvider now uses) cancels/restarts the in-flight animation
    // every tick, which is exactly the "camera jitter" the spec forbids.
    // 300ms floor keeps heading-up visibly responsive while guaranteeing
    // each animateCamera call actually gets to finish.
    var lastCameraAnimateAtMs by remember { mutableStateOf(0L) }

    // Runs on every GPS fix — deliberately separate from the effect above
    // (which only re-runs on track/waypoint/provider changes) so a marker
    // position update never re-adds sources/layers or re-fits the camera.
    LaunchedEffect(userLocation, userHeadingDeg, followUser, rotateWithHeading, accuracyMeters) {
        if (userLocation == null) {
            // BUG FIX ("di layar terlihat posisi terkini padahal app bilang
            // mencari GPS"): on the SHARED MapView, the previous screen's
            // user-position dot lives on in SOURCE_USER (style + sources
            // survive screen switches), so Recording could show a stale blue
            // dot while its own position stream had produced nothing yet —
            // the dot read as "position is right there" while the screen
            // still claimed to be searching and recenter had no target. A
            // null position means "unknown HERE": clear the marker (and the
            // accuracy circle) instead of silently keeping the previous
            // screen's last known spot.
            mapView.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                style.getSourceAs<GeoJsonSource>(SOURCE_USER)
                    ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                style.getSourceAs<GeoJsonSource>(SOURCE_ACCURACY)
                    ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            }
            return@LaunchedEffect
        }
        mapView.getMapAsync { map ->
            val source = map.style?.getSourceAs<GeoJsonSource>(SOURCE_USER) ?: return@getMapAsync
            val feature = Feature.fromGeometry(Point.fromLngLat(userLocation.longitude, userLocation.latitude))
            userHeadingDeg?.let { feature.addNumberProperty("heading", it) }
            source.setGeoJson(feature)

            map.style?.getSourceAs<GeoJsonSource>(SOURCE_ACCURACY)?.let { accSource ->
                if (accuracyMeters != null && accuracyMeters > 0f) {
                    accSource.setGeoJson(geoCircle(userLocation, accuracyMeters.toDouble()))
                } else {
                    accSource.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
                }
            }

            if (followUser) {
                val now = System.currentTimeMillis()
                val dueForCameraUpdate = now - lastCameraAnimateAtMs >= 300L
                if (rotateWithHeading && userHeadingDeg != null) {
                    if (dueForCameraUpdate) {
                        lastCameraAnimateAtMs = now
                        val position = org.maplibre.android.camera.CameraPosition.Builder()
                            .target(userLocation)
                            .zoom(followZoom)
                            .bearing(userHeadingDeg.toDouble())
                            .build()
                        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 300)
                    }
                } else if (!rotateWithHeading && map.cameraPosition.bearing != 0.0) {
                    // User just switched out of heading-up mode (or GPS lost
                    // heading) while the camera was still rotated — reset to
                    // north-up in the same animation instead of leaving the
                    // map stuck at whatever angle it last rotated to. Always
                    // allowed through regardless of throttle — this is a
                    // one-shot mode switch, not a per-tick rotation update.
                    lastCameraAnimateAtMs = now
                    val position = org.maplibre.android.camera.CameraPosition.Builder()
                        .target(userLocation)
                        .zoom(followZoom)
                        .bearing(0.0)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(position))
                } else if (!rotateWithHeading) {
                    // North-up follow: pure recenter pan, no bearing change.
                    // newLatLngZoom (not newLatLng) so follow/recenter also brings
                    // the camera to a usable outdoor zoom level (spec: "zoom ke
                    // level yang nyaman"), not just a pan at whatever zoom the
                    // user happened to leave it at.
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLocation, followZoom))
                }
            }
        }
    }

    // BUG #1 FIX: Click/long-press listeners registered once in the
    // factory (runs once per map instance), not inside the style callback
    // above. MapLibre's addOn*Listener is additive — calling it in the
    // LaunchedEffect's style callback re-registered duplicate listeners on
    // every recomposition where track/waypoints/userWaypoints changed.
    // With listeners in the factory, duplicates are impossible.
    //
    // rememberUpdatedState keeps the lambda references current across
    // recompositions while the listener itself is only registered once.
    // queryRenderedFeatures is style-dependent but safe to call after
    // setStyle completes — by the time a user can tap the map the style
    // is always loaded.
    //
    // SHARED MODE: the factory runs again on every screen switch (new
    // AndroidView adopting the shared instance), so a direct registration
    // here would STACK one set of click listeners per visited screen.
    // Instead the physical click listeners are registered exactly once per
    // process (guarded by SharedMapHolder.needsTapListenerInstall()) and
    // forward into the holder's TapHandlers slots — which the composable
    // above re-points at THIS screen's rememberUpdatedState delegates on
    // every entry. Private mode keeps the original direct registration:
    // its factory runs once per owned instance, so nothing can stack.
    val currentOnWaypointClick by rememberUpdatedState(onWaypointClick)
    val currentOnUserWaypointClick by rememberUpdatedState(onUserWaypointClick)
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnMapLongPress by rememberUpdatedState(onMapLongPress)

    AndroidView(
        factory = {
            // Shared mode: detach from the previous host BEFORE AndroidView
            // attaches the returned view to this screen's holder (a View
            // can't have two parents). Must run here, synchronously — NOT in
            // a DisposableEffect, which fires after the view is already
            // attached and would orphan it (the black-map bug).
            if (shared) {
                SharedMapHolder.detachFromCurrentParent(mapView)
            }
            mapView.getMapAsync { map ->
                if (shared) {
                    if (SharedMapHolder.needsTapListenerInstall()) {
                        map.addOnMapClickListener { point ->
                            handleMapTap(
                                map, point, map.projection.toScreenLocation(point),
                                SharedMapHolder.tapHandlers
                            )
                        }
                        map.addOnMapLongClickListener { point ->
                            SharedMapHolder.tapHandlers.onMapLongPress(point.latitude, point.longitude)
                            true
                        }
                        SharedMapHolder.markTapListenersInstalled()
                    }
                } else {
                    // Private instance: build the handler set once per factory
                    // run (once per owned instance) from the current-delegates.
                    val privateHandlers = SharedMapHolder.TapHandlers(
                        onMapClick = { lat, lon -> currentOnMapClick(lat, lon) },
                        onMapLongPress = { lat, lon -> currentOnMapLongPress(lat, lon) },
                        onWaypointClick = { currentOnWaypointClick(it) },
                        onUserWaypointClick = { currentOnUserWaypointClick(it) }
                    )
                    map.addOnMapClickListener { point ->
                        handleMapTap(map, point, map.projection.toScreenLocation(point), privateHandlers)
                    }

                    map.addOnMapLongClickListener { point ->
                        currentOnMapLongPress(point.latitude, point.longitude)
                        true
                    }
                }
            }
            mapView
        },
        modifier = modifier
    )
}

/** Shared-mode fast path: the style on the shared instance is already the one
 *  this screen wants, so skip setStyle entirely and only refresh what this
 *  screen owns — planned-track source, waypoint sources, camera fit. The
 *  dedicated per-concern effects below (actualTrack/drawnPoints/highlight/
 *  user marker) run on every fresh composition with current data anyway. */
/**
 * Text labels for the waypoint pin layers, added as SEPARATE text-only
 * symbol layers. Root cause this solves ("pin gak muncul di Route Viewer
 * padahal di Activity Detail muncul"): a symbol layer with ANY text
 * property (textField/textFont/textOffset/...) requires the style's
 * "glyphs" font endpoint, and MapLibre's style validation REJECTS such a
 * layer wholesale on glyph-less styles — the inline raster basemaps
 * (RasterStyleJson) used to ship no glyphs URL — so the pin ICON was
 * hidden too, not just the label. No textOptional/textFont combination
 * can rescue a layer that never validates. Splitting pins (icon-only,
 * zero glyph dependency — renders on EVERY basemap including fully
 * offline ones) from labels (text-only, textOptional so a failed glyph
 * fetch hides just that one label) keeps pins bulletproof everywhere
 * while labels still render wherever the style provides fonts.
 *
 * Placed directly ABOVE [aboveLayerId] so a label can never cover a
 * neighboring pin (icons own their pixels; labels fill the gaps).
 */
private fun addWaypointLabelLayer(
    style: org.maplibre.android.maps.Style,
    labelLayerId: String,
    sourceId: String,
    aboveLayerId: String
) {
    style.addLayerAbove(
        org.maplibre.android.style.layers.SymbolLayer(labelLayerId, sourceId).withProperties(
            PropertyFactory.textField("{$PROP_WP_NAME}"),
            // Pin the label to the one family every glyphs endpoint in this
            // app serves (OpenFreeMap fonts endpoint: "Noto Sans Regular" =
            // 200 OK; MapLibre's spec-default stack 404s there — without
            // textFont the label would silently never render anywhere).
            // Safe on a TEXT-ONLY layer: a failed fetch (e.g. fully
            // offline) hides just this label via textOptional below — the
            // pin icon on its own layer is never affected.
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#1A1A1A"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textHaloBlur(0.5f),
            PropertyFactory.textOffset(arrayOf(0f, 1.8f)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textMaxWidth(8f),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textOptional(true)
        ),
        aboveLayerId
    )
}

private fun refreshSharedContent(
    map: MapLibreMap,
    mapView: MapView,
    track: List<TrackPoint>,
    waypoints: List<GpxWaypoint>,
    userWaypoints: List<com.nyasar.app.data.db.WaypointEntity>,
    focusBounds: org.maplibre.android.geometry.LatLngBounds?,
    /** False = content-only refresh (waypoints changed but the route is the
     *  same): skip the camera fit so a background DB emission never yanks the
     *  user's pan/zoom position back to the full-route bounds. */
    refitCamera: Boolean
) {
    map.getStyle { style ->
        val trackSource = style.getSourceAs<GeoJsonSource>(SOURCE_TRACK)
        val lineString = LineString.fromLngLats(track.map { Point.fromLngLat(it.lon, it.lat) })
        if (trackSource != null) {
            trackSource.setGeoJson(lineString)
        } else {
            style.addSource(GeoJsonSource(SOURCE_TRACK, lineString))
            style.addLayer(
                LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                    PropertyFactory.lineColor("#42A5F5"),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")
                )
            )
        }

        val features = waypoints.map { wp ->
            Feature.fromGeometry(Point.fromLngLat(wp.lon, wp.lat)).apply {
                addStringProperty(PROP_WP_NAME, wp.name)
                addNumberProperty(PROP_WP_LAT, wp.lat)
                addNumberProperty(PROP_WP_LON, wp.lon)
                wp.elevationM?.let { addNumberProperty(PROP_WP_ELEVATION, it) }
                wp.description?.let { addStringProperty(PROP_WP_DESCRIPTION, it) }
            }
        }
        // Register the marker images this content needs BEFORE rebuilding
        // the waypoint layers. The shared fast path SKIPS setStyle entirely,
        // so anything registered only inside the style callback may never
        // have run for the style currently on screen (this screen adopting
        // the shared instance from another, or the layers rebuilt here right
        // after a basemap switch). A missing iconImage bitmap = MapLibre
        // silently draws no symbol at all — the "waypoint gak muncul di
        // Route Viewer" bug: every v7-merged GPX waypoint lives in the DB
        // layer, whose pin bitmaps were only ever registered in the full
        // setStyle path that the shared fast path bypasses.
        if (style.getImage("nyasar-marker") == null) {
            style.addImage("nyasar-marker", userWaypointMarkerBitmap(android.graphics.Color.parseColor("#42A5F5")))
        }
        com.nyasar.app.data.db.WaypointCategory.entries.forEach { cat ->
            val imageName = "nyasar-uwp-${cat.name}"
            if (style.getImage(imageName) == null) {
                style.addImage(imageName, userWaypointMarkerBitmap(cat.color.toArgb()))
            }
        }
        // REUSE the source/layer — never remove/recreate here. MapLibre batches
        // style mutations; re-adding an id that is still queued for removal
        // throws ("source id already exists") and kills the REST of this
        // getStyle callback silently — the waypoint layers then stay whatever
        // the last full load left them (often EMPTY), while the camera fit in
        // the mapView.post below still runs. Exactly the "rute biru muncul,
        // pin tidak" symptom. setGeoJson on the existing source is the same
        // proven pattern the track block above uses (the track line always
        // rendered on the shared map — the pins didn't).
        val gpxSource = style.getSourceAs<GeoJsonSource>(SOURCE_WAYPOINTS)
        if (gpxSource != null) {
            gpxSource.setGeoJson(FeatureCollection.fromFeatures(features))
        } else {
            style.addSource(GeoJsonSource(SOURCE_WAYPOINTS, FeatureCollection.fromFeatures(features)))
        }
        if (style.getLayer(LAYER_WAYPOINTS) == null) {
            // Same icon/label split as the full-load path above: a text
            // property drags a glyphs-endpoint requirement onto the whole
            // symbol layer, which is exactly how glyph-less inline raster
            // basemaps used to reject pins wholesale (icon included).
            style.addLayer(
                SymbolLayer(LAYER_WAYPOINTS, SOURCE_WAYPOINTS).withProperties(
                    PropertyFactory.iconImage("nyasar-marker"),
                    PropertyFactory.iconAllowOverlap(true)
                )
            )
            addWaypointLabelLayer(style, LAYER_LABEL_WAYPOINTS, SOURCE_WAYPOINTS, LAYER_WAYPOINTS)
        }

        val userWpFeatures = userWaypoints.map { wp ->
            Feature.fromGeometry(Point.fromLngLat(wp.lon, wp.lat)).apply {
                addStringProperty(PROP_UWP_ID, wp.id)
                addStringProperty(PROP_WP_NAME, wp.name)
                addStringProperty(PROP_UWP_CATEGORY, wp.category)
            }
        }
        // Same reuse rule as the GPX block above. This is the layer that
        // renders every v7-merged DB waypoint in Route Viewer, so a throw
        // here was the remaining "pin gak muncul" path in shared mode:
        // remove+re-add against a removal still queued killed this callback
        // after the source swap but before the layer came back.
        val userWpSource = style.getSourceAs<GeoJsonSource>(SOURCE_USER_WAYPOINTS)
        if (userWpSource != null) {
            userWpSource.setGeoJson(FeatureCollection.fromFeatures(userWpFeatures))
        } else {
            style.addSource(GeoJsonSource(SOURCE_USER_WAYPOINTS, FeatureCollection.fromFeatures(userWpFeatures)))
        }
        if (style.getLayer(LAYER_USER_WAYPOINTS) == null) {
            val iconMatchStops = com.nyasar.app.data.db.WaypointCategory.entries.flatMap { cat ->
                listOf(
                    org.maplibre.android.style.expressions.Expression.literal(cat.name),
                    org.maplibre.android.style.expressions.Expression.literal("nyasar-uwp-${cat.name}")
                )
            }.toTypedArray()
            // Icon/label split — see addWaypointLabelLayer and the full-load
            // path above: icon-only pin layers carry no glyph dependency.
            style.addLayer(
                SymbolLayer(LAYER_USER_WAYPOINTS, SOURCE_USER_WAYPOINTS).withProperties(
                    PropertyFactory.iconImage(
                        org.maplibre.android.style.expressions.Expression.match(
                            org.maplibre.android.style.expressions.Expression.get(PROP_UWP_CATEGORY),
                            org.maplibre.android.style.expressions.Expression.literal("nyasar-uwp-${com.nyasar.app.data.db.WaypointCategory.CUSTOM.name}"),
                            *iconMatchStops
                        )
                    ),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconSize(1f)
                )
            )
            addWaypointLabelLayer(style, LAYER_LABEL_USER_WAYPOINTS, SOURCE_USER_WAYPOINTS, LAYER_USER_WAYPOINTS)
        }

        // Same layout-timing rule as the full-load path: defer the camera fit
        // until the view has final dimensions (newLatLngBounds needs real
        // width/height or the map renders "penyet").
        if (!refitCamera) return@getStyle
        mapView.post {
            if (track.isNotEmpty()) {
                val bounds = boundsOf(track)
                val hasRealSpan = bounds.latitudeSpan > 0.0005 || bounds.longitudeSpan > 0.0005
                if (hasRealSpan) {
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                } else {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.center, 17.5))
                }
            } else if (focusBounds != null) {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(focusBounds, 40))
            }
        }
    }
}

/** One tap-resolution routine used by BOTH the shared physical listener and
 *  the private per-instance listener, so waypoint-hit behavior can never
 *  drift between the two modes. Returns the listener result (true = consumed). */
private fun handleMapTap(
    map: MapLibreMap,
    point: LatLng,
    screenPoint: android.graphics.PointF,
    handlers: SharedMapHolder.TapHandlers
): Boolean {
    // Include the label layers in hit-testing: a tap on the label text must
    // behave exactly like a tap on its pin (they're separate layers now).
    val gpxHits = map.queryRenderedFeatures(screenPoint, LAYER_WAYPOINTS, LAYER_LABEL_WAYPOINTS)
    val gpxHit = gpxHits.firstOrNull()
    if (gpxHit != null) {
        val name = gpxHit.getStringProperty(PROP_WP_NAME)
        val lat = gpxHit.getProperty(PROP_WP_LAT)?.asDouble
        val lon = gpxHit.getProperty(PROP_WP_LON)?.asDouble
        if (name != null && lat != null && lon != null) {
            val ele = gpxHit.getProperty(PROP_WP_ELEVATION)?.asDouble
            val desc = gpxHit.getProperty(PROP_WP_DESCRIPTION)?.asString
            handlers.onWaypointClick(GpxWaypoint(name = name, lat = lat, lon = lon, elevationM = ele, description = desc))
            return true
        }
    }

    val userHits = map.queryRenderedFeatures(screenPoint, LAYER_USER_WAYPOINTS, LAYER_LABEL_USER_WAYPOINTS)
    val userHit = userHits.firstOrNull()
    if (userHit != null) {
        val id = userHit.getStringProperty(PROP_UWP_ID)
        if (id != null) {
            handlers.onUserWaypointClick(id)
            return true
        }
    }

    handlers.onMapClick(point.latitude, point.longitude)
    return false
}

private fun boundsOf(points: List<TrackPoint>): org.maplibre.android.geometry.LatLngBounds {
    val builder = org.maplibre.android.geometry.LatLngBounds.Builder()
    points.forEach { builder.include(LatLng(it.lat, it.lon)) }
    return builder.build()
}

/**
 * A real geographic circle (not a fixed-pixel-radius decoration) approximated
 * as a 32-sided polygon around [center] with the given [radiusMeters] —
 * spec P3A GPS UX: "accuracy circle". Equirectangular offset is accurate
 * enough at accuracy-circle scale (tens of meters) and avoids pulling in a
 * geodesy library for something this small.
 */
private fun geoCircle(center: LatLng, radiusMeters: Double): FeatureCollection {
    val points = 32
    val earthRadius = 6371000.0
    val latRad = Math.toRadians(center.latitude)
    val ring = (0..points).map { i ->
        val angle = 2.0 * Math.PI * i / points
        val dLat = (radiusMeters * Math.cos(angle)) / earthRadius
        val dLon = (radiusMeters * Math.sin(angle)) / (earthRadius * Math.cos(latRad))
        Point.fromLngLat(
            center.longitude + Math.toDegrees(dLon),
            center.latitude + Math.toDegrees(dLat)
        )
    }
    return FeatureCollection.fromFeatures(
        arrayOf(Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(listOf(ring))))
    )
}

/**
 * Small filled pin (circle + point) in the given category color, generated
 * in code like [headingArrowBitmap] so no per-category drawable resources
 * need to be kept in sync manually.
 */
private fun userWaypointMarkerBitmap(colorArgb: Int): android.graphics.Bitmap {
    val size = 36
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        style = android.graphics.Paint.Style.FILL
    }
    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    val cx = size / 2f
    val cy = size * 0.38f
    val r = size * 0.32f

    // Small triangular "tail" under the circle, drawn first so the circle
    // sits cleanly on top of it (a simple pin silhouette without needing a
    // single self-intersecting path).
    val tail = android.graphics.Path().apply {
        moveTo(cx, size * 0.95f)
        lineTo(cx - r * 0.6f, cy + r * 0.6f)
        lineTo(cx + r * 0.6f, cy + r * 0.6f)
        close()
    }
    canvas.drawPath(tail, fillPaint)
    canvas.drawCircle(cx, cy, r, fillPaint)
    canvas.drawCircle(cx, cy, r, strokePaint)
    return bitmap
}

/**
 * Small solid triangle pointing "up" (north) at rotation 0 — iconRotate then
 * turns it to match GPS bearing. Generated in code rather than as a drawable
 * resource so the heading indicator needs no separate asset to keep in sync.
 */
private fun headingArrowBitmap(): android.graphics.Bitmap {
    val size = 28
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#2979FF")
        style = android.graphics.Paint.Style.FILL
    }
    val path = android.graphics.Path().apply {
        moveTo(size / 2f, 0f)
        lineTo(size * 0.85f, size * 0.9f)
        lineTo(size / 2f, size * 0.65f)
        lineTo(size * 0.15f, size * 0.9f)
        close()
    }
    canvas.drawPath(path, paint)
    return bitmap
}
