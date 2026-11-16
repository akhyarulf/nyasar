package com.nyasar.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nyasar.app.ui.theme.NyasarControl
import com.nyasar.app.ui.theme.NyasarElevation
import com.nyasar.app.ui.theme.NyasarRadius

/**
 * Kontrol peta sekunder sebagai SATU kontainer, bukan deretan lingkaran
 * terpisah.
 *
 * Alasan desain: empat lingkaran mengambang yang masing-masing punya shadow
 * sendiri membuat sisi kanan peta terbaca sebagai empat tombol acak dengan
 * bobot sama. Mengelompokkannya dalam satu permukaan membulat memberi
 * hierarki yang jelas (satu objek, empat aksi), memangkas bayangan dari
 * empat menjadi satu, dan menyisakan satu bentuk yang kuat di atas peta —
 * pola yang dipakai Strava dan Apple Maps.
 *
 * Setiap item tetap memakai target sentuh 48dp dari [NyasarControl], jadi
 * kepadatan visualnya bertambah tanpa mengorbankan sasaran jari.
 */
@Composable
fun MapControlCluster(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NyasarRadius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = NyasarElevation.mapControlTonal,
        shadowElevation = NyasarElevation.mapControlShadow,
        border = BorderStroke(
            NyasarControl.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(content = content)
    }
}

/** Satu aksi di dalam [MapControlCluster] — rata, tanpa shadow sendiri. */
@Composable
fun MapControlItem(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(NyasarControl.mapButton)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Pemisah rambut antar aksi — diberi inset supaya ikut lengkung kontainer
 *  dan tidak menyentuh tepinya. */
@Composable
fun MapControlDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 10.dp),
        thickness = NyasarControl.hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}
