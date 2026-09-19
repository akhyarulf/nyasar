package com.nyasar.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RouteEntity::class, ActivityEntity::class, ActivityPointEntity::class, WaypointEntity::class, PendingPublishEntity::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao
    abstract fun activityDao(): ActivityDao
    abstract fun waypointDao(): WaypointDao
    abstract fun pendingPublishDao(): PendingPublishDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

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
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE waypoints ADD COLUMN linkedRouteId TEXT DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE waypoints ADD COLUMN linkedActivityId TEXT DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE waypoints ADD COLUMN source TEXT NOT NULL DEFAULT 'USER'"
                )
            }
        }

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
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS activity_photos")
            }
        }

        /**
         * v8 -> v9 (offline publish queue, "save = publish" konsep): new
         * pending_publishes table — one row per ACTIVITY still owing the
         * cloud a publish (no account yet / offline at the trailhead).
         * Pure additive CREATE TABLE. NOTE: intentionally creates the
         * schema AS SHIPPED in the v9 release (activityId PK, no
         * sourceKind) — v9 already shipped to devices, so this path must
         * reproduce it byte-for-byte before MIGRATION_9_10 transforms it.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_publishes (" +
                        "activityId TEXT NOT NULL PRIMARY KEY NOT NULL, " +
                        "difficulty TEXT, " +
                        "difficultyDescription TEXT, " +
                        "trailType TEXT, " +
                        "description TEXT, " +
                        "isPublic INTEGER NOT NULL, " +
                        "queuedAtEpochMs INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v9 -> v10 (queue learns library routes): pending_publishes is
         * re-keyed from activityId to a polymorphic sourceId + sourceKind
         * ("activity" | "route") so GPX/drawn library-route publishes can
         * queue offline too. Existing queued rows are all activities, so
         * they copy over with sourceKind='activity' — nothing is lost.
         * Room wraps each Migration in a transaction, so a mid-step crash
         * rolls the whole step back.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_publishes RENAME TO pending_publishes_old")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_publishes (" +
                        "sourceId TEXT NOT NULL PRIMARY KEY NOT NULL, " +
                        "sourceKind TEXT NOT NULL, " +
                        "difficulty TEXT, " +
                        "difficultyDescription TEXT, " +
                        "trailType TEXT, " +
                        "description TEXT, " +
                        "isPublic INTEGER NOT NULL, " +
                        "queuedAtEpochMs INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO pending_publishes (sourceId, sourceKind, difficulty, " +
                        "difficultyDescription, trailType, description, isPublic, queuedAtEpochMs) " +
                        "SELECT activityId, 'activity', difficulty, difficultyDescription, " +
                        "trailType, description, isPublic, queuedAtEpochMs FROM pending_publishes_old"
                )
                db.execSQL("DROP TABLE pending_publishes_old")
            }
        }

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
                    // Migration (see MIGRATION_6_7); v7 -> v8 drops the
                    // photo table via explicit Migration (see MIGRATION_7_8)
                    // — also non-destructive to the rest of the data.
                    // v8 -> v9 adds pending_publishes (explicit, see above);
                    // v9 -> v10 re-keys it for library-route queuing
                    // (explicit, see MIGRATION_9_10).
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .build().also {
                        instance = it
                        cleanupLegacyPhotoFiles(context)
                    }
            }
    }
}
