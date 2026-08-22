package com.nyasar.app.data.repository

import android.content.Context
import android.net.Uri
import com.nyasar.app.data.db.ActivityPhotoEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.media.PhotoStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Single entry point for Activity Photos (P3H) — mirrors the
 * WaypointRepository/RouteRepository split already in this codebase (DAO
 * for persistence, this class for the actual read/write behavior + file
 * lifecycle). No duplicate Activity/media architecture: reuses
 * AppDatabase, FileProvider (existing authority), and this app's existing
 * "own private copy" storage convention (RouteRepository copies imported
 * GPX the same way).
 */
class ActivityPhotoRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).activityPhotoDao()
    private val storage = PhotoStorageManager(appContext)

    fun observeForActivity(activityId: String): Flow<List<ActivityPhotoEntity>> =
        dao.observeForActivity(activityId)

    /**
     * Get photos for an activity as a one-shot list (not Flow).
     * Used by PostRecordingForm to load existing photos.
     */
    suspend fun getPhotosForActivity(activityId: String): List<ActivityPhotoEntity> =
        withContext(Dispatchers.IO) {
            dao.getForActivity(activityId)
        }

    /** Camera flow, step 1: caller needs a destination Uri before it can
     *  launch the system Camera app (ActivityResultContracts.TakePicture
     *  requires the target Uri up front). No DB row is created here — only
     *  [confirmCameraCapture] (called on a successful result) inserts one,
     *  which is what makes spec §2 "jika user membatalkan, tidak membuat
     *  record kosong" true: a cancelled capture just leaves an unreferenced
     *  file (cleaned up by the caller) with nothing in the database. */
    suspend fun prepareCameraCaptureTarget(activityId: String): Pair<File, Uri> =
        withContext(Dispatchers.IO) {
            val nextSeq = dao.maxSortOrder(activityId) + 1
            storage.newCameraCaptureTarget(activityId, nextSeq)
        }

    /** Camera flow, step 2 — only called after TakePicture() returns
     *  success=true, i.e. the file at [file] genuinely has a photo in it. */
    suspend fun confirmCameraCapture(activityId: String, file: File): ActivityPhotoEntity =
        withContext(Dispatchers.IO) {
            val meta = storage.readExifMetadata(file)
            val entity = ActivityPhotoEntity(
                id = UUID.randomUUID().toString(),
                activityId = activityId,
                filePath = file.absolutePath,
                createdAtEpochMs = System.currentTimeMillis(),
                sortOrder = dao.maxSortOrder(activityId) + 1,
                exifTimestampEpochMs = meta.timestampEpochMs,
                exifLatitude = meta.latitude,
                exifLongitude = meta.longitude
            )
            dao.insert(entity)
            entity
        }

    /** Cancelled capture cleanup — the destination file TakePicture() was
     *  given may exist (some camera apps pre-create it) even on cancel;
     *  since it was never confirmed into the DB, delete it so it doesn't
     *  linger as an orphaned file. */
    suspend fun discardCameraCapture(file: File) = withContext(Dispatchers.IO) {
        if (file.exists()) file.delete()
    }

    /** Gallery flow — one call per picked Uri (Photo Picker already
     *  supports multi-select, spec §3), each copied into app storage
     *  independently so one failure doesn't abort the rest of the batch. */
    suspend fun addFromGallery(activityId: String, uris: List<Uri>): List<ActivityPhotoEntity> =
        withContext(Dispatchers.IO) {
            var nextSeq = dao.maxSortOrder(activityId) + 1
            uris.mapNotNull { uri ->
                val file = storage.copyFromGalleryUri(uri, activityId, nextSeq) ?: return@mapNotNull null
                nextSeq++
                val meta = storage.readExifMetadata(file)
                val entity = ActivityPhotoEntity(
                    id = UUID.randomUUID().toString(),
                    activityId = activityId,
                    filePath = file.absolutePath,
                    createdAtEpochMs = System.currentTimeMillis(),
                    sortOrder = nextSeq - 1,
                    exifTimestampEpochMs = meta.timestampEpochMs,
                    exifLatitude = meta.latitude,
                    exifLongitude = meta.longitude
                )
                dao.insert(entity)
                entity
            }
        }

    /** Delete one photo — spec §11/§12: only ever removes this app's own
     *  copy, never anything in the user's Gallery (there is nothing from
     *  the Gallery in this table to begin with — see ActivityPhotoEntity). */
    suspend fun delete(photo: ActivityPhotoEntity) = withContext(Dispatchers.IO) {
        storage.deleteFile(photo.filePath)
        dao.delete(photo)
    }

    /** Spec §21/§22 (P3H) + §14 regression test in P3H's own list: Activity
     *  delete must clean up photo associations *and* their files, without
     *  touching anything else the activity delete flow already does
     *  (GPS points, the activity row itself — still ActivityDetailViewModel's
     *  job, this only owns the photo side of that same delete). */
    suspend fun deleteAllForActivity(activityId: String) = withContext(Dispatchers.IO) {
        storage.deleteAllForActivity(activityId)
        dao.deleteAllForActivity(activityId)
    }
}
