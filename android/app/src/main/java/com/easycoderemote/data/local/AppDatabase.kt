package com.easycoderemote.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        PartEntity::class,
        PendingItemEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun partDao(): PartDao
    abstract fun pendingItemDao(): PendingItemDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 → v2: add the message creation-time column used for transcript ordering. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN timeCreated INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 → v3: add message-local agent/model badge columns (backfill on next ingest). */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN agent TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN providerID TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN modelID TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ecr.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
