package com.nyasar.app.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/**
 * All on-disk concerns for Activity Photos (P3H) in one place — spec §4/§12
 * ("aman", "jangan overwrite original"), §24 (naming), §17 (no format
 * changes to the source photo). Nothing here touches MediaStore/Gallery:
 * camera captures write straight into this app's private files dir, and
 * gallery picks are byte-copied in — the original the user picked is never
 * opened for writing, only read once to make the copy.
 */
class PhotoStorageManager(private val context: Context) {

    private fun activityPhotoDir(activityId: String): File =
        File(context.filesDir, "activity_photos/$activityId").apply { mkdirs() }

    /**
     * A fresh destination file for a camera capture (spec §24 naming:
     * `<activityId>_photo_<seq>.jpg`) plus the content:// Uri the system
     * Camera app needs to write into it (FileProvider — same authority
     * already declared for GPX share/export, reused here rather than a
     * second provider).
     */
    fun newCameraCaptureTarget(activityId: String, nextSeq: Int): Pair<File, Uri> {
        val file = File(activityPhotoDir(activityId), "%s_photo_%03d.jpg".format(activityId, nextSeq))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    /**
     * Copies a gallery-picked photo (from the Android Photo Picker — a
     * content:// Uri the app only has short-lived read access to) into this
     * app's private storage, byte-for-byte, so it survives independently of
     * that Uri's lifetime (spec §4: "jangan hanya menyimpan URI sementara").
     * The source is only ever opened for reading — spec §12/§13.
     *
     * P3H final fix: the copy's extension now follows the source's actual
     * MIME type (JPEG/PNG/HEIC/WEBP/...) instead of always ".jpg" — a raw
     * byte copy of a PNG or HEIC file saved with a ".jpg" name is still
     * exactly that PNG/HEIC's bytes, just mislabeled, which is misleading
     * on-disk and can trip up anything reading the extension to guess
     * format. The copy itself is still a plain byte-for-byte
     * `input.copyTo(output)` either way — only the destination filename's
     * extension changed, no recompression/transcoding.
     */
    fun copyFromGalleryUri(sourceUri: Uri, activityId: String, nextSeq: Int): File? {
        val extension = extensionForUri(sourceUri)
        val file = File(activityPhotoDir(activityId), "%s_photo_%03d.%s".format(activityId, nextSeq, extension))
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            file
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /**
     * MIME type (from the ContentResolver, which the Photo Picker always
     * supplies) -> file extension, via the standard Android MimeTypeMap.
     * Falls back to "jpg" only when the MIME type is missing or genuinely
     * unrecognized (MimeTypeMap has no mapping) — not a default that
     * silently mislabels known non-JPEG formats.
     */
    private fun extensionForUri(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri)
        val fromMimeMap = mimeType?.let {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        }
        return fromMimeMap ?: when (mimeType) {
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/webp" -> "webp"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> "jpg"
        }
    }

    /** Spec §14/§16: read-only EXIF, never written back. Any failure (no
     *  EXIF block, corrupted file) just means no metadata — not an error
     *  the caller needs to handle specially. */
    fun readExifMetadata(file: File): PhotoExifMetadata {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val latLon = FloatArray(2)
            val hasLatLon = exif.getLatLong(latLon)
            val dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val timestamp = dateString?.let { parseExifDate(it) }
            PhotoExifMetadata(
                timestampEpochMs = timestamp,
                latitude = if (hasLatLon) latLon[0].toDouble() else null,
                longitude = if (hasLatLon) latLon[1].toDouble() else null
            )
        } catch (e: Exception) {
            PhotoExifMetadata(null, null, null)
        }
    }

    private fun parseExifDate(value: String): Long? = try {
        // EXIF datetime format: "yyyy:MM:dd HH:mm:ss"
        val format = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
        format.parse(value)?.time
    } catch (e: Exception) {
        null
    }

    /** Deletes the on-disk file for one photo — spec §11: "hapus file
     *  sesuai ownership" (this app owns every file here, see class doc). */
    fun deleteFile(filePath: String) {
        File(filePath).delete()
    }

    /** Spec §21: Activity delete → its whole photo directory goes with it. */
    fun deleteAllForActivity(activityId: String) {
        activityPhotoDir(activityId).deleteRecursively()
    }
}

data class PhotoExifMetadata(
    val timestampEpochMs: Long?,
    val latitude: Double?,
    val longitude: Double?
)
