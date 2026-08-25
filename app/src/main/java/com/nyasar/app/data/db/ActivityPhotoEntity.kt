package com.nyasar.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One photo attached to an [ActivityEntity] (spec P3H). `activityId` is the
 * relationship — not `activityName` (spec §23: "Photo harus terkait dengan
 * activityId, bukan hanya nama Activity", since names can be renamed —
 * see [ActivityDetailViewModel.rename] — without breaking this link). No
 * Room FK/CASCADE, same deliberate choice as [ActivityEntity]/[RouteEntity]
 * in this codebase: deletion is handled explicitly in the repository
 * (ActivityPhotoRepository.deleteAllForActivity), not relied on implicitly.
 *
 * `filePath` is always this app's own private copy — camera captures write
 * directly here, gallery picks are copied in (see PhotoStorageManager) —
 * never a raw content:// Uri into the user's Gallery/MediaStore. That's
 * what makes spec §11/§12 ("delete association ≠ delete original Gallery
 * photo", "jangan overwrite original") true by construction: there is no
 * original in this table to accidentally touch.
 */
@Entity(tableName = "activity_photos")
data class ActivityPhotoEntity(
    @PrimaryKey val id: String, // UUID
    val activityId: String,
    val filePath: String, // absolute path, this app's private storage
    val createdAtEpochMs: Long,
    val sortOrder: Int,
    // EXIF-derived (spec §14/§16) — null whenever not present in the
    // source photo; never backfilled from the Activity's own GPS track
    // (spec §16: "jangan menggunakan lokasi Activity sebagai lokasi foto
    // secara otomatis").
    val exifTimestampEpochMs: Long? = null,
    val exifLatitude: Double? = null,
    val exifLongitude: Double? = null
)
