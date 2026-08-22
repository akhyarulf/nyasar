package com.nyasar.app.ui.waypoint

import androidx.compose.foundation.layout.*
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
import com.nyasar.app.map.providers.TileProviderFactory
import com.nyasar.app.ui.components.NyasarMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Waypoint selection screen with crosshair in center of map.
 * 
 * Flow:
 * 1. User taps Waypoint button
 * 2. Crosshair appears in center of map
 * 3. User pans/zooms map to desired location
 * 4. Crosshair stays in center
 * 5. Coordinates update as map moves
 * 6. User taps Confirm to save waypoint
 * 
 * UX similar to download area selection, but for a single point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointCrosshairScreen(
    initialLatLng: LatLng? = null,
    onSave: (lat: Double, lon: Double, name: String, category: WaypointCategory) -> Unit,
    onDismiss: () -> Unit
) {
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var currentCenter by remember { mutableStateOf(initialLatLng ?: LatLng(0.0, 0.0)) }
    var waypointName by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(WaypointCategory.POI) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    
    val provider = remember { TileProviderFactory.default() }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pilih Lokasi Waypoint") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Batal")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (currentCenter.latitude != 0.0 || currentCenter.longitude != 0.0) {
                                onSave(
                                    currentCenter.latitude,
                                    currentCenter.longitude,
                                    waypointName.ifBlank { selectedCategory.label },
                                    selectedCategory
                                )
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Simpan",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Map with crosshair
            NyasarMapView(
                modifier = Modifier.fillMaxSize(),
                provider = provider,
                track = emptyList(),
                userLocation = null,
                followUser = false,
                onMapReady = { map ->
                    mapInstance = map
                    // Set initial position if provided
                    initialLatLng?.let { latLng ->
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15.0))
                    }
                },
                onMapClick = { lat, lon ->
                    // Update center position when user taps on map
                    currentCenter = LatLng(lat, lon)
                }
            )
            
            // Crosshair in center
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                // Crosshair icon
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // Cross lines
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
            
            // Bottom info panel
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Coordinates display
                    Text(
                        "Koordinat",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%.6f, %.6f".format(currentCenter.latitude, currentCenter.longitude),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Waypoint name input
                    OutlinedTextField(
                        value = waypointName,
                        onValueChange = { waypointName = it },
                        label = { Text("Nama Waypoint (opsional)") },
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
                            Text("Kategori: ${selectedCategory.label}")
                        }
                        
                        DropdownMenu(
                            expanded = showCategoryMenu,
                            onDismissRequest = { showCategoryMenu = false }
                        ) {
                            WaypointCategory.entries.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.label) },
                                    onClick = {
                                        selectedCategory = category
                                        showCategoryMenu = false
                                    }
                                )
                            }
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
                            Text("Batal")
                        }
                        
                        Button(
                            onClick = {
                                if (currentCenter.latitude != 0.0 || currentCenter.longitude != 0.0) {
                                    onSave(
                                        currentCenter.latitude,
                                        currentCenter.longitude,
                                        waypointName.ifBlank { selectedCategory.label },
                                        selectedCategory
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Simpan")
                        }
                    }
                }
            }
        }
    }
}