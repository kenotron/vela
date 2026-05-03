package com.vela.app.streaming

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which session the user is actively viewing.
 * Used by [SessionStreamingService] to suppress notifications
 * for the session that is currently on screen.
 */
@Singleton
class ActiveSessionTracker @Inject constructor() {
    @Volatile private var activeSessionId: String? = null

    fun setActive(sessionId: String)   { activeSessionId = sessionId }
    fun setInactive(sessionId: String) { if (activeSessionId == sessionId) activeSessionId = null }
    fun isActive(sessionId: String)    = activeSessionId == sessionId
}
