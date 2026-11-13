package com.example.thormobile

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OutboxAudio::class, RavenMessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ThorDatabase : RoomDatabase() {

    abstract fun outboxDao(): OutboxDao
    abstract fun ravenMessageDao(): RavenMessageDao

    companion object {
        @Volatile
        private var INSTANCE: ThorDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `raven_messages` (
                        `msgId` TEXT NOT NULL,
                        `folder` TEXT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `linksJson` TEXT NOT NULL,
                        `attachmentUri` TEXT NOT NULL,
                        `attachmentName` TEXT NOT NULL,
                        `attachmentMime` TEXT NOT NULL,
                        `stagedMediaId` TEXT NOT NULL,
                        `stagedExpiresUnix` INTEGER NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `timestampEt` TEXT NOT NULL,
                        `isRead` INTEGER NOT NULL,
                        `syncStatus` TEXT NOT NULL,
                        PRIMARY KEY(`msgId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): ThorDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ThorDatabase::class.java,
                    "thor_local_memory.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
