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
private const val SOURCE_USER = "nyasar-user-source"
private const val SOURCE_ACCURACY = "nyasar-accuracy-source"
private const val LAYER_ACCURACY_FILL = "nyasar-accuracy-fill-layer"
private const val LAYER_USER_HALO = "nyasar-user-halo-layer"
private const val LAYER_USER_DOT = "nyasar-user-dot-layer"
private const val LAYER_USER_HEADING = "nyasar-user-heading-layer"
private const val SOURCE_OFFLINE_COVERAGE = "nyasar-offline-coverage-source"
private const val LAYER_OFFLINE_COVERAGE_FILL = "nyasar-offline-coverage-fill-layer"
private const val LAYER_OFFLINE_COVERAGE_OUTLINE = "nyasar-offline-coverage-outline-layer"
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
     *  [TileProvider] abstraction — no provider-specific branching here. */
    styleVariant: StyleVariant = StyleVariant.OUTDOOR,
    track: List<TrackPoint>,
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
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mapView = remember(context) { MapView(context) }

    DisposableEffect(mapView) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // focusBounds intentionally NOT a key here. OfflineDownloadScreen feeds
    // this from state that it itself updates on every camera-idle event
    // (recomputeBoundsFromViewport) — if focusBounds re-triggered this
    // effect, every idle would re-run moveCamera below, which triggers
    // another idle, which re-triggers this effect again: an infinite
    // camera feedback loop (the "download ngezoom terus" issue). The very
    // first composition's focusBounds value is applied once inside this
    // effect and never again after — see the one-shot effect further below
    // for handling subsequent focusBounds changes intentionally.
    LaunchedEffect(provider.id, styleVariant, track, waypoints, userWaypoints) {
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
            map.setStyle(provider.styleUrl(styleVariant)) { style ->
                if (style.getImage("nyasar-heading-arrow") == null) {
                    style.addImage("nyasar-heading-arrow", headingArrowBitmap())
                }
                // Track line
                val lineString = LineString.fromLngLats(track.map { Point.fromLngLat(it.lon, it.lat) })
                val trackSource = style.getSourceAs<GeoJsonSource>(SOURCE_TRACK)
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
                            PropertyFactory.lineColor("#00C853"),
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
                val features = waypoints.map { wp ->
                    Feature.fromGeometry(Point.fromLngLat(wp.lon, wp.lat)).apply {
                        addStringProperty(PROP_WP_NAME, wp.name)
                        addNumberProperty(PROP_WP_LAT, wp.lat)
                        addNumberProperty(PROP_WP_LON, wp.lon)
                        wp.elevationM?.let { addNumberProperty(PROP_WP_ELEVATION, it) }
                        wp.description?.let { addStringProperty(PROP_WP_DESCRIPTION, it) }
                    }
                }
                val wpSource = style.getSourceAs<GeoJsonSource>(SOURCE_WAYPOINTS)
                if (wpSource != null) {
                    wpSource.setGeoJson(FeatureCollection.fromFeatures(features))
                } else {
                    style.addSource(GeoJsonSource(SOURCE_WAYPOINTS, FeatureCollection.fromFeatures(features)))
                    style.addLayer(
                        SymbolLayer(LAYER_WAYPOINTS, SOURCE_WAYPOINTS).withProperties(
                            PropertyFactory.iconImage("marker-15"),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.textField("{$PROP_WP_NAME}"),
                            PropertyFactory.textSize(11f),
                            PropertyFactory.textOffset(arrayOf(0f, 1.2f))
                        )
                    )
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
                val userWpFeatures = userWaypoints.map { wp ->
                    Feature.fromGeometry(Point.fromLngLat(wp.lon, wp.lat)).apply {
                        addStringProperty(PROP_UWP_ID, wp.id)
                        addStringProperty(PROP_WP_NAME, wp.name)
                        addStringProperty(PROP_UWP_CATEGORY, wp.category)
                    }
                }
                val userWpSource = style.getSourceAs<GeoJsonSource>(SOURCE_USER_WAYPOINTS)
                if (userWpSource != null) {
                    userWpSource.setGeoJson(FeatureCollection.fromFeatures(userWpFeatures))
                } else {
                    style.addSource(GeoJsonSource(SOURCE_USER_WAYPOINTS, FeatureCollection.fromFeatures(userWpFeatures)))
                    val iconMatchStops = com.nyasar.app.data.db.WaypointCategory.entries.flatMap { cat ->
                        listOf(
                            org.maplibre.android.style.expressions.Expression.literal(cat.name),
                            org.maplibre.android.style.expressions.Expression.literal("nyasar-uwp-${cat.name}")
                        )
                    }.toTypedArray()
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
                            PropertyFactory.iconSize(1f),
                            PropertyFactory.textField("{$PROP_WP_NAME}"),
                            PropertyFactory.textSize(11f),
                            PropertyFactory.textOffset(arrayOf(0f, 1.4f))
                        )
                    )
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

                if (track.isNotEmpty()) {
                    // Bug fix: a track with only one real point (or several
                    // points that all round to the same coordinate — e.g. a
                    // recording whose GPS never actually moved off the
                    // world-view default before the camera bug was fixed)
                    // produces a zero-width/zero-height LatLngBounds.
                    // Asking the camera to fit padding into a bounds with no
                    // span is what actually crashes here — LatLngBounds
                    // itself only throws when NO points were included at
                    // all (already guarded by isNotEmpty() above), so this
                    // was a real, reachable crash the isNotEmpty() check
                    // didn't cover, most likely to hit exactly on a
                    // messed-up short recording's Activity Detail.
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

                // Native gesture detection (spec: "1 jari drag = PAN", "saat
                // user menggeser/zoom manual -> Follow GPS harus OFF"). This
                // is registered on the MapLibreMap itself, not via a Compose
                // pointerInput overlay, so it never steals touch events from
                // MapLibre's own pan/pinch/rotate handling.
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
        if (userLocation == null) return@LaunchedEffect
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
    val currentOnWaypointClick by rememberUpdatedState(onWaypointClick)
    val currentOnUserWaypointClick by rememberUpdatedState(onUserWaypointClick)
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnMapLongPress by rememberUpdatedState(onMapLongPress)

    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                map.addOnMapClickListener { point ->
                    val screenPoint = map.projection.toScreenLocation(point)

                    val gpxHits = map.queryRenderedFeatures(screenPoint, LAYER_WAYPOINTS)
                    val gpxHit = gpxHits.firstOrNull()
                    if (gpxHit != null) {
                        val name = gpxHit.getStringProperty(PROP_WP_NAME)
                        val lat = gpxHit.getProperty(PROP_WP_LAT)?.asDouble
                        val lon = gpxHit.getProperty(PROP_WP_LON)?.asDouble
                        if (name != null && lat != null && lon != null) {
                            val ele = gpxHit.getProperty(PROP_WP_ELEVATION)?.asDouble
                            val desc = gpxHit.getProperty(PROP_WP_DESCRIPTION)?.asString
                            currentOnWaypointClick(GpxWaypoint(name = name, lat = lat, lon = lon, elevationM = ele, description = desc))
                            return@addOnMapClickListener true
                        }
                    }

                    val userHits = map.queryRenderedFeatures(screenPoint, LAYER_USER_WAYPOINTS)
                    val userHit = userHits.firstOrNull()
                    if (userHit != null) {
                        val id = userHit.getStringProperty(PROP_UWP_ID)
                        if (id != null) {
                            currentOnUserWaypointClick(id)
                            return@addOnMapClickListener true
                        }
                    }

                    currentOnMapClick(point.latitude, point.longitude)
                    false
                }

                map.addOnMapLongClickListener { point ->
                    currentOnMapLongPress(point.latitude, point.longitude)
                    true
                }
            }
            mapView
        },
        modifier = modifier
    )
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
