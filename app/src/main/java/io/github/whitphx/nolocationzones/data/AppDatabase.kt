package io.github.whitphx.nolocationzones.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ZoneEntity::class, PendingStripEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun zoneDao(): ZoneDao
    abstract fun pendingStripDao(): PendingStripDao

    companion object {
        /**
         * Adds the `mime_type` column to `pending_strips` for the photos+videos pipeline. Existing
         * rows are images (the queue couldn't hold anything else before v2), so leaving the new
         * column NULL is safe — the strip dispatcher reads `null` as "not a video", matching the
         * pre-v2 behaviour.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_strips ADD COLUMN mime_type TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nlz.db")
                .addMigrations(MIGRATION_1_2)
                // Last-resort fallback for any pre-development build that diverged from these
                // versioned migrations — drops every table and recreates from the current schema.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
