package com.nyasar.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nyasar.app.R
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap

/**
 * P3 gap fix: +/- zoom buttons. Spec explicitly calls these out as missing
 * ("🔴/🟡 Zoom + button, Zoom - button") separately from pinch-to-zoom,
 * which already works natively through MapLibre's own gesture handling
 * (see NyasarMapView's comment on why gestures are never intercepted by a
 * Compose overlay). This composable does NOT touch gestures — it just
 * calls MapLibreMap.animateCamera with a zoom delta, the same mechanism
 * pinch already drives, so both stay in sync automatically.
 *
 * map is nullable because it's only available once NyasarMapView's
 * onMapReady fires; callers hold it in a `remember { mutableStateOf<...>
 * (null) }` populated by that callback. Buttons are always shown, just a
 * no-op until the map finishes loading, rather than appearing/disappearing
 * (spec: consistent placement, not something that pops in).
 */
@Composable
fun ZoomControls(
    map: MapLibreMap?,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(shape = CircleShape, tonalElevation = 3.dp, shadowElevation = 2.dp, modifier = Modifier.size(44.dp)) {
            IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.zoom_in_cd))
            }
        }
        Surface(shape = CircleShape, tonalElevation = 3.dp, shadowElevation = 2.dp, modifier = Modifier.size(44.dp)) {
            IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.zoom_out_cd))
            }
        }
    }
}
