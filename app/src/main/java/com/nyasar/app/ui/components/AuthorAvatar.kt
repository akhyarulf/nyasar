package com.nyasar.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person

/**
 * The one identity chip in the app: a filled circle carrying the person's
 * initial, falling back to a person glyph when there is no name yet.
 *
 * Why initials instead of a photo: Nyasar has no avatar uploads (profiles only
 * store a username), so any "photo" would be a stand-in anyway. A tinted
 * circle with the real initial at least looks deliberate — the old grey square
 * with a generic person icon read as a broken placeholder, especially when
 * several cards in a row showed the identical glyph.
 *
 * Same treatment as the Profile header, so a publisher looks the same on the
 * feed, on the route detail, and on your own profile.
 */
@Composable
fun AuthorAvatar(
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val initial = name
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(1)
        ?.uppercase()

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (initial != null) {
                Text(
                    text = initial,
                    // One step up from the body scale at 40dp, more at 56dp —
                    // a single letter needs to look like a monogram, not a
                    // label that happens to be one character long.
                    style = if (size >= 48.dp) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(size * 0.55f)
                )
            }
        }
    }
}
