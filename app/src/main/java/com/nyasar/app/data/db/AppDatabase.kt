package com.nyasar.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RouteEntity::class, ActivityEntity::class, ActivityPointEntity::class, WaypointEntity::class, PendingPublishEntity::class],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao
    abstract fun activityDao(): ActivityDao
    abstract fun waypointDao(): WaypointDao
    abstract fun pendingPublishDao(): PendingPublishDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // Semua definisi migrasi (beserta SQL dan alasannya) pindah ke
        // Migrations.kt supaya SQL-nya bisa diuji di JVM tanpa emulator —
        // lihat MigrationSqlTest.

        /** One-time cleanup of pre-removal photo files. Idempotent and
         *  cheap when the directory doesn't exist (deleteRecursively on a
         *  missing File is a no-op returning false). Runs on the first
         *  get() after app start; guarded by a @Volatile flag so repeated
         *  get() calls (every repository) don't re-scan the dir. */
        @Volatile private var legacyPhotoFilesCleaned = false

        private fun cleanupLegacyPhotoFiles(context: Context) {
            if (legacyPhotoFilesCleaned) return
            synchronized(this) {
                if (legacyPhotoFilesCleaned) return
                try {
                    java.io.File(context.filesDir, "activity_photos").deleteRecursively()
                } catch (_: Exception) {
                    // Best-effort: leftover files are invisible to the user
                    // and harmless; never block DB startup on this.
                }
                legacyPhotoFilesCleaned = true
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nyasar.db"
                )
                    // v1 -> v2: added activities/activity_points tables.
                    // v2 -> v3 (P3E1): added RouteEntity.lowestElevationM.
                    // v3 -> v4 (P3E2): added waypoints table (user-created
                    // map waypoints).
                    // v4 -> v5 (P3H): added activity_photos table.
                    // v5 -> v6: added ActivityEntity.sportType.
                    // Still pre-release, so destructive migration remains
                    // acceptable for these oldest versions — same reasoning
                    // as the v1->v2 comment. From v6 on, user waypoint data
                    // is real accumulated content: v6 -> v7 was an explicit
                    // Migration (see MigrationsSql.v6To7); v7 -> v8 drops the
                    // photo table via explicit Migration (v7To8) — also
                    // non-destructive to the rest of the data.
                    // v8 -> v9 adds pending_publishes (explicit, v8To9);
                    // v9 -> v10 re-keys it for library-route queuing
                    // (explicit, v9To10); v10 -> v11 adds
                    // activities.backtrackOf (explicit, v10To11).
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build().also {
                        instance = it
                        cleanupLegacyPhotoFiles(context)
                    }
            }
    }
}
