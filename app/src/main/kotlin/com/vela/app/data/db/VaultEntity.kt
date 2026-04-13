package com.vela.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vaults")
data class VaultEntity(
    @PrimaryKey val id: String,
    val name: String,
    val localPath: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)
