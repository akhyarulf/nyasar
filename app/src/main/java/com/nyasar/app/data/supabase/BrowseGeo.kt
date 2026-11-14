package com.nyasar.app.data.supabase

import kotlin.math.cos

/**
 * Kotak geografis untuk "Dekat saya" / "area peta" di Jelajah.
 *
 * Server-side filter-nya sederhana: `start_lat BETWEEN min..max` dan
 * `start_lng BETWEEN min..max` pada titik AWAL rute (kolom `start_lat` /
 * `start_lng` yang sudah diisi saat publish). Tidak perlu PostGIS untuk
 * skala ini, dan kotak lebih longgar sedikit dari lingkaran radius — cukup
 * untuk pertanyaan "gunung apa yang dekat saya".
 *
 * Batas [MAX_SPAN_DEGREES] penting: bbox datang dari klien, jadi tanpa batas
 * seseorang bisa mengirim kotak se-Indonesia dan menarik seluruh tabel dalam
 * satu request. 2° ≈ 220 km utara-selatan, jauh di atas kebutuhan "dekat".
 *
 * Tidak menangani antimeridian (±180°). Rute Indonesia tidak melewatinya;
 * kalau suatu saat dibutuhkan, pecah jadi dua query.
 */
data class BrowseBbox(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double
) {

    /** Tukar sudut yang terbalik dan clamp ke rentang bumi. */
    fun normalized(): BrowseBbox {
        val latLow = minOf(minLat, maxLat).coerceIn(-90.0, 90.0)
        val latHigh = maxOf(minLat, maxLat).coerceIn(-90.0, 90.0)
        val lngLow = minOf(minLng, maxLng).coerceIn(-180.0, 180.0)
        val lngHigh = maxOf(minLng, maxLng).coerceIn(-180.0, 180.0)
        return BrowseBbox(latLow, lngLow, latHigh, lngHigh)
    }

    val latSpanDegrees: Double
        get() = maxLat - minLat

    val lngSpanDegrees: Double
        get() = maxLng - minLng

    /** False = jangan kirim filter bbox sama sekali (lebih baik menampilkan
     *  daftar global daripada query yang tidak masuk akal). */
    val isUsable: Boolean
        get() {
            val n = normalized()
            return n.maxLat > n.minLat &&
                n.maxLng > n.minLng &&
                n.latSpanDegrees <= MAX_SPAN_DEGREES &&
                n.lngSpanDegrees <= MAX_SPAN_DEGREES
        }

    companion object {
        /** Lebar maksimum per sisi, dalam derajat (≈ 220 km). */
        const val MAX_SPAN_DEGREES = 2.0

        /** 1° lintang ≈ 111,32 km (rata-rata bola bumi). */
        const val KM_PER_DEGREE_LAT = 111.32

        /**
         * Kotak di sekitar satu titik. [radiusKm] di-clamp ke 500 km supaya
         * pemanggil tidak bisa tidak sengaja meminta setengah planet.
         */
        fun around(latitude: Double, longitude: Double, radiusKm: Double): BrowseBbox {
            val safeRadius = if (radiusKm.isFinite()) radiusKm.coerceIn(0.0, 500.0) else 0.0
            val halfLat = (safeRadius / KM_PER_DEGREE_LAT).coerceAtMost(MAX_SPAN_DEGREES / 2.0)
            // cos(lat) mengecilkan derajat bujur saat menjauh dari ekuator;
            // di kutub pembagi mendekati nol, jadi beri lantai 0,01.
            val cosLat = cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0)))
                .coerceAtLeast(0.01)
            val halfLng = (safeRadius / (KM_PER_DEGREE_LAT * cosLat))
                .coerceAtMost(MAX_SPAN_DEGREES / 2.0)
            return BrowseBbox(
                minLat = (latitude - halfLat).coerceIn(-90.0, 90.0),
                minLng = (longitude - halfLng).coerceIn(-180.0, 180.0),
                maxLat = (latitude + halfLat).coerceIn(-90.0, 90.0),
                maxLng = (longitude + halfLng).coerceIn(-180.0, 180.0)
            )
        }
    }
}

/**
 * Pagination PostgREST. `range(from, to)` inklusif di kedua ujung, jadi
 * halaman berukuran [limit] berakhir di `offset + limit - 1` — bukan
 * `offset + limit`. Salah satu angka saja bikin satu baris terlewat atau
 * terduplikasi di setiap batas halaman.
 */
object BrowsePaging {
    const val DEFAULT_PAGE_SIZE = 50

    /** Batas atas per request; melindungi dari pemanggil yang meminta 5.000. */
    const val MAX_PAGE_SIZE = 100

    fun page(offset: Int, limit: Int): LongRange {
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        return safeOffset.toLong()..(safeOffset + safeLimit - 1).toLong()
    }
}
