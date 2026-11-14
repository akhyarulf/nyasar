package com.nyasar.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Waktu startup aplikasi (cold start) di build non-debuggable.
 *
 * Yang diukur adalah `timeToInitialDisplay` dan `timeToFullDisplay`: milidetik
 * sampai frame pertama tampil, dan sampai aplikasi benar-benar siap dipakai.
 * Ini satu-satunya metrik di proyek ini yang punya angka — bukan tebakan.
 *
 * Jalankan di perangkat/emulator:
 *   gradle :benchmark:connectedBenchmarkAndroidTest
 * Hasilnya ada di `benchmark/build/outputs/connected_android_test_additional_output/`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        // Partial: profil AOT parsial seperti yang dipasang Play Store untuk
        // pengguna sungguhan. Full compilation membuat startup terlihat lebih
        // cepat daripada kenyataan; None membuatnya lebih lambat.
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        }
    ) {
        // Tidak ada interaksi: metrik startup sudah dikumpulkan saat activity
        // tampil. Blok ini sengaja kosong supaya angka yang keluar murni
        // waktu startup, bukan waktu yang tertelan aksi tambahan.
    }

    @Test
    fun warmStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            startActivityAndWait()
            // Warm start = proses masih hidup, activity dibangun ulang.
            device.pressHome()
            device.waitForIdle()
        }
    ) { }

    private companion object {
        // applicationId, bukan namespace: yang diukur adalah APK terpasang.
        const val TARGET_PACKAGE = "com.nyasarnyaman.nyasar"
    }
}
