package com.vela.app.skills

    sealed class SkillLoadResult {
        data class Content(val body: String, val skillDirectory: String) : SkillLoadResult()
        data class ForkResult(val response: String) : SkillLoadResult()
        data class NotFound(val name: String) : SkillLoadResult()
        data class Error(val message: String) : SkillLoadResult()
    }
    