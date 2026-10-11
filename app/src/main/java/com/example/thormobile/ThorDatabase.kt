package com.example.thormobile

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [OutboxAudio::class], version = 1, exportSchema = false)
abstract class ThorDatabase : RoomDatabase() {

    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var INSTANCE: ThorDatabase? = null

        fun getDatabase(context: Context): ThorDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ThorDatabase::class.java,
                    "thor_local_memory.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}