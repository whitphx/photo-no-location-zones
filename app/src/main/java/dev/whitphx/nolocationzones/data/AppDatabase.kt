package dev.whitphx.nolocationzones.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ZoneEntity::class, PendingStripEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun zoneDao(): ZoneDao
    abstract fun pendingStripDao(): PendingStripDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nlz.db")
                // No migrations are maintained during initial development. Both fallbacks below
                // mean: if the on-device DB has a different schema (older, newer, or just
                // incompatible), Room drops every table and recreates them from this version 1
                // definition. Acceptable here because zones and pending strips are personal data
                // the user can re-create.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
