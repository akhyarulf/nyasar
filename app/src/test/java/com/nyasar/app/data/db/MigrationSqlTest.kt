package com.nyasar.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Migration tests that run on the JVM (no emulator, no Robolectric).
 *
 * Why not MigrationTestHelper: it needs the exported schema JSON for the
 * starting version, and this project has always had `exportSchema = false` —
 * that JSON (including Room's identity hash) can only be produced by the
 * compiler, so there is no v6..v10 schema to hand it. Instead the old-version
 * tables are recreated here from the entities as they stood at that version,
 * and the REAL migration objects from [ALL_MIGRATIONS] are executed against a
 * real SQLite database through a thin JDBC-backed [SupportSQLiteDatabase]
 * stub. That covers the failure modes that actually hurt: broken SQL, lost
 * user rows, and a DB version bump with a missing migration.
 */
class MigrationSqlTest {

    @Test
    fun `registered migrations form one unbroken chain ending at the DB version`() {
        val dbVersion = dbVersionFromSource()
        val sorted = ALL_MIGRATIONS.sortedBy { it.startVersion }
        assertTrue("harus ada migrasi terdaftar", sorted.isNotEmpty())
        assertEquals(
            "migrasi non-destruktif dimulai dari v6 (v1..v5 ditangani fallbackToDestructiveMigrationFrom)",
            6,
            sorted.first().startVersion
        )
        var version = sorted.first().startVersion
        for (migration in sorted) {
            assertEquals("versi awal harus bersambung dengan migrasi sebelumnya", version, migration.startVersion)
            assertEquals("satu migrasi = satu langkah versi", migration.startVersion + 1, migration.endVersion)
            version = migration.endVersion
        }
        assertEquals(
            "versi terakhir di ALL_MIGRATIONS harus sama dengan @Database(version = …) di AppDatabase.kt",
            dbVersion,
            version
        )
    }

    @Test
    fun `v6 to latest upgrade keeps user rows and ends on the current schema`() = withDb { db ->
        db.createV6Schema()
        db.exec(
            "INSERT INTO routes (id, name, localGpxFilePath, distanceMeters, elevationGainM, " +
                "elevationLossM, highestElevationM, lowestElevationM, waypointCount, " +
                "importedAtEpochMs, lastOpenedAtEpochMs) " +
                "VALUES ('r1', 'Rinjani', '/files/r1.gpx', 12000.0, 900.0, 880.0, 3726.0, 600.0, 2, 11, 12)",
            "INSERT INTO activities (id, routeId, name, startedAtEpochMs, endedAtEpochMs, status, " +
                "distanceMeters, movingTimeMs, elapsedTimeMs, avgSpeedKmh, maxSpeedKmh, " +
                "elevationGainM, elevationLossM, sportType) " +
                "VALUES ('a1', 'r1', 'Hike', 1, 2, 'completed', 1200.0, 600000, 700000, 4.1, 6.0, 120.0, 90.0, 'HIKING')",
            "INSERT INTO activity_points (activityId, sequence, lat, lon, elevationM, speedMps, " +
                "accuracyMeters, timestampMs) VALUES ('a1', 0, -8.4, 116.4, 1200.0, 1.2, 5.0, 10)",
            "INSERT INTO waypoints (id, name, category, lat, lon, elevationM, note, createdAtEpochMs) " +
                "VALUES ('w1', 'Pos 1', 'SUMMIT', -8.4, 116.4, 1200.0, NULL, 99)",
            "INSERT INTO activity_photos (id, activityId, filePath, createdAtEpochMs) " +
                "VALUES ('p1', 'a1', '/files/a1.jpg', 5)"
        )

        db.migrateUpTo(dbVersionFromSource())

        assertEquals("activity harus selamat", 1, db.rowCount("activities"))
        assertEquals(
            listOf(listOf<Any?>("a1", "Hike", "HIKING", null)),
            db.query("SELECT id, name, sportType, backtrackOf FROM activities")
        )
        assertEquals("titik GPS harus selamat", 1, db.rowCount("activity_points"))
        assertEquals(
            listOf(listOf<Any?>("Pos 1", "USER", null)),
            db.query("SELECT name, source, linkedRouteId FROM waypoints")
        )
        assertEquals("route harus selamat", 1, db.rowCount("routes"))
        assertFalse("activity_photos harus hilang sejak v8", db.tableExists("activity_photos"))
        assertTrue("pending_publishes harus ada sejak v9", db.tableExists("pending_publishes"))
        assertEquals(
            listOf(
                "sourceId", "sourceKind", "difficulty", "difficultyDescription",
                "trailType", "description", "isPublic", "queuedAtEpochMs"
            ),
            db.columns("pending_publishes").keys.toList()
        )
        assertEquals("antrean publish baru harus kosong", 0, db.rowCount("pending_publishes"))
    }

    private fun Connection.migrateUpTo(target: Int) {
        var version = 6
        while (version < target) {
            migrateFrom(version)
            version++
        }
    }

    @Test
    fun `migration 10 to 11 adds a nullable backtrackOf without touching existing activities`() = withDb { db ->
        // Bentuk tabel v6..v10 identik: sportType sudah ada sejak v6.
        db.exec(
            V6_ACTIVITIES,
            "INSERT INTO activities (id, routeId, name, startedAtEpochMs, endedAtEpochMs, status, " +
                "distanceMeters, movingTimeMs, elapsedTimeMs, avgSpeedKmh, maxSpeedKmh, " +
                "elevationGainM, elevationLossM, sportType) " +
                "VALUES ('a1', NULL, 'Hike', 1, 2, 'completed', 1200.0, 600000, 700000, NULL, NULL, NULL, NULL, 'HIKING')"
        )

        db.migrateFrom(10)

        val columns = db.columns("activities")
        assertEquals("kolom lain tidak boleh berubah", 15, columns.size)
        assertEquals(Column("TEXT", false, "NULL", false), columns["backtrackOf"])
        assertEquals(
            listOf(listOf<Any?>("a1", null)),
            db.query("SELECT id, backtrackOf FROM activities")
        )
        // Kolomnya harus benar-benar bisa menyimpan id aktivitas lain.
        db.exec("UPDATE activities SET backtrackOf = 'a2' WHERE id = 'a1'")
        assertEquals(listOf(listOf<Any?>("a2")), db.query("SELECT backtrackOf FROM activities"))
    }

    @Test
    fun `migration 9 to 10 re-keys the publish queue and preserves queued rows`() = withDb { db ->
        db.migrateFrom(8) // bikin pending_publishes persis versi v9
        db.exec(
            "INSERT INTO pending_publishes VALUES ('act-1', 'EASY', NULL, 'LOOP', 'catatan', 1, 111)",
            "INSERT INTO pending_publishes VALUES ('act-2', NULL, NULL, NULL, NULL, 0, 222)"
        )

        db.migrateFrom(9)

        assertEquals(
            listOf(
                "sourceId", "sourceKind", "difficulty", "difficultyDescription",
                "trailType", "description", "isPublic", "queuedAtEpochMs"
            ),
            db.columns("pending_publishes").keys.toList()
        )
        assertEquals(
            listOf(
                listOf<Any?>("act-1", "activity", "EASY", 1L, 111L),
                listOf<Any?>("act-2", "activity", null, 0L, 222L)
            ),
            db.query(
                "SELECT sourceId, sourceKind, difficulty, isPublic, queuedAtEpochMs " +
                    "FROM pending_publishes ORDER BY sourceId"
            )
        )
        assertFalse("tabel sementara harus ikut terhapus", db.tableExists("pending_publishes_old"))
    }

    @Test
    fun `migration 6 to 7 stamps existing waypoints as user pins without linking them`() = withDb { db ->
        db.exec(
            V6_WAYPOINTS,
            "INSERT INTO waypoints VALUES ('w1', 'Pos 1', 'SUMMIT', -8.4, 116.4, 1200.0, NULL, 99)"
        )

        db.migrateFrom(6)

        val columns = db.columns("waypoints")
        assertEquals(Column("TEXT", false, "NULL", false), columns["linkedRouteId"])
        assertEquals(Column("TEXT", false, "NULL", false), columns["linkedActivityId"])
        assertEquals(Column("TEXT", true, "'USER'", false), columns["source"])
        assertEquals(
            listOf(listOf<Any?>("Pos 1", "USER", null)),
            db.query("SELECT name, source, linkedRouteId FROM waypoints")
        )
    }

    @Test
    fun `migration 7 to 8 drops the photo table only`() = withDb { db ->
        db.createV6Schema()

        db.migrateFrom(7)

        assertFalse("activity_photos harus hilang", db.tableExists("activity_photos"))
        assertTrue("routes harus tetap ada", db.tableExists("routes"))
        assertTrue("activities harus tetap ada", db.tableExists("activities"))
        assertTrue("waypoints harus tetap ada", db.tableExists("waypoints"))
    }

    // ---------------------------------------------------------------------
    // Helper: JDBC <-> Room
    // ---------------------------------------------------------------------

    private fun withDb(block: (Connection) -> Unit) {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    /** Salinan SQL dari @Database(version = …) — satu-satunya angka yang tidak
     *  bisa dibaca lewat reflection (anotasi Room ber-retention CLASS). */
    private fun dbVersionFromSource(): Int {
        val candidates = listOf(
            File("src/main/java/com/nyasar/app/data/db/AppDatabase.kt"),
            File("app/src/main/java/com/nyasar/app/data/db/AppDatabase.kt")
        )
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            fail("AppDatabase.kt tidak ditemukan dari direktori kerja ${File(".").absolutePath}")
            return 11
        }
        val match = Regex("""version\s*=\s*(\d+)""").find(file.readText())
        if (match == null) {
            fail("@Database(version = …) tidak ditemukan di ${file.path}")
            return 11
        }
        return match.groupValues[1].toInt()
    }

    private fun Connection.migrateFrom(startVersion: Int) {
        val migration = ALL_MIGRATIONS.firstOrNull { it.startVersion == startVersion }
        if (migration == null) {
            fail("Tidak ada migrasi terdaftar dari versi $startVersion di ALL_MIGRATIONS")
            return
        }
        migration.migrate(supportDatabase(this))
    }

    /** Stub tipis [SupportSQLiteDatabase] di atas JDBC. Semua migrasi Nyasar
     *  hanya memakai execSQL; kalau nanti ada yang memakai API lain, test ini
     *  gagal dengan pesan jelas alih-alih lolos diam-diam. */
    private fun supportDatabase(db: Connection): SupportSQLiteDatabase {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args!![0] as String
                    if (args.size > 1) {
                        @Suppress("UNCHECKED_CAST")
                        val bindArgs = args[1] as Array<Any?>
                        db.prepareStatement(sql).use { statement ->
                            bindArgs.forEachIndexed { index, value ->
                                statement.setObject(index + 1, value)
                            }
                            statement.execute()
                        }
                    } else {
                        db.createStatement().use { it.execute(sql) }
                    }
                    null
                }
                "toString" -> "SupportSQLiteDatabase(JDBC)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException(
                    "Migrasi memakai SupportSQLiteDatabase.${method.name} yang belum didukung stub test ini"
                )
            }
        }
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler
        ) as SupportSQLiteDatabase
    }

    private fun Connection.exec(vararg statements: String) {
        createStatement().use { statement ->
            statements.forEach { statement.execute(it) }
        }
    }

    private fun Connection.query(sql: String): List<List<Any?>> {
        val rows = mutableListOf<List<Any?>>()
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                val width = rs.metaData.columnCount
                while (rs.next()) {
                    // Angka disamakan ke Long: sqlite-jdbc bisa mengembalikan
                    // Integer atau Long tergantung besar nilainya, dan assert
                    // di sini tidak boleh bergantung pada detail itu.
                    rows.add(
                        (1..width).map { column ->
                            rs.getObject(column).let { if (it is Number) it.toLong() else it }
                        }
                    )
                }
            }
        }
        return rows
    }

    private data class Column(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKey: Boolean
    )

    private fun Connection.columns(table: String): Map<String, Column> {
        val result = LinkedHashMap<String, Column>()
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    result[rs.getString("name")] = Column(
                        type = rs.getString("type"),
                        notNull = rs.getInt("notnull") != 0,
                        defaultValue = rs.getString("dflt_value"),
                        primaryKey = rs.getInt("pk") != 0
                    )
                }
            }
        }
        return result
    }

    private fun Connection.rowCount(table: String): Int =
        (query("SELECT count(*) FROM `$table`").single().single() as Number).toInt()

    private fun Connection.tableExists(table: String): Boolean =
        (query("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'")
            .single().single() as Number).toInt() > 0

    private fun Connection.createV6Schema() {
        exec(V6_ROUTES, V6_ACTIVITIES, V6_ACTIVITY_POINTS, V6_WAYPOINTS, V6_ACTIVITY_PHOTOS)
    }

    private companion object {
        /** Bentuk tabel routes sejak v2 (tidak berubah sampai v11). */
        const val V6_ROUTES = """
            CREATE TABLE IF NOT EXISTS `routes` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `localGpxFilePath` TEXT NOT NULL,
                `distanceMeters` REAL NOT NULL, `elevationGainM` REAL, `elevationLossM` REAL,
                `highestElevationM` REAL, `lowestElevationM` REAL, `waypointCount` INTEGER NOT NULL,
                `importedAtEpochMs` INTEGER NOT NULL, `lastOpenedAtEpochMs` INTEGER,
                PRIMARY KEY(`id`)
            )
        """

        /** activities v6..v10 (sportType masuk di v6, backtrackOf baru di v11). */
        const val V6_ACTIVITIES = """
            CREATE TABLE IF NOT EXISTS `activities` (
                `id` TEXT NOT NULL, `routeId` TEXT, `name` TEXT NOT NULL,
                `startedAtEpochMs` INTEGER NOT NULL, `endedAtEpochMs` INTEGER,
                `status` TEXT NOT NULL, `distanceMeters` REAL NOT NULL,
                `movingTimeMs` INTEGER NOT NULL, `elapsedTimeMs` INTEGER NOT NULL,
                `avgSpeedKmh` REAL, `maxSpeedKmh` REAL, `elevationGainM` REAL,
                `elevationLossM` REAL, `sportType` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
        """

        const val V6_ACTIVITY_POINTS = """
            CREATE TABLE IF NOT EXISTS `activity_points` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `activityId` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL,
                `elevationM` REAL, `speedMps` REAL, `accuracyMeters` REAL NOT NULL,
                `timestampMs` INTEGER NOT NULL
            )
        """

        /** waypoints v6 (sebelum linking + source). */
        const val V6_WAYPOINTS = """
            CREATE TABLE IF NOT EXISTS `waypoints` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL,
                `lat` REAL NOT NULL, `lon` REAL NOT NULL, `elevationM` REAL, `note` TEXT,
                `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )
        """

        /** Tabel fitur foto (v4..v7) — dihapus di v8. */
        const val V6_ACTIVITY_PHOTOS = """
            CREATE TABLE IF NOT EXISTS `activity_photos` (
                `id` TEXT NOT NULL, `activityId` TEXT NOT NULL, `filePath` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )
        """
    }
}
