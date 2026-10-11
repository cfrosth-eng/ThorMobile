package com.example.thormobile

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OutboxDao {
    // Inserts a new audio chunk into the database when you are offline
    @Insert
    fun insertAudio(audio: OutboxAudio)

    // Grabs all pending audio chunks in chronological order to send to the cloud
    @Query("SELECT * FROM outbox_audio WHERE status = 'PENDING' ORDER BY timestamp ASC")
    fun getPendingAudio(): List<OutboxAudio>

    // Deletes the audio chunk from the phone's hard drive after a successful upload
    @Delete
    fun deleteAudio(audio: OutboxAudio)
}