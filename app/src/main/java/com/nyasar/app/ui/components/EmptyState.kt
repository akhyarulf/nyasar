package com.nyasar.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nyasar.app.ui.theme.NyasarRadius

/**
 * Reusable friendly empty state (icon in a soft tinted circle + title +
 * optional description + optional CTA), replacing the bare one-line
 * "Belum ada X" Text labels screens used to show. Kept presentation-only —
 * every screen passes its own strings/icon/callback so localization and
 * navigation stay where they already live.
 *
 * Now animates in with the shared [AnimatedAppear] (fade + rise, staggered
 * by the caller where several empty states could appear together) and uses
 * the shared radius/spacing tokens so every empty state has the exact same
 * look in every screen.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    ctaText: String? = null,
    onCtaClick: (() -> Unit)? = null
) {
    AnimatedAppear(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (ctaText != null && onCtaClick != null) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onCtaClick,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(NyasarRadius.sm),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(ctaText)
                }
            }
        }
    }
}
