package com.nyasar.app.map

import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Structured metadata for an offline region. Stored in the region's own
 * metadata byte array (MapLibre persists it next to the tile database —
 * no extra storage system, and it survives restarts with the region).
 *
 * Before this, region metadata was a bare UTF-8 name string, which made
 * "which basemap was this downloaded for?" and "when?" unanswerable and
 * left old regions nameless ("Peta tanpa nama"). JSON with a version field
 * keeps every region self-describing.
 *
 * Backwards compatibility: [parse] also accepts the legacy format (a bare
 * name string like "Lawu-offline-173...") so regions downloaded by older
 * builds still show a real name instead of "unnamed" — they simply report
 * null for the newer fields. A v2 document is recognized by its leading
 * '{'; a legacy name can never collide with that (names don't start with
 * '{').
 */
data class OfflineRegionMetadata(
    /** Human-readable area name, e.g. "Lawu" — shown in the Offline Maps list. */
    val name: String,
    /** [BasemapEntry.gpxKey] the region was downloaded for, when known.
     *  Legacy regions and raw-provider downloads report null. */
    val basemapId: String? = null,
    /** Epoch ms when the download was created, when known. */
    val createdAtEpochMs: Long? = null
) {
    fun encode(): ByteArray = JSONObject().apply {
        put(KEY_VERSION, 2)
        put(KEY_NAME, name)
        basemapId?.let { put(KEY_BASEMAP, it) }
        createdAtEpochMs?.let { put(KEY_CREATED, it) }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        private const val KEY_VERSION = "v"
        private const val KEY_NAME = "name"
        private const val KEY_BASEMAP = "basemap"
        private const val KEY_CREATED = "created"

        fun encode(name: String, basemapId: String?, createdAtEpochMs: Long?): ByteArray =
            OfflineRegionMetadata(name, basemapId, createdAtEpochMs).encode()

        /**
         * Never throws — a corrupt/foreign metadata blob degrades to
         * "unnamed region" the same way the old raw-string code did, but
         * without the crash that made the whole list die (the String(bytes)
         * force-close fixed in OfflineMapsViewModel).
         */
        fun parse(metadata: ByteArray?): OfflineRegionMetadata {
            if (metadata == null || metadata.isEmpty()) return unnamed()
            return try {
                val raw = String(metadata, Charsets.UTF_8).trim()
                if (raw.startsWith("{")) {
                    val json = JSONObject(raw)
                    OfflineRegionMetadata(
                        name = json.optString(KEY_NAME),
                        basemapId = json.optString(KEY_BASEMAP).takeIf { it.isNotBlank() },
                        createdAtEpochMs = if (json.has(KEY_CREATED)) json.getLong(KEY_CREATED) else null
                    )
                } else {
                    // Legacy: bare name string written by older builds.
                    OfflineRegionMetadata(name = raw)
                }
            } catch (_: Exception) {
                unnamed()
            }
        }

        fun parse(region: OfflineRegion): OfflineRegionMetadata = parse(region.metadata)

        private fun unnamed() = OfflineRegionMetadata(name = "")
    }
}

/**
 * The exact style URL a region was downloaded for — from the definition
 * MapLibre stored with the region. Null only for foreign/non-pyramid
 * definitions. This is how a screen can answer "is THIS area already
 * downloaded for the basemap I'm looking at?" by comparing URLs.
 */
fun OfflineRegion.styleUrlOrNull(): String? =
    (definition as? OfflineTilePyramidRegionDefinition)?.styleURL

/**
 * Whether this region's coverage fully contains [target]. Regions are
 * rectangles, so rectangle-contains-rectangle is exact — no pixel math.
 * False for non-pyramid definitions.
 */
fun OfflineRegion.covers(target: LatLngBounds): Boolean {
    val bounds = (definition as? OfflineTilePyramidRegionDefinition)?.bounds ?: return false
    return bounds.latitudeSouth <= target.latitudeSouth &&
        bounds.latitudeNorth >= target.latitudeNorth &&
        bounds.longitudeWest <= target.longitudeWest &&
        bounds.longitudeEast >= target.longitudeEast
}
