package com.vela.app.streaming

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vela.app.MainActivity
import com.vela.app.R
import com.vela.app.notifications.ApprovalNotificationHelper
import com.vela.app.ui.sessiondetail.SessionStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android Foreground Service that keeps the process alive while sessions are EXECUTING.
 *
 * Responsibilities:
 *  1. Observe [SessionStreamingManagerImpl.getAllSessionFlows] for state changes.
 *  2. Enter foreground (persistent notification) when any session is EXECUTING.
 *  3. Return to standby (no foreground notification) when all sessions are IDLE/ERROR.
 *  4. Post turn-complete, approval, and error notifications on state transitions.
 *
 * Started from [com.vela.app.MainActivity.onCreate] via startForegroundService().
 * Uses START_STICKY so Android restarts it if killed.
 */
@AndroidEntryPoint
class SessionStreamingService : Service() {

    @Inject lateinit var streamingManager: SessionStreamingManagerImpl
    @Inject lateinit var activeSessionTracker: ActiveSessionTracker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Previous snapshot used to detect state transitions for notification triggers. */
    var prevStates: Map<String, SessionState> = emptyMap()

    inner class StreamingBinder : Binder() {
        fun getService() = this@SessionStreamingService
    }

    private val binder = StreamingBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannels()
        scope.launch {
            streamingManager.getAllSessionFlows().collect { sessions ->
                handleStateSnapshot(sessions)
                prevStates = sessions
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground() promptly when started via startForegroundService().
        // handleStateSnapshot() will update or remove this notification as session state changes.
        startForeground(NOTIF_FOREGROUND_ID, buildForegroundNotification("Vela"))
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Foreground / standby ──────────────────────────────────────────────────────

    private fun handleStateSnapshot(sessions: Map<String, SessionState>) {
        val executing = sessions.values.filter { it.status == SessionStatus.EXECUTING }
        if (executing.isNotEmpty()) {
            val label = buildRunningLabel(executing.first(), executing.size)
            startForeground(NOTIF_FOREGROUND_ID, buildForegroundNotification(label))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        // Check for notification-worthy state transitions
        for ((sessionId, newState) in sessions) {
            checkNotifications(prevStates[sessionId], newState)
        }
    }

    private fun buildRunningLabel(session: SessionState, totalExecuting: Int): String {
        if (totalExecuting > 1) return "Vela — $totalExecuting sessions running"
        val todoText = session.currentTodoActiveForm
        val project  = session.projectName
        return when {
            todoText != null && project != null -> "Vela — $todoText · $project"
            todoText != null                    -> "Vela — $todoText"
            project != null                     -> "Vela — Working… · $project"
            else                                -> "Vela — Working…"
        }
    }

    private fun buildForegroundNotification(contentText: String): android.app.Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(contentText)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    // ── Notification triggers ─────────────────────────────────────────────────────

    private fun checkNotifications(prev: SessionState?, next: SessionState) {
        val sid = next.sessionId
        // User is already looking at this session — no notification needed
        if (activeSessionTracker.isActive(sid)) return

        // Turn complete: EXECUTING → IDLE
        if (prev?.status == SessionStatus.EXECUTING && next.status == SessionStatus.IDLE) {
            ApprovalNotificationHelper.postTurnComplete(
                context         = this,
                sessionId       = sid,
                nodeId          = next.nodeId,
                projectName     = next.projectName ?: "Vela",
                lastUserMessage = next.lastUserMessage,
            )
        }

        // Approval needed: pendingApproval became non-null (or changed)
        val newApproval = next.pendingApproval
        if (newApproval != null && prev?.pendingApproval?.id != newApproval.id) {
            ApprovalNotificationHelper.postApproval(
                context     = this,
                sessionId   = sid,
                nodeId      = next.nodeId,
                projectName = next.projectName ?: "Vela",
                approvalId  = newApproval.id,
                question    = newApproval.question,
            )
        }

        // Error: any → ERROR
        if (prev?.status != SessionStatus.ERROR && next.status == SessionStatus.ERROR) {
            ApprovalNotificationHelper.postError(
                context     = this,
                sessionId   = sid,
                nodeId      = next.nodeId,
                projectName = next.projectName ?: "Vela",
            )
        }
    }

    // ── Channel creation ──────────────────────────────────────────────────────────

    private fun createChannels() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNNING,
                "Vela — Active",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Persistent notification while Vela is working" }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSIONS,
                "Vela — Session Events",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Turn complete, approval needed, and error notifications"
                enableVibration(true)
            }
        )
    }

    companion object {
        const val CHANNEL_RUNNING     = "vela_running"
        const val CHANNEL_SESSIONS    = "vela_sessions"
        const val NOTIF_FOREGROUND_ID = 0x5555
    }
}
