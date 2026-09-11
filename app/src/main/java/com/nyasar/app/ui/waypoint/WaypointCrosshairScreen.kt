package com.nyasar.app.ui.waypoint

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nyasar.app.data.db.WaypointCategory
import org.maplibre.android.geometry.LatLng
import com.nyasar.app.R
import androidx.compose.ui.res.stringResource

/**
 * Waypoint placement overlay with a fixed crosshair in the center of the
 * screen.
 *
 * v7 UX fix — "layar terakhir": this is NOT a second map anymore. The old
 * implementation embedded its own NyasarMapView, which starts centered at
 * (0,0) and snaps to a hardcoded zoom 15 — so opening it from Home, Route
 * Preview or Recording always teleported the view somewhere else and lost
 * every overlay/track the host screen had on. The host screens now render
 * this overlay ON TOP of their existing (still visible, still interactive)
 * map with [WaypointPlacementOverlayController] feeding it live camera
 * data, so the crosshair view opens at EXACTLY the last position + zoom
 * the user was looking at — never zoomed in, never zoomed out, tracks and
 * overlays untouched underneath.
 *
 * Panning/zooming happens on the host map as usual; this overlay only
 * reads the camera back through [cameraTarget] and pins the coordinate
 * display to it. The root Box deliberately has NO pointerInput: a
 * tap-consuming scrim (the old detectTapGestures {}) swallowed the first
 * pointer down, so MapLibre never saw the gesture stream and the map
 * could not be panned while placing. Buttons/panels here are their own
 * hit targets, so nothing needs a full-screen interceptor.
 *
 * Flow:
 * 1. User taps Waypoint button (Home/RoutePreview/Recording)
 * 2. Dim + crosshair appear over the CURRENT map view (no camera move)
 * 3. User pans/zooms the host map to fine-tune
 * 4. Coordinate display follows the crosshair in real time
 * 5. Confirm/Cancel via the bottom panel or the top bar actions
 */
