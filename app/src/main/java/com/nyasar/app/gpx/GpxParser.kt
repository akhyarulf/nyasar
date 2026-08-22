package com.nyasar.app.gpx

import android.util.Xml
import com.nyasar.app.gpx.model.GpxDocument
import com.nyasar.app.gpx.model.GpxTrack
import com.nyasar.app.gpx.model.GpxWaypoint
import com.nyasar.app.gpx.model.TrackPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streaming GPX 1.1 parser built on Android's stock [XmlPullParser].
 * Deliberately dependency-free: parsing must never require network access,
 * which keeps the app's offline story simple (nothing to fetch, nothing
 * that can silently phone home while reading a file on a mountain).
 *
 * Handles <trk>/<trkseg>/<trkpt>, <rte>/<rtept>, <wpt>, elevation (<ele>)
 * and <time> when present — all optional per spec, all tolerated as missing.
 *
 * Follows the standard Android recursive-descent XmlPullParser pattern:
 * every read*() assumes it is called right after the START_TAG for its
 * element, consumes exactly up to and including that element's END_TAG,
 * and unknown children are explicitly skipped rather than left dangling.
 */
class GpxParser {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val isoFormatMillis = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parse(input: InputStream, fallbackName: String): GpxDocument {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            parser.nextTag()
            parser.require(XmlPullParser.START_TAG, null, "gpx")

            var docName: String? = null
            val tracks = mutableListOf<GpxTrack>()
            val waypoints = mutableListOf<GpxWaypoint>()

            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "metadata" -> docName = readMetadata(parser) ?: docName
                    "trk" -> tracks += readTrack(parser)
                    "rte" -> tracks += readRoute(parser)
                    "wpt" -> waypoints += readWaypoint(parser)
                    else -> skip(parser)
                }
            }

            if (tracks.all { it.points.isEmpty() } && waypoints.isEmpty()) {
                throw GpxParseException("File tidak berisi track, route, atau waypoint yang bisa dibaca.")
            }

            return GpxDocument(
                name = docName ?: fallbackName,
                tracks = tracks.filter { it.points.isNotEmpty() },
                waypoints = waypoints
            )
        } catch (e: GpxParseException) {
            throw e
        } catch (e: Exception) {
            throw GpxParseException("GPX tidak valid atau rusak: ${e.message}", e)
        }
    }

    private fun readMetadata(parser: XmlPullParser): String? {
        parser.require(XmlPullParser.START_TAG, null, "metadata")
        var name: String? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "name") name = readText(parser, "name") else skip(parser)
        }
        return name
    }

    private fun readTrack(parser: XmlPullParser): GpxTrack {
        parser.require(XmlPullParser.START_TAG, null, "trk")
        var name = "Track"
        val points = mutableListOf<TrackPoint>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "name" -> name = readText(parser, "name").ifBlank { name }
                "trkseg" -> points += readTrkseg(parser)
                else -> skip(parser)
            }
        }
        return GpxTrack(name = name, points = points)
    }

    private fun readTrkseg(parser: XmlPullParser): List<TrackPoint> {
        parser.require(XmlPullParser.START_TAG, null, "trkseg")
        val points = mutableListOf<TrackPoint>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "trkpt") points += readPoint(parser, "trkpt") else skip(parser)
        }
        return points
    }

    private fun readRoute(parser: XmlPullParser): GpxTrack {
        parser.require(XmlPullParser.START_TAG, null, "rte")
        var name = "Route"
        val points = mutableListOf<TrackPoint>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "name" -> name = readText(parser, "name").ifBlank { name }
                "rtept" -> points += readPoint(parser, "rtept")
                else -> skip(parser)
            }
        }
        return GpxTrack(name = name, points = points)
    }

    private fun readWaypoint(parser: XmlPullParser): GpxWaypoint {
        parser.require(XmlPullParser.START_TAG, null, "wpt")
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
        var name = "Waypoint"
        var ele: Double? = null
        var desc: String? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "name" -> name = readText(parser, "name").ifBlank { name }
                "ele" -> ele = readText(parser, "ele").toDoubleOrNull()
                "desc" -> desc = readText(parser, "desc")
                else -> skip(parser)
            }
        }
        return GpxWaypoint(name = name, lat = lat, lon = lon, elevationM = ele, description = desc)
    }

    private fun readPoint(parser: XmlPullParser, tagName: String): TrackPoint {
        parser.require(XmlPullParser.START_TAG, null, tagName)
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
            ?: throw GpxParseException("$tagName tanpa atribut lat")
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
            ?: throw GpxParseException("$tagName tanpa atribut lon")
        var ele: Double? = null
        var time: Long? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "ele" -> ele = readText(parser, "ele").toDoubleOrNull()
                "time" -> time = parseTime(readText(parser, "time"))
                else -> skip(parser)
            }
        }
        return TrackPoint(lat = lat, lon = lon, elevationM = ele, timestampEpochMs = time)
    }

    /** Reads the text content of a simple leaf element and consumes its END_TAG. */
    private fun readText(parser: XmlPullParser, tag: String): String {
        parser.require(XmlPullParser.START_TAG, null, tag)
        val text = if (parser.next() == XmlPullParser.TEXT) {
            val t = parser.text
            parser.nextTag()
            t
        } else {
            ""
        }
        parser.require(XmlPullParser.END_TAG, null, tag)
        return text
    }

    /** Skips an entire element subtree the parser doesn't otherwise handle. */
    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) throw IllegalStateException()
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    private fun parseTime(raw: String): Long? = try {
        if (raw.contains('.')) isoFormatMillis.parse(raw)?.time else isoFormat.parse(raw)?.time
    } catch (e: Exception) {
        null
    }
}
