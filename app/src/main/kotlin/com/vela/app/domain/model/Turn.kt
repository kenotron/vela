package com.vela.app.domain.model

import java.util.UUID

enum class TurnStatus { RUNNING, COMPLETE, ERROR }

data class Turn(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val userMessage: String,
    val status: TurnStatus = TurnStatus.RUNNING,
    val timestamp: Long = System.currentTimeMillis(),
    val error: String? = null,
)
