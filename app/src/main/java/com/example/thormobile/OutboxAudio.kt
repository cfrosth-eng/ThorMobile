package com.example.thormobile

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outbox_audio")
data class OutboxAudio(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val audioData: ByteArray,
    val status: String = "PENDING"
)