package com.nyasar.app.data.repository

import android.content.Context
import android.net.Uri
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.db.RouteEntity
import com.nyasar.app.gpx.GpxParser
import com.nyasar.app.gpx.model.GpxDocument
import com.nyasar.app.navigation.ElevationStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Single entry point for "GPX → local storage → available for navigation".
 * This is the concrete implementation of spec section 16: everything after
 * import runs off [routesDir] on local disk. No network call is ever made
 * here, regardless of where the GPX file originally came from (file picker,
 * Share intent, or a file downloaded earlier from Nyasar Nyaman/GitHub).
 */
class RouteRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).routeDao()
    private val parser = GpxParser()

    private val routesDir: File by lazy {
        File(context.filesDir, "routes").apply { mkdirs() }
    }

    fun observeRoutes(): Flow<List<RouteEntity>> = dao.observeAll()

    suspend fun getRoute(id: String): RouteEntity? = dao.getById(id)

    fun gpxFile(route: RouteEntity): File = File(route.localGpxFilePath)

    /** Parses a stored route's GPX back into a full [GpxDocument] for map/nav use. */
    suspend fun loadDocument(route: RouteEntity): GpxDocument = withContext(Dispatchers.IO) {
        File(route.localGpxFilePath).inputStream().use { parser.parse(it, route.name) }
    }

    /**
     * Imports a GPX from any content [Uri] (file picker or Open With/Share
     * intent): copies the bytes into app-private local storage first, then
     * parses the *local* copy. This guarantees navigation never depends on
     * the original Uri/source still being reachable later (e.g. a SAF Uri
     * from a file manager that isn't valid across app restarts).
     */
    suspend fun importFromUri(uri: Uri, displayName: String?): RouteEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val destFile = File(routesDir, "$id.gpx")

        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Tidak bisa membuka file GPX yang dipilih.")

        val doc = destFile.inputStream().use { parser.parse(it, displayName ?: "Route") }
        val elevation = ElevationStats.summarize(doc.allTrackPoints)

        val totalDistance = doc.tracks.sumOf { track ->
            track.points.zipWithNext().sumOf { (a, b) ->
                com.nyasar.app.navigation.GeoMath.distanceMeters(
                    com.nyasar.app.navigation.LatLng(a.lat, a.lon),
                    com.nyasar.app.navigation.LatLng(b.lat, b.lon)
                )
            }
        }

        val entity = RouteEntity(
            id = id,
            name = doc.name,
            localGpxFilePath = destFile.absolutePath,
            distanceMeters = totalDistance,
            elevationGainM = elevation?.gainM,
            elevationLossM = elevation?.lossM,
            highestElevationM = elevation?.highestM,
            lowestElevationM = elevation?.lowestM,
            waypointCount = doc.waypoints.size,
            importedAtEpochMs = System.currentTimeMillis(),
            lastOpenedAtEpochMs = null
        )
        dao.insert(entity)
        entity
    }

    /**
     * Builds a route from points the user tapped on the map (draw-route
     * feature) instead of an imported .gpx file. Deliberately produces the
     * exact same artifact importFromUri does — a real .gpx file written to
     * [routesDir] plus a [RouteEntity] row — so a drawn route is
     * indistinguishable from an imported one everywhere else in the app
     * (Track & Peta list, Route Preview, offline download, navigation all
     * only ever read RouteEntity + the GPX file on disk; neither knows or
     * needs to know how the route was created).
     *
     * Points have no elevation (map taps only give lat/lon) — every
     * consumer of GpxDocument/TrackPoint already treats elevationM as
     * nullable (ElevationStats.summarize returns null cleanly with no
     * elevation data at all, exactly as it already does for GPX files
     * that lack elevation), so this isn't a new code path, just a new
     * source of an already-supported case.
     *
     * @param points at least 2 (a route needs a start and an end);
     * callers should not offer "Selesai" with fewer.
     */
    suspend fun importFromDrawnPoints(name: String, points: List<com.nyasar.app.gpx.model.TrackPoint>): RouteEntity =
        withContext(Dispatchers.IO) {
            require(points.size >= 2) { "Rute butuh minimal 2 titik." }

            val id = UUID.randomUUID().toString()
            val destFile = File(routesDir, "$id.gpx")
            val routeName = name.ifBlank { "Rute Baru" }

            writeDrawnRouteGpx(destFile, routeName, points)

            val elevation = ElevationStats.summarize(points) // null — no elevation from tapped points, handled the same as any elevation-less GPX
            val totalDistance = points.zipWithNext().sumOf { (a, b) ->
                com.nyasar.app.navigation.GeoMath.distanceMeters(
                    com.nyasar.app.navigation.LatLng(a.lat, a.lon),
                    com.nyasar.app.navigation.LatLng(b.lat, b.lon)
                )
            }

            val entity = RouteEntity(
                id = id,
                name = routeName,
                localGpxFilePath = destFile.absolutePath,
                distanceMeters = totalDistance,
                elevationGainM = elevation?.gainM,
                elevationLossM = elevation?.lossM,
                highestElevationM = elevation?.highestM,
                lowestElevationM = elevation?.lowestM,
                waypointCount = 0,
                importedAtEpochMs = System.currentTimeMillis(),
                lastOpenedAtEpochMs = null
            )
            dao.insert(entity)
            entity
        }

    /** Same GPX 1.1 shape/escaping GpxExporter uses for recorded activities
     *  (kept separate rather than sharing that object directly — GpxExporter's
     *  own doc comment scopes it to ActivityEntity/ActivityPointEntity
     *  export, a distinct read-only-DB contract this isn't bound by; this
     *  writes a brand-new route file, not an export of existing rows).
     *  No <time> per point — these were never actually visited yet. */
    private fun writeDrawnRouteGpx(file: File, name: String, points: List<com.nyasar.app.gpx.model.TrackPoint>) {
        file.bufferedWriter().use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            writer.newLine()
            writer.write("""<gpx version="1.1" creator="Nyasar" xmlns="http://www.topografix.com/GPX/1/1">""")
            writer.newLine()
            writer.write("  <trk><name>${escapeXml(name)}</name><trkseg>")
            writer.newLine()
            points.forEach { p ->
                writer.write("""    <trkpt lat="${p.lat}" lon="${p.lon}">""")
                p.elevationM?.let { writer.write("<ele>$it</ele>") }
                writer.write("</trkpt>")
                writer.newLine()
            }
            writer.write("  </trkseg></trk>")
            writer.newLine()
            writer.write("</gpx>")
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    suspend fun markOpened(routeId: String) = withContext(Dispatchers.IO) {
        dao.markOpened(routeId, System.currentTimeMillis())
    }

    suspend fun delete(route: RouteEntity) = withContext(Dispatchers.IO) {
        File(route.localGpxFilePath).delete()
        dao.delete(route)
    }
}
