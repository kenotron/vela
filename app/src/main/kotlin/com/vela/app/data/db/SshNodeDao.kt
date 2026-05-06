package com.vela.app.data.db

    import androidx.room.*
    import kotlinx.coroutines.flow.Flow

    @Dao
    interface SshNodeDao {
        @Query("SELECT * FROM ssh_nodes ORDER BY addedAt ASC")
        fun getAllNodes(): Flow<List<SshNodeEntity>>

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insert(node: SshNodeEntity)

        @Query("DELETE FROM ssh_nodes WHERE id = :id")
        suspend fun delete(id: String)

        @Query("SELECT * FROM ssh_nodes WHERE id = :id")
        suspend fun getById(id: String): SshNodeEntity?

        /** Update only the bootstrap lifecycle column. */
        @Query("UPDATE ssh_nodes SET bootstrapStatus = :status WHERE id = :id")
        suspend fun updateBootstrapStatus(id: String, status: String)

        /**
         * Promote an SSH node to an amplifierd node in a single statement: flips
         * nodeType, sets url + token, and marks bootstrapStatus.
         */
        @Query("UPDATE ssh_nodes SET nodeType = :type, url = :url, tailscale_url = :tailscaleUrl, token = :token, bootstrapStatus = :status WHERE id = :id")
        suspend fun promoteToAmplifierd(id: String, type: String, url: String, tailscaleUrl: String, token: String, status: String)

        /** Update editable connection fields without touching token / bootstrapStatus / url. */
        @Query("UPDATE ssh_nodes SET label = :label, hosts = :hosts, port = :port, username = :username, workspace_dir = :workspaceDir WHERE id = :id")
        suspend fun updateConnection(id: String, label: String, hosts: String, port: Int, username: String, workspaceDir: String)
    }
    