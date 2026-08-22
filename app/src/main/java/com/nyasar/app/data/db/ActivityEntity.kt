package com.nyasar.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ringkasan satu activity (recording session). Unit sama dengan RouteEntity
 * (meter, ms, km/h) supaya UI activity detail bisa reuse formatter yang
 * sudah ada untuk RouteRow, dsb.
 *
 * routeId nullable secara sengaja: activity bisa berdiri sendiri (recording
 * tanpa route sama sekali) atau terhubung ke route yang sedang dinavigasi
 * (dipakai untuk "planned vs actual" di Fase 3). Tidak pakai Room foreign
 * key CASCADE — kalau route-nya dihapus nanti, histori activity tidak boleh
 * ikut hilang (spec: activity disimpan lokal, tidak bergantung ke route).
 */
@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey val id: String, // UUID, dibuat saat recording dimulai
    val routeId: String?,
    val name: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?, // null selama masih recording/paused

    /** "recording" | "paused" | "completed" | "discarded" — lihat [ActivityStatus]. */
    val status: String,

    val distanceMeters: Double,
    val movingTimeMs: Long,
    val elapsedTimeMs: Long, // termasuk waktu paused, untuk ditampilkan sebagai durasi total
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val elevationGainM: Double?,
    val elevationLossM: Double?
)

/** Nilai valid untuk [ActivityEntity.status]. Disimpan sebagai String di Room
 *  (bukan enum langsung) supaya migrasi skema di masa depan lebih fleksibel,
 *  konsisten dengan tidak adanya TypeConverter lain di project ini. */
object ActivityStatus {
    const val RECORDING = "recording"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val DISCARDED = "discarded"
}
