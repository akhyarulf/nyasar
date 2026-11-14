package com.nyasar.app.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * ACCESS_BACKGROUND_LOCATION — izin terpisah sejak Android 10 dan prasyarat
 * untuk menghidupkan foreground service bertipe `location` DARI LUAR
 * foreground, mis. [com.nyasar.app.recording.BootCompletedReceiver] setelah
 * HP restart.
 *
 * Kenapa ini kelas tersendiri: izin ini dideklarasikan di manifest sejak
 * awal ("requested lazily"), tapi tidak pernah diminta di kode mana pun.
 * Akibatnya auto-resume setelah reboot selalu kena SecurityException di
 * Android 10+ (ditangkap receiver dan hanya masuk log) — fiturnya ada, tapi
 * diam-diam tidak pernah jalan.
 *
 * Sejak Android 11 izin ini TIDAK bisa diminta lewat dialog runtime: satu-
 * satunya jalur resmi adalah halaman Settings aplikasi (Permissions →
 * Location → "Allow all the time"). Karena itu kelas ini hanya menyediakan
 * cek status + intent ke halaman tersebut; pemanggilnya yang membuka
 * (launcher di UI, supaya hasilnya terbaca saat activity kembali).
 */
object BackgroundLocationPermission {

    /** Di bawah Android 10 izin latar belakang menyatu dengan izin lokasi
     *  biasa, jadi selalu dianggap tersedia. */
    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** Halaman detail aplikasi di Settings — tempat user mengubah izin
     *  lokasi menjadi "Allow all the time". Halaman izin spesifik tidak
     *  punya intent publik yang stabil, jadi app details adalah tujuan
     *  paling andal (pola yang sama dengan fallback baris pengecualian
     *  baterai di Settings). */
    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
}
