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
     * Promote an SSH node to an amplifierd node in a single statement: flips nodeType, sets
     * url + token, writes machineId + endpoints JSON, and marks bootstrapStatus RUNNING.
     */
    @Query("UPDATE ssh_nodes SET nodeType = :type, url = :url, tailscale_url = :tailscaleUrl, token = :token, bootstrapStatus = :status, machine_id = :machineId, endpoints = :endpoints WHERE id = :id")
    suspend fun promoteToAmplifierd(
        id: String,
        type: String,
        url: String,
        tailscaleUrl: String,
        token: String,
        status: String,
        machineId: String,
        endpoints: String,
    )

    /** Update editable connection fields without touching token / bootstrapStatus / url. */
    @Query("UPDATE ssh_nodes SET label = :label, hosts = :hosts, port = :port, username = :username, workspace_dir = :workspaceDir WHERE id = :id")
    suspend fun updateConnection(
        id: String,
        label: String,
        hosts: String,
        port: Int,
        username: String,
        workspaceDir: String,
    )

    /** Write the machine_id discovered from /health. */
    @Query("UPDATE ssh_nodes SET machine_id = :machineId WHERE id = :id")
    suspend fun updateMachineId(id: String, machineId: String)

    /** Overwrite the entire endpoints JSON column. */
    @Query("UPDATE ssh_nodes SET endpoints = :endpoints WHERE id = :id")
    suspend fun updateEndpoints(id: String, endpoints: String)

    /** Persist last confirmed reachability: 1 = reachable, 0 = unreachable. */
    @Query("UPDATE ssh_nodes SET last_known_reachable = :reachable WHERE id = :id")
    suspend fun updateLastKnownReachable(id: String, reachable: Int)
}
