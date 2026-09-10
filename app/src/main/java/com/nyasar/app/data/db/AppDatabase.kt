package com.nyasar.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RouteEntity::class, ActivityEntity::class, ActivityPointEntity::class, WaypointEntity::class, ActivityPhotoEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao
    abstract fun activityDao(): ActivityDao
    abstract fun waypointDao(): WaypointDao
    abstract fun activityPhotoDao(): ActivityPhotoDao

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
                    // is real accumulated content: v6 -> v7 is an explicit
                    // Migration (see MIGRATION_6_7).
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                    .addMigrations(MIGRATION_6_7)
                    .build().also { instance = it }
            }
    }
}
