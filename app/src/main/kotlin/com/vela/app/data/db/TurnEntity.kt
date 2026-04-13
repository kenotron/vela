package com.vela.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "turns")
data class TurnEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userMessage: String,
    val status: String,          // "running" | "complete" | "error"
    val timestamp: Long,
    val error: String? = null,
)
