package com.nyasar.app.gpx

import android.content.Context
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.ActivityPointEntity
import com.nyasar.app.data.db.WaypointEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a completed activity's recorded points (+ any waypoints dropped
 * during that session) as a standard GPX 1.1 file into the app's cache
 * dir, ready to be shared via FileProvider. Routes already have their
 * original GPX on disk (RouteRepository.gpxFile) and don't need this —
 * this is specifically for recorded activities, which only exist as
 * ActivityPointEntity/WaypointEntity rows until exported.
 *
 * Read-only with respect to the database (spec P3G §3/§12: export must
 * never mutate Activity/track/waypoint data) — every parameter here is a
 * plain snapshot already queried by the caller; this object only writes a
 * file, no Dao access.
 */
object GpxExporter {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * @param waypoints activity-scoped waypoints only (e.g.
     * ActivityDetailViewModel.waypointsDuringActivity) — spec P3G §2:
     * "JANGAN memasukkan semua waypoint global user", so the caller is
     * responsible for having already scoped this list, not this function.
     *
     * Blocking file I/O — call from a background dispatcher (see
     * ActivityDetailScreen's shareActivityGpx, which wraps this in
     * Dispatchers.IO). Kept as a plain function rather than `suspend` so it
     * has no coroutines dependency of its own and stays trivially testable.
     */
    fun exportActivity(
        context: Context,
        activity: ActivityEntity,
        points: List<ActivityPointEntity>,
        waypoints: List<WaypointEntity> = emptyList()
    ): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, buildFileName(activity))

        file.bufferedWriter().use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            writer.newLine()
            writer.write("""<gpx version="1.1" creator="Nyasar" xmlns="http://www.topografix.com/GPX/1/1">""")
            writer.newLine()

            // Waypoints before the track — conventional GPX 1.1 element
            // order (wpt*, rte*, trk*), some stricter parsers care.
            waypoints.forEach { wpt ->
                writer.write("""  <wpt lat="${wpt.lat}" lon="${wpt.lon}">""")
                wpt.elevationM?.let { writer.write("<ele>$it</ele>") }
                writer.write("<name>${escapeXml(wpt.name)}</name>")
                // <type> is the standard GPX 1.1 element for a free-form
                // category tag — using it (not a Nyasar-specific extension
                // element) keeps this readable by any generic GPX viewer.
                writer.write("<type>${escapeXml(wpt.category)}</type>")
                wpt.note?.takeIf { it.isNotBlank() }?.let { writer.write("<desc>${escapeXml(it)}</desc>") }
                writer.write("</wpt>")
                writer.newLine()
            }

            if (points.isEmpty()) {
                // Spec §10: never emit a bare empty <trkseg> with no
                // explanation — an empty GPX with nothing but headers is
                // technically valid XML but useless and confusing if
                // opened externally. The UI (ActivityDetailScreen) is
                // expected not to even offer export when there's no track,
                // but this is a second line of defense at the file level.
                writer.write("  <!-- No recorded track available for this activity. -->")
                writer.newLine()
            } else {
                writer.write("  <trk><name>${escapeXml(activity.name)}</name><trkseg>")
                writer.newLine()
                points.forEach { p ->
                    writer.write("""    <trkpt lat="${p.lat}" lon="${p.lon}">""")
                    p.elevationM?.let { writer.write("<ele>$it</ele>") }
                    writer.write("<time>${isoFormat.format(java.util.Date(p.timestampMs))}</time>")
                    writer.write("</trkpt>")
                    writer.newLine()
                }
                writer.write("  </trkseg></trk>")
                writer.newLine()
            }
            writer.write("</gpx>")
        }
        return file
    }

    /**
     * Spec P3G §4: lowercase, spaces -> dash, strip anything else weird,
     * append the activity's date, .gpx extension. E.g. "Lawu — Cemoro
     * Sewu" recorded 2026-08-15 -> "lawu-cemoro-sewu-2026-08-15.gpx".
     * Falls back to "aktivitas" if the name collapses to nothing (e.g. a
     * name that was 100% punctuation/emoji).
     */
    private fun buildFileName(activity: ActivityEntity): String {
        val dateSuffix = fileDateFormat.format(java.util.Date(activity.startedAtEpochMs))
        val slug = activity.name
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .ifBlank { "aktivitas" }
        return "$slug-$dateSuffix.gpx"
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
