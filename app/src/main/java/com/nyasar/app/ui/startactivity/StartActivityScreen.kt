package com.nyasar.app.ui.startactivity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Task 7: the missing "confirm before recording starts" step. Opening the
 * recording/navigation screens directly used to double as "start now" —
 * this screen is the explicit checkpoint in between, so navigating here
 * is never itself the start signal.
 *
 * routeName == null means the route-less entry point (Home's "Mulai
 * Recording" FAB): navigation isn't offered at all, recording is the only
 * option and is always on (spec: no route -> Navigation unavailable).
 * routeName != null means entry came from Route Preview: both toggles are
 * shown, Recording and Navigation both default ON, and the user can turn
 * off either one independently — never mutually exclusive.
 *
 * Visual redesign (reference: Strava's activity-picker sheet) — dark
 * bottom-sheet-style layout with mode pills instead of the previous plain
 * Switch rows, big centered play button as the primary action. The
 * underlying toggle logic (recordingEnabled/navigationEnabled, same
 * mutual-independence rules) is unchanged — only presentation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartActivityScreen(
    routeName: String? = null,
    onBack: () -> Unit,
    onStart: (recordingEnabled: Boolean, navigationEnabled: Boolean) -> Unit
) {
    var recordingEnabled by remember { mutableStateOf(true) }
    var navigationEnabled by remember { mutableStateOf(routeName != null) }
    val canStart = recordingEnabled || navigationEnabled

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF16181A))
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                routeName ?: "Tanpa route",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (routeName != null) "Route dipilih" else "Recording bebas, tidak mengikuti track manapun.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(24.dp))

            // Mode pills (reference: "Trail Run" / "Add Route" card row).
            // Route-less flow only shows Record (navigation genuinely has
            // nothing to navigate without a route); route flow shows both,
            // independently toggleable.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ModePill(
                    icon = Icons.Default.Hiking,
                    label = "Record",
                    selected = recordingEnabled,
                    onClick = { recordingEnabled = !recordingEnabled }
                )
                if (routeName != null) {
                    ModePill(
                        icon = Icons.Default.Route,
                        label = "Navigation",
                        selected = navigationEnabled,
                        onClick = { navigationEnabled = !navigationEnabled }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                // Big centered play button (reference design) — disabled
                // state (both toggles off) dims rather than disappears, so
                // the "why can't I start" affordance stays visible instead
                // of the button just vanishing.
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(
                            if (canStart) Color(0xFFFC5200) else Color(0xFFFC5200).copy(alpha = 0.3f)
                        )
                        .clickable(enabled = canStart) { onStart(recordingEnabled, navigationEnabled) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Mulai",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            if (!canStart) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Aktifkan Record atau Navigation untuk mulai.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ModePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFFFC5200).copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) Color(0xFFFC5200) else Color.Transparent,
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) Color(0xFFFC5200) else Color.White.copy(alpha = 0.5f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.5f)
        )
    }
}