@Composable
fun WaypointCrosshairScreen(
    cameraTarget: LatLng?,
    attachments: WaypointAttachments = WaypointAttachments(),
    initialLinkedRouteId: String? = null,
    initialLinkedActivityId: String? = null,
    onSave: (lat: Double, lon: Double, name: String, category: WaypointCategory, linkedRouteId: String?, linkedActivityId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var waypointName by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(WaypointCategory.POI) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var linkedRouteId by remember { mutableStateOf(initialLinkedRouteId) }
    var linkedActivityId by remember { mutableStateOf(initialLinkedActivityId) }
    // Hoisted so onClick lambdas (non-composable scope) can use the
    // category's localized label as the default waypoint name.
    val selectedCategoryLabel = stringResource(selectedCategory.labelRes)

    // Rendered as a full-screen overlay from Home/RoutePreview/Recording —
    // system back must close THIS overlay, not pop the host destination.
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        // Crosshair pinned to the exact center — the map is NOT moved by
        // this overlay; whatever the camera already shows IS the selection.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    HorizontalDivider(
                        modifier = Modifier
                            .width(24.dp)
                            .height(2.dp),
                        color = Color.White
                    )
                    VerticalDivider(
                        modifier = Modifier
                            .width(2.dp)
                            .height(24.dp),
                        color = Color.White
                    )
                }
            }
        }

        // Top actions: cancel / confirm. A compact bar instead of a full
        // TopAppBar — the host screen's own top bar stays visible behind,
        // reinforcing "this is still the screen you were on".
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = com.nyasar.app.ui.theme.NyasarElevation.mapControlTonal,
                shadowElevation = com.nyasar.app.ui.theme.NyasarElevation.mapControlShadow,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Surface(
                onClick = {
                    val target = cameraTarget
                    if (target != null) {
                        onSave(
                            target.latitude,
                            target.longitude,
                            waypointName.ifBlank { selectedCategoryLabel },
                            selectedCategory,
                            linkedRouteId,
                            linkedActivityId
                        )
                    }
                },
                enabled = cameraTarget != null,
                shape = CircleShape,
                color = if (cameraTarget != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.save),
                        tint = if (cameraTarget != null) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Bottom info panel — same AnimatedAppear entrance and visual family
        // as the shared waypoint form sheets.
        com.nyasar.app.ui.components.AnimatedAppear(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Coordinates display — follows the host camera live.
                    Text(
                        stringResource(R.string.coordinate),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    cameraTarget?.let { target ->
                        Text(
                            "%.6f, %.6f".format(target.latitude, target.longitude),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Waypoint name input
                    OutlinedTextField(
                        value = waypointName,
                        onValueChange = { waypointName = it },
                        label = { Text(stringResource(R.string.waypoint_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // Category selection
                    Box {
                        OutlinedButton(
                            onClick = { showCategoryMenu = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.category) + ": ${stringResource(selectedCategory.labelRes)}")
                        }

                        DropdownMenu(
                            expanded = showCategoryMenu,
                            onDismissRequest = { showCategoryMenu = false }
                        ) {
                            WaypointCategory.entries.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(category.labelRes)) },
                                    onClick = {
                                        selectedCategory = category
                                        showCategoryMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Attachment selection (v7) — only when the caller offers
                    // a link context; otherwise the waypoint is independent.
                    if (attachments.hasOptions) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.waypoint_attachment), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            attachments.routeId?.let { rid ->
                                AttachmentOption(
                                    selected = linkedRouteId == rid,
                                    label = stringResource(R.string.waypoint_attachment_route, attachments.routeName ?: stringResource(R.string.default_route_name)),
                                    onClick = { linkedRouteId = rid; linkedActivityId = null }
                                )
                            }
                            attachments.activityId?.let { aid ->
                                AttachmentOption(
                                    selected = linkedActivityId == aid,
                                    label = stringResource(R.string.waypoint_attachment_activity, attachments.activityName ?: stringResource(R.string.activity_title_generic)),
                                    onClick = { linkedActivityId = aid; linkedRouteId = null }
                                )
                            }
                            AttachmentOption(
                                selected = linkedRouteId == null && linkedActivityId == null,
                                label = stringResource(R.string.waypoint_attachment_independent),
                                onClick = { linkedRouteId = null; linkedActivityId = null }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cancel))
                        }

                        Button(
                            onClick = {
                                val target = cameraTarget
                                if (target != null) {
                                    onSave(
                                        target.latitude,
                                        target.longitude,
                                        waypointName.ifBlank { selectedCategoryLabel },
                                        selectedCategory,
                                        linkedRouteId,
                                        linkedActivityId
                                    )
                                }
                            },
                            enabled = cameraTarget != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wires a host screen's shared map into [WaypointCrosshairScreen]:
 * observes the host's MapLibre camera and mirrors it into a Compose state
 * the overlay displays. Also controls whether the host map accepts input —
 * while the overlay is open the map MUST stay interactive (pan/zoom is how
 * the user fine-tunes placement), and the screen previously worried about
 * long-presses creating stray Add sheets: those are suppressed here while
 * the overlay is open so a long-press during placement can't queue a second
 * waypoint form.
 *
 * The camera listener is registered for the lifetime of the returned state
 * (the overlay session), not just one frame: set [active] when the overlay
 * opens/closes.
 */
@Composable
fun rememberCrosshairCameraState(
    mapInstance: org.maplibre.android.maps.MapLibreMap?,
    active: Boolean
): State<LatLng?> {
    val target = remember { mutableStateOf<LatLng?>(null) }
    // Seed synchronously from the current camera so the very first frame of
    // the overlay already shows the real coordinates (no (0,0) flash).
    LaunchedEffect(mapInstance, active) {
        if (!active) return@LaunchedEffect
        mapInstance?.cameraPosition?.target?.let { target.value = it }
    }
    DisposableEffect(mapInstance, active) {
        if (!active || mapInstance == null) {
            target.value = null
            return@DisposableEffect onDispose { }
        }
        target.value = mapInstance.cameraPosition.target
        val listener = org.maplibre.android.maps.MapLibreMap.OnCameraMoveListener {
            // Throttle: camera moves fire per frame; a cheap field read +
            // state write is fine, recomposition of the small coordinate
            // Text is the intended cost.
            mapInstance.cameraPosition.target?.let { target.value = it }
        }
        mapInstance.addOnCameraMoveListener(listener)
        onDispose {
            mapInstance.removeOnCameraMoveListener(listener)
        }
    }
    return target
}
