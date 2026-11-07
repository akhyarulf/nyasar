package com.nyasar.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nyasar.app.ui.theme.NyasarRadius
import com.nyasar.app.ui.theme.WarningAmber

/**
 * Dialog "Mode Hemat Baterai" — sengaja BUKAN [androidx.compose.material3.AlertDialog].
 *
 * AlertDialog M3 memaksa judul/isi/aksi berjarak seragam dengan tombol yang
 * tingginya sama; referensi (Strava) memakai kartu mengambang dengan judul
 * tebal besar, isi yang mengalir, dan dua aksi teks rata kanan di bawah —
 * anatomi itu yang ditiru di sini, tapi warnanya tetap token Nyasar supaya
 * tidak terasa seperti dialog milik aplikasi lain: kartu
 * surfaceContainerHigh, badge ikon baterai bernada amber sebagai jangkar
 * visual, aksi utama memakai warna primary.
 *
 * Dipakai saat user menekan Start sementara mode hemat daya HP aktif —
 * Android membatasi pembaruan lokasi di mode itu, jadi jejak GPS bisa
 * bolong. "Buka pengaturan" membawa user ke halaman sistem yang tepat;
 * "Batal" membatalkan percobaan mulai (bukan memulai diam-diam).
 */
@Composable
fun BatterySaverDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        // Lebar kartu ditentukan token kita sendiri (lihat modifier di bawah),
        // bukan lebar default platform yang sempit dan tidak konsisten
        // antara orientasi portrait/landscape.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(NyasarRadius.xl),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                Surface(
                    shape = RoundedCornerShape(NyasarRadius.sm),
                    color = WarningAmber.copy(alpha = 0.16f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(dismissText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onConfirm) {
                        Text(confirmText, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
