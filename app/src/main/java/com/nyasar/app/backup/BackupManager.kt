package com.nyasar.app.backup

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.nyasar.app.data.db.ActivityDao
import com.nyasar.app.data.db.ActivityEntity
import com.nyasar.app.data.db.ActivityPointEntity
import com.nyasar.app.data.db.AppDatabase
import com.nyasar.app.data.db.RouteDao
import com.nyasar.app.data.db.RouteEntity
import com.nyasar.app.data.db.WaypointDao
import com.nyasar.app.data.db.WaypointEntity
import com.nyasar.app.data.supabase.BackupRepository
import com.nyasar.app.data.supabase.SupabaseClientProvider
import com.nyasar.app.gpx.GpxParser
import com.nyasar.app.navigation.ElevationStats
import com.nyasar.app.navigation.GeoMath
import com.nyasar.app.navigation.LatLng
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrator for Fase 3 (Backup Pribadi) — turns local Room rows into
 * `activity_backups` rows (backup) and the other direction on a new phone
 * (restore). The network layer is [BackupRepository]; the codec is
 * [DeltaEncoder]; this class owns the mapping and the merge rules.
 *
 * Merge rules (keputusan Fase 3, tercatat di PROJECT_CONTEXT.md):
 *  - Row id is STABLE per source: first backup of an activity/route mints
 *    a UUID and keeps it forever (probe via fetchRowIdFor), so re-backups
 *    UPSERT the same row — no duplicates, ever. (The schema's unique
 *    indexes are partial; on_conflict can't target them, hence the id probe.)
 *  - Backup NEVER deletes: removing cloud rows happens nowhere here (a
 *    local delete intentionally does not propagate — the cloud copy is the
 *    safety net the whole feature exists for).
 *  - Restore SKIPS sources that already exist locally (same id) — never
 *    overwrites newer local data, never duplicates. A partially-restored
 *    source (points inserted but summary missing, crash mid-restore) also
 *    counts as "already present" and gets completed from the backup.
 *  - Restore PRESERVES original ids (route/activity/waypoint) so
 *    activities.localRouteId stays meaningful after a full restore.
 *  - Restore order: routes first, then activities — localRouteId can only
 *    dangle if the user restored activities but deliberately skipped routes
 *    (or the route backup row was deleted server-side); same tolerance as
 *    ActivityEntity's own no-FK design.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val activityDao: ActivityDao,
    private val routeDao: RouteDao,
    private val waypointDao: WaypointDao,
    private val repository: BackupRepository = BackupRepository()
) {

    sealed class BackupResult {
        data class Success(val uploaded: Int, val skipped: Int) : BackupResult()
        data class Failure(val kind: Kind) : BackupResult()
        enum class Kind { NOT_SIGNED_IN, NOT_CONFIGURED, NETWORK, UNKNOWN }
    }

    sealed class RestoreResult {
        data class Success(val restoredRoutes: Int, val restoredActivities: Int, val skipped: Int) : RestoreResult()
        data class Failure(val kind: BackupResult.Kind) : RestoreResult()
    }

    // ────────────────────────── Backup ──────────────────────────

    /** Backs up ONE completed activity (recording source) — the auto-backup
     *  entry point after a recording finishes. */
    suspend fun backupActivity(activityId: String): BackupResult = withContext(Dispatchers.IO) {
        val activity = activityDao.getById(activityId) ?: return@withContext BackupResult.Failure(BackupResult.Kind.UNKNOWN)
        val points = activityDao.getPoints(activityId)
        val waypoints = waypointDao.getForActivity(activityId)
        backupSources(sources = listOf(BackupSource.Activity(activity, points, waypoints)))
    }

    /** Backs up ONE route (GPX import source) — track comes from the route's
     *  GPX file, the same artifact the rest of the app reads. */
    suspend fun backupRoute(routeId: String): BackupResult = withContext(Dispatchers.IO) {
        val route = routeDao.getById(routeId) ?: return@withContext BackupResult.Failure(BackupResult.Kind.UNKNOWN)
        val gpx = routeDaoFile(route)
        val points = if (gpx.exists()) {
            try {
                gpx.inputStream().use { GpxParser().parse(it, route.name) }.allTrackPoints
            } catch (e: Exception) {
                Log.e(TAG, "backupRoute: GPX unreadable for ${route.id}", e)
                emptyList()
            }
        } else emptyList()
        val waypoints = waypointDao.getForRoute(routeId)
        backupSources(sources = listOf(BackupSource.Route(route, points, waypoints)))
    }

    /** Backup-all: probe existing rows first, then upsert every local
     *  activity + route. One failing source never aborts the batch. */
    suspend fun backupAll(): BackupResult = withContext(Dispatchers.IO) {
        val sources = ArrayList<BackupSource>()
        activityDao.getAllOnce().forEach { activity ->
            sources.add(
                BackupSource.Activity(
                    activity,
                    activityDao.getPoints(activity.id),
                    waypointDao.getForActivity(activity.id)
                )
            )
        }
        routeDao.getAllOnce().forEach { route ->
            val gpx = routeDaoFile(route)
            val points = if (gpx.exists()) {
                try {
                    gpx.inputStream().use { GpxParser().parse(it, route.name) }.allTrackPoints
                } catch (e: Exception) {
                    Log.e(TAG, "backupAll: GPX unreadable for ${route.id}", e)
                    emptyList()
                }
            } else emptyList()
            sources.add(BackupSource.Route(route, points, waypointDao.getForRoute(route.id)))
        }
        backupSources(sources)
    }

    /** The union fed to [backupSources] — one local thing worth backing up. */
    private sealed class BackupSource {
        abstract val name: String
        abstract val startedAt: Long?
        abstract val endedAt: Long?
        abstract val status: String?
        abstract val points: List<com.nyasar.app.gpx.model.TrackPoint>

        data class Activity(
            val activity: ActivityEntity,
            val pts: List<ActivityPointEntity>,
            val waypoints: List<WaypointEntity>
        ) : BackupSource() {
            override val name get() = activity.name
            override val startedAt get() = activity.startedAtEpochMs
            override val endedAt get() = activity.endedAtEpochMs
            override val status: String? get() = activity.status
            override val points: List<com.nyasar.app.gpx.model.TrackPoint>
                get() = pts.map {
                    com.nyasar.app.gpx.model.TrackPoint(it.lat, it.lon, it.elevationM, it.timestampMs)
                }
        }

        data class Route(
            val route: RouteEntity,
            val pts: List<com.nyasar.app.gpx.model.TrackPoint>,
            val waypoints: List<WaypointEntity>
        ) : BackupSource() {
            override val name get() = route.name
            override val startedAt get() = route.importedAtEpochMs
            override val endedAt: Long? = null
            // Route rows have no recording status — null passes the schema's
            // check (status is null OR one of the four values).
            override val status: String? = null
            override val points: List<com.nyasar.app.gpx.model.TrackPoint> get() = pts
        }
    }

    /** Shared backup pipeline: session probe → stable id probe → encode →
     *  upsert. Per-source try/catch: one bad row (corrupt GPX, encode bug)
     *  counts as failed, not fatal. */
    private suspend fun backupSources(sources: List<BackupSource>): BackupResult {
        if (sources.isEmpty()) return BackupResult.Success(uploaded = 0, skipped = 0)
        if (!SupabaseClientProvider.isConfigured) return BackupResult.Failure(BackupResult.Kind.NOT_CONFIGURED)
        val userId = BackupRepository.currentUserIdOrNull()
            ?: return BackupResult.Failure(BackupResult.Kind.NOT_SIGNED_IN)
        val client = SupabaseClientProvider.client

        var uploaded = 0
        var skipped = 0
        for (source in sources) {
            try {
                val (activityKey, routeKey) = when (source) {
                    is BackupSource.Activity -> source.activity.id to null
                    is BackupSource.Route -> null to source.route.id
                }

                // Stable row id: reuse the existing cloud row when this
                // source was backed up before; mint once otherwise.
                val existingId = when (val probe = repository.fetchRowIdFor(client, activityKey, routeKey)) {
                    is BackupRepository.ExistingOutcome.Success -> probe.rows.firstOrNull()?.id
                    is BackupRepository.ExistingOutcome.Failure -> when (probe.error) {
                        BackupRepository.BackupError.NETWORK -> return BackupResult.Failure(BackupResult.Kind.NETWORK)
                        else -> return BackupResult.Failure(BackupResult.Kind.UNKNOWN)
                    }
                }

                val row = BackupRepository.BackupRow(
                    id = existingId ?: UUID.randomUUID().toString(),
                    userId = userId,
                    sourceActivityId = activityKey,
                    sourceRouteId = routeKey,
                    name = source.name,
                    startedAtEpochMs = source.startedAt,
                    endedAtEpochMs = source.endedAt,
                    status = source.status,
                    distanceMeters = when (source) {
                        is BackupSource.Activity -> source.activity.distanceMeters
                        is BackupSource.Route -> source.route.distanceMeters
                    },
                    elevationGainM = when (source) {
                        is BackupSource.Activity -> source.activity.elevationGainM
                        is BackupSource.Route -> source.route.elevationGainM
                    },
                    elevationLossM = when (source) {
                        is BackupSource.Activity -> source.activity.elevationLossM
                        is BackupSource.Route -> source.route.elevationLossM
                    },
                    movingTimeMs = when (source) {
                        is BackupSource.Activity -> source.activity.movingTimeMs
                        is BackupSource.Route -> null
                    },
                    elapsedTimeMs = when (source) {
                        is BackupSource.Activity -> source.activity.elapsedTimeMs
                        is BackupSource.Route -> null
                    },
                    avgSpeedKmh = when (source) {
                        is BackupSource.Activity -> source.activity.avgSpeedKmh
                        is BackupSource.Route -> null
                    },
                    maxSpeedKmh = when (source) {
                        is BackupSource.Activity -> source.activity.maxSpeedKmh
                        is BackupSource.Route -> null
                    },
                    sportType = when (source) {
                        is BackupSource.Activity -> source.activity.sportType
                        is BackupSource.Route -> null
                    },
                    localRouteId = (source as? BackupSource.Activity)?.activity?.routeId,
                    trackData = BackupRepository.byteaHexEncode(
                        DeltaEncoder.encode(
                            when (source) {
                                is BackupSource.Activity -> source.pts
                                is BackupSource.Route ->
                                    source.points.mapIndexed { i, p ->
                                        ActivityPointEntity(
                                            id = 0, activityId = "", sequence = i,
                                            lat = p.lat, lon = p.lon,
                                            elevationM = p.elevationM,
                                            speedMps = null,
                                            accuracyMeters = 0f,
                                            timestampMs = p.timestampEpochMs ?: 0L
                                        )
                                    }
                            }
                        )
                    ),
                    waypointsJson = source.waypoints.map { w ->
                        BackupRepository.WaypointJson(
                            id = w.id, name = w.name, category = w.category,
                            lat = w.lat, lon = w.lon, elevationM = w.elevationM,
                            note = w.note, createdAtEpochMs = w.createdAtEpochMs, source = w.source
                        )
                    }
                )

                when (val outcome = repository.upsertBackup(client, row)) {
                    is BackupRepository.Outcome.Success -> uploaded++
                    is BackupRepository.Outcome.Failure -> when (outcome.error) {
                        BackupRepository.BackupError.NETWORK -> return BackupResult.Failure(BackupResult.Kind.NETWORK)
                        else -> skipped++ // one bad row never kills the batch
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "backupSources: source failed (${source.name})", e)
                skipped++
            }
        }
        return BackupResult.Success(uploaded, skipped)
    }

    // ────────────────────────── Restore ──────────────────────────

    /** Restores every cloud backup not already present locally. Idempotent:
     *  running it twice is a no-op the second time. */
    suspend fun restoreAll(): RestoreResult = withContext(Dispatchers.IO) {
        if (!SupabaseClientProvider.isConfigured) return@withContext RestoreResult.Failure(BackupResult.Kind.NOT_CONFIGURED)
        val client = SupabaseClientProvider.client
        val rows = when (val outcome = repository.fetchAllBackups(client)) {
            is BackupRepository.FetchOutcome.Success -> outcome.rows
            is BackupRepository.FetchOutcome.Failure -> return@withContext RestoreResult.Failure(
                when (outcome.error) {
                    BackupRepository.BackupError.NETWORK -> BackupResult.Kind.NETWORK
                    else -> BackupResult.Kind.UNKNOWN
                }
            )
        }

        var restoredRoutes = 0
        var restoredActivities = 0
        var skipped = 0

        // Pass 1: routes (activities may reference them via localRouteId).
        for (row in rows.filter { it.sourceRouteId != null }) {
            try {
                if (routeDao.getById(row.sourceRouteId) != null) {
                    skipped++
                    continue
                }
                restoreRoute(row)
                restoredRoutes++
            } catch (e: Exception) {
                Log.e(TAG, "restore: route row ${row.id} failed", e)
                skipped++
            }
        }

        // Pass 2: activities.
        for (row in rows.filter { it.sourceActivityId != null }) {
            try {
                if (activityDao.getById(row.sourceActivityId) != null) {
                    skipped++
                    continue
                }
                restoreActivity(row)
                restoredActivities++
            } catch (e: Exception) {
                Log.e(TAG, "restore: activity row ${row.id} failed", e)
                skipped++
            }
        }
        RestoreResult.Success(restoredRoutes, restoredActivities, skipped)
    }

    /** Rebuilds a RouteEntity + its GPX file + GPX-origin waypoints from one
     *  backup row — the inverse of [backupRoute]. The regenerated GPX is a
     *  first-class file (preview/navigation/offline all read it normally). */
    private suspend fun restoreRoute(row: BackupRepository.BackupRow) {
        val points = decodeTrackPoints(row)
        val routeId = row.sourceRouteId!! // pass filter guarantees non-null

        // Recompute stats from the decoded track — the same formulas
        // importLocalGpxFile uses — so a row backed up before a stats fix
        // still lands consistent with locally-imported routes.
        val elevation = ElevationStats.summarize(points)
        val totalDistance = points.zipWithNext().sumOf { (a, b) ->
            GeoMath.distanceMeters(LatLng(a.lat, a.lon), LatLng(b.lat, b.lon))
        }

        val dir = File(context.filesDir, "routes").apply { mkdirs() }
        val gpxFile = File(dir, "$routeId.gpx")
        writeRouteGpx(gpxFile, row.name, points)

        db.withTransaction {
            routeDao.insert(
                RouteEntity(
                    id = routeId,
                    name = row.name,
                    localGpxFilePath = gpxFile.absolutePath,
                    distanceMeters = totalDistance,
                    elevationGainM = elevation?.gainM,
                    elevationLossM = elevation?.lossM,
                    highestElevationM = elevation?.highestM,
                    lowestElevationM = elevation?.lowestM,
                    waypointCount = row.waypointsJson?.size ?: 0,
                    importedAtEpochMs = row.startedAtEpochMs ?: System.currentTimeMillis(),
                    lastOpenedAtEpochMs = null
                )
            )
            insertWaypoints(row, routeLinkId = routeId, activityLinkId = null)
        }
    }

    /** Rebuilds an ActivityEntity + its raw GPS points + activity waypoints. */
    private suspend fun restoreActivity(row: BackupRepository.BackupRow) {
        val activityId = row.sourceActivityId!! // pass filter guarantees non-null
        val points = DeltaEncoder.decode(BackupRepository.byteaHexDecode(row.trackData), activityId)

        db.withTransaction {
            activityDao.insert(
                ActivityEntity(
                    id = activityId,
                    routeId = row.localRouteId,
                    name = row.name,
                    startedAtEpochMs = row.startedAtEpochMs ?: 0L,
                    endedAtEpochMs = row.endedAtEpochMs,
                    status = row.status ?: com.nyasar.app.data.db.ActivityStatus.COMPLETED,
                    distanceMeters = row.distanceMeters ?: 0.0,
                    movingTimeMs = row.movingTimeMs ?: 0L,
                    elapsedTimeMs = row.elapsedTimeMs ?: 0L,
                    avgSpeedKmh = row.avgSpeedKmh,
                    maxSpeedKmh = row.maxSpeedKmh,
                    elevationGainM = row.elevationGainM,
                    elevationLossM = row.elevationLossM,
                    sportType = row.sportType ?: "UNSPECIFIED"
                )
            )
            if (points.isNotEmpty()) activityDao.insertPoints(points)
            insertWaypoints(row, routeLinkId = null, activityLinkId = activityId)
        }
    }

    /** Inserts the row's waypoints_json with the ORIGINAL ids, linked to
     *  whatever the backup row says. IGNORE strategy: a mid-restore crash
     *  leaves some inserted; the row's source-exists probe makes a rerun
     *  skip the whole source anyway, so IGNORE just avoids PK collisions
     *  from pre-existing user pins sharing an id (never in practice). */
    private suspend fun insertWaypoints(
        row: BackupRepository.BackupRow,
        routeLinkId: String?,
        activityLinkId: String?
    ) {
        val json = row.waypointsJson ?: return
        if (json.isEmpty()) return
        waypointDao.insertAll(
            json.map { w ->
                WaypointEntity(
                    id = w.id,
                    name = w.name,
                    category = w.category,
                    lat = w.lat,
                    lon = w.lon,
                    elevationM = w.elevationM,
                    note = w.note,
                    createdAtEpochMs = w.createdAtEpochMs,
                    linkedRouteId = routeLinkId,
                    linkedActivityId = activityLinkId,
                    source = w.source
                )
            }
        )
    }

    /** Decodes a row's track_data into generic track points (route path —
     *  no per-point accuracy/speed was stored for routes at backup time). */
    private fun decodeTrackPoints(row: BackupRepository.BackupRow): List<com.nyasar.app.gpx.model.TrackPoint> =
        DeltaEncoder.decode(BackupRepository.byteaHexDecode(row.trackData), "")
            .map { com.nyasar.app.gpx.model.TrackPoint(it.lat, it.lon, it.elevationM, it.timestampMs) }

    /** Minimal GPX 1.1 writer — same shape as RouteRepository's drawn-route
     *  writer (trk/trkseg + ele per point). Deliberately NOT reusing that
     *  private method: package boundaries keep route import self-contained. */
    private fun writeRouteGpx(file: File, name: String, points: List<com.nyasar.app.gpx.model.TrackPoint>) {
        file.bufferedWriter().use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.newLine()
            writer.write("<gpx version=\"1.1\" creator=\"Nyasar\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            writer.newLine()
            writer.write("  <trk><name>${escapeXml(name)}</name><trkseg>")
            writer.newLine()
            points.forEach { p ->
                writer.write("    <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                p.elevationM?.let { writer.write("<ele>$it</ele>") }
                p.timestampEpochMs?.let {
                    writer.write("<time>${isoFormat.format(java.util.Date(it))}</time>")
                }
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

    /** Same UTC ISO-8601 shape GpxExporter writes (GPX <time> convention). */
    private val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private fun routeDaoFile(route: RouteEntity): File = File(route.localGpxFilePath)

    companion object {
        private const val TAG = "BackupManager"

        /** Singleton wiring — same one-DB-per-app pattern as the repos. */
        @Volatile private var instance: BackupManager? = null

        fun get(context: Context): BackupManager = instance ?: synchronized(this) {
            instance ?: run {
                val db = AppDatabase.get(context)
                BackupManager(
                    context = context.applicationContext,
                    db = db,
                    activityDao = db.activityDao(),
                    routeDao = db.routeDao(),
                    waypointDao = db.waypointDao()
                )
            }.also { instance = it }
        }

        /** Independent scope for auto-backup — deliberately NOT the
         *  RecordingService's serviceScope, which onDestroy cancels. The
         *  upload is a network round-trip that must survive the service
         *  being torn down right after stopSelf(); jobs here are finite
         *  (one per finished activity) and never cancelled mid-flight. */
        private val autoBackupScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        )

        /** Fire-and-forget auto-backup of one finished activity (Fase 2's
         *  "auto setelah activity selesai"). Offline at the trailhead is
         *  the NORMAL case — failures are logged, never surfaced as UI
         *  errors; the Settings "Backup sekarang" row is the manual retry
         *  once signal returns (the retry-queue design is still an open
         *  PROJECT_CONTEXT item, so there is deliberately no fake
         *  "menunggu sinyal" UI yet). */
        fun scheduleActivityBackup(context: Context, activityId: String) {
            autoBackupScope.launch {
                val result = try {
                    get(context).backupActivity(activityId)
                } catch (e: Exception) {
                    Log.e(TAG, "auto-backup crashed for $activityId", e)
                    null
                }
                when (result) {
                    is BackupResult.Success ->
                        Log.i(TAG, "auto-backup ok ($activityId): uploaded=${result.uploaded} skipped=${result.skipped}")
                    is BackupResult.Failure ->
                        Log.i(TAG, "auto-backup not completed ($activityId): ${result.kind}")
                    null -> {}
                }
            }
        }
    }
}
