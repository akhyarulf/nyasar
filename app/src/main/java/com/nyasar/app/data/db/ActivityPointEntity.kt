package com.nyasar.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Satu titik GPS yang direkam selama activity berjalan. Ditulis per fix
 * (bukan dikumpulkan di memori lalu ditulis sekali di akhir) supaya
 * crash atau baterai habis di tengah hike tidak menghilangkan seluruh
 * recording — yang hilang hanya titik-titik setelah insert terakhir yang
 * berhasil.
 */
@Entity(tableName = "activity_points")
data class ActivityPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: String,
    /** Urutan eksplisit saat insert. GPS timestamp kadang jitter/tidak
     *  strictly increasing saat sinyal lemah di outdoor, jadi urutan insert
     *  lebih bisa diandalkan untuk redraw track daripada sort by timestamp. */
    val sequence: Int,
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
    val speedMps: Float?,
    val accuracyMeters: Float,
    val timestampMs: Long
)
