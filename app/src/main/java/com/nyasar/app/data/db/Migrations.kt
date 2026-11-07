package com.nyasar.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL semua migrasi skema Nyasar, disimpan sebagai DATA (daftar statement),
 * bukan terkubur di dalam objek anonim [Migration].
 *
 * Alasan pemisahan: dengan bentuk ini SQL-nya bisa dijalankan dan diperiksa
 * di JVM biasa (lihat MigrationSqlTest) memakai SQLite asli — sebelumnya
 * satu-satunya yang pernah mengeksekusi SQL ini adalah perangkat pengguna
 * saat aplikasi pertama kali dibuka setelah update. Kalau ada statement
 * salah, crash-nya baru ketahuan di HP orang.
 *
 * Aturan pemeliharaan: setiap kali `AppDatabase` menaikkan `version`,
 * tambahkan satu entri di sini DAN daftarkan di [ALL_MIGRATIONS].
 * MigrationSqlTest akan gagal kalau rantai versinya bolong.
 */
internal object MigrationsSql {

    /**
     * v6 -> v7 (waypoint linking + GPX merge): adds three columns to the
     * existing waypoints table — linkedRouteId, linkedActivityId, source.
     * An explicit [Migration] on purpose, NOT destructive: waypoints are
     * user data accumulated across hikes, and the change is a pure
     * additive ALTER TABLE, so there is nothing to lose by migrating and
     * everything to lose by rebuilding. DEFAULT 'USER' on the source
     * column stamps every pre-existing row as a user-created pin without
     * rewriting it.
     */
    val v6To7 = listOf(
        "ALTER TABLE waypoints ADD COLUMN linkedRouteId TEXT DEFAULT NULL",
        "ALTER TABLE waypoints ADD COLUMN linkedActivityId TEXT DEFAULT NULL",
        "ALTER TABLE waypoints ADD COLUMN source TEXT NOT NULL DEFAULT 'USER'"
    )

    /**
     * v7 -> v8 (photo feature removal): the activity_photos table is
     * dropped — the photo feature itself was removed from the app
     * entirely. Explicit [Migration], NOT destructive: dropping only
     * the photo table keeps routes/activities/points/waypoints
     * (real accumulated user data) fully intact. The photo FILES under
     * files/activity_photos/ are cleaned up once at first DB get()
     * (see cleanupLegacyPhotoFiles) so they don't linger as invisible
     * dead weight; app-private files, no other consumer exists.
     */
    val v7To8 = listOf(
        "DROP TABLE IF EXISTS activity_photos"
    )

    /**
     * v8 -> v9 (offline publish queue, "save = publish" konsep): new
     * pending_publishes table — one row per ACTIVITY still owing the
     * cloud a publish (no account yet / offline at the trailhead).
     * Pure additive CREATE TABLE. NOTE: intentionally creates the
     * schema AS SHIPPED in the v9 release (activityId PK, no
     * sourceKind) — v9 already shipped to devices, so this path must
     * reproduce it byte-for-byte before [v9To10] transforms it.
     */
    val v8To9 = listOf(
        "CREATE TABLE IF NOT EXISTS pending_publishes (" +
            "activityId TEXT NOT NULL PRIMARY KEY NOT NULL, " +
            "difficulty TEXT, " +
            "difficultyDescription TEXT, " +
            "trailType TEXT, " +
            "description TEXT, " +
            "isPublic INTEGER NOT NULL, " +
            "queuedAtEpochMs INTEGER NOT NULL)"
    )

    /**
     * v9 -> v10 (queue learns library routes): pending_publishes is
     * re-keyed from activityId to a polymorphic sourceId + sourceKind
     * ("activity" | "route") so GPX/drawn library-route publishes can
     * queue offline too. Existing queued rows are all activities, so
     * they copy over with sourceKind='activity' — nothing is lost.
     * Room wraps each Migration in a transaction, so a mid-step crash
     * rolls the whole step back.
     */
    val v9To10 = listOf(
        "ALTER TABLE pending_publishes RENAME TO pending_publishes_old",
        "CREATE TABLE IF NOT EXISTS pending_publishes (" +
            "sourceId TEXT NOT NULL PRIMARY KEY NOT NULL, " +
            "sourceKind TEXT NOT NULL, " +
            "difficulty TEXT, " +
            "difficultyDescription TEXT, " +
            "trailType TEXT, " +
            "description TEXT, " +
            "isPublic INTEGER NOT NULL, " +
            "queuedAtEpochMs INTEGER NOT NULL)",
        "INSERT INTO pending_publishes (sourceId, sourceKind, difficulty, " +
            "difficultyDescription, trailType, description, isPublic, queuedAtEpochMs) " +
            "SELECT activityId, 'activity', difficulty, difficultyDescription, " +
            "trailType, description, isPublic, queuedAtEpochMs FROM pending_publishes_old",
        "DROP TABLE pending_publishes_old"
    )

    /**
     * v10 -> v11 (backtrack pulih penuh): menambah satu kolom nullable
     * backtrackOf ke activities — id aktivitas lama yang jejaknya dipakai
     * sebagai garis pemandu sesi rekam ini. Pure additive ALTER TABLE,
     * jadi nontransaksional tetap aman; baris lama otomatis NULL (bukan
     * backtrack), sesuai perilaku sebelumnya.
     *
     * Room hanya membandingkan nilai default kalau ENTITY mendeklarasikan
     * @ColumnInfo(defaultValue=…) (b/136019383), jadi "DEFAULT NULL" di sini
     * tidak dianggap beda dengan entitas yang memang tanpa default.
     */
    val v10To11 = listOf(
        "ALTER TABLE activities ADD COLUMN backtrackOf TEXT DEFAULT NULL"
    )
}

/** Bungkus daftar SQL jadi [Migration] Room. Dipakai [ALL_MIGRATIONS]. */
private fun migration(
    startVersion: Int,
    endVersion: Int,
    sql: List<String>
): Migration = object : Migration(startVersion, endVersion) {
    override fun migrate(db: SupportSQLiteDatabase) {
        sql.forEach { db.execSQL(it) }
    }
}

/**
 * Semua migrasi non-destruktif, dipasang lewat
 * `addMigrations(*ALL_MIGRATIONS)` di [AppDatabase.get].
 *
 * v1..v5 sengaja TIDAK ada di sini: versi-versi itu pre-release dan
 * ditangani `fallbackToDestructiveMigrationFrom(1..5)`.
 */
internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    migration(6, 7, MigrationsSql.v6To7),
    migration(7, 8, MigrationsSql.v7To8),
    migration(8, 9, MigrationsSql.v8To9),
    migration(9, 10, MigrationsSql.v9To10),
    migration(10, 11, MigrationsSql.v10To11)
)
