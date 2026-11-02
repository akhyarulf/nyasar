package com.nyasar.app.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nyasar.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Auto-resume rekaman setelah HP reboot (permintaan pengguna, 2026-11):
 * kalau proses Nyasar mati karena HP dimatikan/di-restart di tengah
 * rekaman, receiver ini menyalakan kembali RecordingService begitu boot
 * selesai — pengguna tidak perlu membuka app agar rekaman berlanjut.
 *
 * Flow: OS broadcast ACTION_BOOT_COMPLETED (protected broadcast, hanya
 * sistem yang boleh mengirimnya) -> receiver membaca DB -> jika ada
 * activity berstatus 'recording'/'paused' yang belum basi -> dibangunkan
 * lewat jalur startForegroundService + resumeExistingIntent, persis alur
 * recovery yang sama dengan RecordingViewModel.checkForRecovery() saat
 * buka app (restoreExistingActivity() di RecordingService).
 *
 * Dua catatan desain:
 * 1. Staleness guard 24 jam: BOOT_COMPLETED dikirim pada SETIAP boot.
 *    Tanpa guard, rekaman ACTIVE "yatim" yang tertinggal berhari-hari
 *    (pengguna lupa menutup rekaman) akan dibangunkan kembali pada boot
 *    berikutnya — GPS menyala diam-diam sampai habis baterai. Dengan
 *    guard, rekaman lama tetap utuh datanya dan ditangani jalur recovery
 *    manual saat pengguna benar-benar buka app.
 * 2. Ini TIDAK memulai ulang app (Activity). Android melarang auto-launch
 *    activity dari background (Android 10+); yang legal && standar adalah
 *    memulai ulang foreground service dari receiver boot — pola yang
 *    sama dipakai app tracking pada umumnya.FORCE_STOP dari Settings
 *    tetap memblokir broadcast ini sampai pengguna membuka app sekali
 *    (perilaku OS, bukan bug).
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        /** Batas umur rekaman yang masih layak dihidupkan otomatis saat
         *  boot. 24 jam berada jauh di luar durasi aktivitas outdoor
         *  realistis (hiking seharian pun < 24 jam) tapi juga jauh di
         *  atas jeda reboot/aplikasi-restart yang wajar. */
        private const val MAX_AUTO_RESUME_AGE_MS = 24 * 60 * 60 * 1000L

        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // goAsync: pembacaan Room bersifat suspend — context broadcast hanya
        // dijamin hidup ±10 detik, jadi kita pegang PendingResult sampai
        // query + startForegroundService selesai, lalu finish() eksplisit.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val active = AppDatabase.get(appContext).activityDao().getActiveOrNull()
                if (active != null &&
                    System.currentTimeMillis() - active.startedAtEpochMs <= MAX_AUTO_RESUME_AGE_MS
                ) {
                    ContextCompat.startForegroundService(
                        appContext,
                        RecordingService.resumeExistingIntent(appContext, active.id, active.routeId)
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Auto-resume after boot failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
