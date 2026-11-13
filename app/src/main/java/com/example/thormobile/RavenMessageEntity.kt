package com.example.thormobile

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "raven_messages")
data class RavenMessageEntity(
    @PrimaryKey val msgId: String,
    val folder: String, // "RECEIVED" or "SENT"
    val sender: String,
    val title: String,
    val body: String,
    val linksJson: String = "[]",
    val attachmentUri: String = "",
    val attachmentName: String = "",
    val attachmentMime: String = "",
    val stagedMediaId: String = "",
    val stagedExpiresUnix: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val timestampEt: String = "",
    val isRead: Boolean = false,
    val syncStatus: String = "SYNCED" // "SYNCED" or "QUEUED_OFFLINE"
)
