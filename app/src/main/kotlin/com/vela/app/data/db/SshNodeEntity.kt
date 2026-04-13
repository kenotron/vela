    package com.vela.app.data.db

    import androidx.room.Entity
    import androidx.room.PrimaryKey

    @Entity(tableName = "ssh_nodes")
    data class SshNodeEntity(
        @PrimaryKey val id: String,
        val label: String,
        val host: String,
        val port: Int,
        val username: String,
        val addedAt: Long,
    )
    