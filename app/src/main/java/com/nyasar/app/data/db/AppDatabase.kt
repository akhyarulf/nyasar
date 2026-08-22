package com.nyasar.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RouteEntity::class, ActivityEntity::class, ActivityPointEntity::class, WaypointEntity::class, ActivityPhotoEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao
    abstract fun activityDao(): ActivityDao
    abstract fun waypointDao(): WaypointDao
    abstract fun activityPhotoDao(): ActivityPhotoDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

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
                    // map waypoints, separate from GPX-parsed ones).
                    // v4 -> v5 (P3H): added activity_photos table.
                    // Still pre-release, so destructive migration remains
                    // acceptable — same reasoning as the v1->v2 comment.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                    .build().also { instance = it }
            }
    }
}
