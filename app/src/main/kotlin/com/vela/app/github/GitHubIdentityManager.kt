package com.vela.app.github

import com.vela.app.data.db.GitHubIdentityDao
import com.vela.app.data.db.GitHubIdentityEntity
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubIdentity(
    val id: String,
    val label: String,
    val username: String,
    val avatarUrl: String,
    val token: String,
    val tokenType: String,   // "pat" | "oauth"
    val scopes: String,
    val addedAt: Long,
    val isDefault: Boolean,
)

data class DeviceFlowState(
    val deviceCode: String,
    val userCode: String,           // e.g. "ABCD-1234" — user enters this at github.com/login/device
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int,              // polling interval in seconds
)

sealed class GitHubConnectResult {
    data class Success(val identity: GitHubIdentity) : GitHubConnectResult()
    data class Error(val message: String) : GitHubConnectResult()
    object Pending : GitHubConnectResult()      // device flow: user hasn't completed yet
    object Expired : GitHubConnectResult()      // device code expired
}

@Singleton
class GitHubIdentityManager @Inject constructor(
    private val dao: GitHubIdentityDao,
    private val client: OkHttpClient,
) {
    fun allFlow(): Flow<List<GitHubIdentityEntity>> = dao.getAll()

    suspend fun getAll(): List<GitHubIdentity> = dao.getAllSync().map { it.toDomain() }

    suspend fun getDefault(): GitHubIdentity? = dao.getDefault()?.toDomain()

    suspend fun getById(id: String): GitHubIdentity? = dao.getById(id)?.toDomain()

    suspend fun findByLabel(label: String): GitHubIdentity? =
        dao.getAllSync().firstOrNull { it.label.equals(label, ignoreCase = true) }?.toDomain()

    /** Validate a PAT, fetch user info, and store the identity. */
    suspend fun connectWithPat(label: String, token: String): GitHubConnectResult {
        return try {
            val user = fetchUser(token)
                ?: return GitHubConnectResult.Error("Invalid token or no network access")
            val scopes = fetchScopes(token)
            val entity = GitHubIdentityEntity(
                id        = UUID.randomUUID().toString(),
                label     = label.ifBlank { user.optString("login", "GitHub") },
                username  = user.getString("login"),
                avatarUrl = user.optString("avatar_url", ""),
                token     = token,
                tokenType = "pat",
                scopes    = scopes,
                addedAt   = System.currentTimeMillis(),
                isDefault = dao.getAllSync().isEmpty(),   // first identity becomes default
            )
            dao.upsert(entity)
            GitHubConnectResult.Success(entity.toDomain())
        } catch (e: Exception) {
            GitHubConnectResult.Error(e.message ?: "Unknown error")
        }
    }

    /** Start GitHub device authorization flow. Returns state the UI should display. */
    suspend fun startDeviceFlow(clientId: String): Result<DeviceFlowState> = runCatching {
        val body = "client_id=$clientId&scope=repo,read:org,read:project"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val req = Request.Builder()
            .url("https://github.com/login/device/code")
            .post(body)
            .addHeader("Accept", "application/json")
            .build()
        val json = client.newCall(req).execute().use { resp ->
            JSONObject(resp.body!!.string())
        }
        DeviceFlowState(
            deviceCode      = json.getString("device_code"),
            userCode        = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            expiresIn       = json.optInt("expires_in", 900),
            interval        = json.optInt("interval", 5),
        )
    }

    /** Poll once for device flow completion. */
    suspend fun pollDeviceFlow(clientId: String, state: DeviceFlowState, label: String): GitHubConnectResult {
        return try {
            val body = "client_id=$clientId&device_code=${state.deviceCode}&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .post(body)
                .addHeader("Accept", "application/json")
                .build()
            val json = client.newCall(req).execute().use { resp -> JSONObject(resp.body!!.string()) }

            when {
                json.has("access_token") -> {
                    val token = json.getString("access_token")
                    val user = fetchUser(token) ?: return GitHubConnectResult.Error("Could not fetch user")
                    val entity = GitHubIdentityEntity(
                        id        = UUID.randomUUID().toString(),
                        label     = label.ifBlank { user.optString("login", "GitHub") },
                        username  = user.getString("login"),
                        avatarUrl = user.optString("avatar_url", ""),
                        token     = token,
                        tokenType = "oauth",
                        scopes    = "repo,read:org,read:project",
                        addedAt   = System.currentTimeMillis(),
                        isDefault = dao.getAllSync().isEmpty(),
                    )
                    dao.upsert(entity)
                    GitHubConnectResult.Success(entity.toDomain())
                }
                json.optString("error") == "authorization_pending" -> GitHubConnectResult.Pending
                json.optString("error") == "expired_token"         -> GitHubConnectResult.Expired
                else -> GitHubConnectResult.Error(json.optString("error_description", "Unknown"))
            }
        } catch (e: Exception) {
            GitHubConnectResult.Error(e.message ?: "Poll failed")
        }
    }

    suspend fun setDefault(id: String) {
        dao.clearDefault()
        dao.setDefault(id)
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Returns the token for the default identity, or null. */
    suspend fun defaultToken(): String? = dao.getDefault()?.token

    /** Returns the token for the given identity label, or falls back to default. */
    suspend fun tokenFor(identityLabel: String?): String? {
        if (identityLabel.isNullOrBlank()) return defaultToken()
        return findByLabel(identityLabel)?.token ?: defaultToken()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun fetchUser(token: String): JSONObject? {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else JSONObject(resp.body!!.string())
        }
    }

    private fun fetchScopes(token: String): String {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        return client.newCall(req).execute().use { resp ->
            resp.header("X-OAuth-Scopes") ?: ""
        }
    }

    private fun GitHubIdentityEntity.toDomain() = GitHubIdentity(
        id        = id,
        label     = label,
        username  = username,
        avatarUrl = avatarUrl,
        token     = token,
        tokenType = tokenType,
        scopes    = scopes,
        addedAt   = addedAt,
        isDefault = isDefault,
    )
}


