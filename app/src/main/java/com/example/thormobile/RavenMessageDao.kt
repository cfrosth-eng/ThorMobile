package com.example.thormobile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RavenMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(message: RavenMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(messages: List<RavenMessageEntity>)

    @Query("SELECT * FROM raven_messages WHERE folder = :folder ORDER BY timestampMs DESC")
    fun getMessagesByFolder(folder: String): List<RavenMessageEntity>

    @Query("SELECT * FROM raven_messages ORDER BY timestampMs DESC LIMIT 200")
    fun getAllMessages(): List<RavenMessageEntity>

    @Query("SELECT COUNT(*) FROM raven_messages WHERE folder = 'RECEIVED' AND isRead = 0")
    fun getUnreadReceivedCount(): Int

    @Query("SELECT COUNT(*) FROM raven_messages WHERE folder = :folder")
    fun getCountByFolder(folder: String): Int

    @Query("UPDATE raven_messages SET isRead = 1 WHERE msgId = :msgId")
    fun markRead(msgId: String)

    @Query("UPDATE raven_messages SET syncStatus = :status WHERE msgId = :msgId")
    fun updateSyncStatus(msgId: String, status: String)

    @Query("SELECT * FROM raven_messages WHERE syncStatus = 'QUEUED_OFFLINE' ORDER BY timestampMs ASC")
    fun getPendingOfflineMessages(): List<RavenMessageEntity>

    @Query("DELETE FROM raven_messages WHERE msgId = :msgId")
    fun deleteById(msgId: String)
}
