package com.vela.app.data.db

    import androidx.room.Entity
    import androidx.room.PrimaryKey

    /**
     * A stored GitHub account — PAT or OAuth device-flow token.
     * One row per GitHub identity; a user can have multiple accounts.
     */
    @Entity(tableName = "github_identities")
    data class GitHubIdentityEntity(
        @PrimaryKey val id: String,
        /** User-chosen label, e.g. "Work", "Personal", "Robotdad" */
        val label: String,
        /** Resolved GitHub login from the /user endpoint */
        val username: String,
        val avatarUrl: String,
        /** The actual token — PAT or OAuth access token */
        val token: String,
        /** "pat" | "oauth" */
        val tokenType: String,
        /** Comma-separated scopes reported by GitHub, e.g. "repo,read:org" */
        val scopes: String,
        val addedAt: Long,
        /** True if this is the account to use when no explicit identity is chosen */
        val isDefault: Boolean = false,
    )
    