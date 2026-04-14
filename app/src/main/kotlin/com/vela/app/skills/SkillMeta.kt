package com.vela.app.skills

data class SkillMeta(
    val name: String,
    val description: String,
    val isFork: Boolean = false,
    val isUserInvocable: Boolean = false,
    val directory: String,
)
