package com.vela.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vela.app.MainActivity
import com.vela.app.R

/**
 * Posts Android notifications for session events.
 *
 * Channels:
 *  CHANNEL_APPROVALS ("vela_approvals") — legacy approval channel, HIGH importance.
 *  CHANNEL_SESSIONS  ("vela_sessions")  — turn-complete, approval, error events.
 *
 * The foreground service channel ("vela_running") is created by [SessionStreamingService].
 */
object ApprovalNotificationHelper {

    const val CHANNEL_APPROVALS  = "vela_approvals"
    const val CHANNEL_SESSIONS   = "vela_sessions"

    private const val NOTIFICATION_ID_BASE   = 0x4150   // "AP" — legacy approval IDs
    private const val NOTIF_COMPLETE_BASE    = 0x5500   // turn-complete notifications
    private const val NOTIF_ERROR_BASE       = 0x5600   // error notifications

    // ── Channel creation ──────────────────────────────────────────────────────────

    /** Create the legacy approvals channel. Call once from [Application.onCreate]. */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_APPROVALS,
            "Vela — Approval Requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifies when an amplifierd session needs your approval"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** Create the unified session-events channel. Call once from [Application.onCreate]. */
    fun createSessionChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_SESSIONS,
            "Vela — Session Events",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Turn complete, approval needed, and error notifications"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    // ── Legacy API (kept for backward compat with SessionDetailViewModel) ─────────

    /**
     * Post a high-priority notification for an approval request.
     * Kept for ViewModel-level usage until Phase 2 migration is complete.
     */
    fun notify(context: Context, sessionId: String, nodeId: String = "", question: String) {
        if (!hasPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Vela — Action needed")
            .setContentText(question.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId, nodeId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID_BASE + sessionId.hashCode(), notification)
    }

    // ── New notification API (used by SessionStreamingService) ────────────────────

    /** Post a "turn complete" notification when EXECUTING → IDLE. */
    fun postTurnComplete(
        context: Context,
        sessionId: String,
        nodeId: String = "",
        projectName: String,
        lastUserMessage: String?,
    ) {
        if (!hasPermission(context)) return
        val body = lastUserMessage?.take(60) ?: ""
        val notification = NotificationCompat.Builder(context, CHANNEL_SESSIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$projectName: Done")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId, nodeId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIF_COMPLETE_BASE + sessionId.hashCode(), notification)
    }

    /**
     * Post an "approval needed" notification.
     * Uses CHANNEL_APPROVALS (HIGH importance) so the user gets alerted even if DND.
     */
    fun postApproval(
        context: Context,
        sessionId: String,
        nodeId: String = "",
        projectName: String,
        approvalId: String,
        question: String,
    ) {
        if (!hasPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$projectName: Needs your input")
            .setContentText(question.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId, nodeId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID_BASE + sessionId.hashCode(), notification)
    }

    /** Post an "error" notification when a session transitions to ERROR. */
    fun postError(
        context: Context,
        sessionId: String,
        nodeId: String = "",
        projectName: String,
    ) {
        if (!hasPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_SESSIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$projectName: Task may have failed")
            .setContentText("Connection error — tap to retry")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId, nodeId))
            .build()
        NotificationManagerCompat.from(context)
            .notify(NOTIF_ERROR_BASE + sessionId.hashCode(), notification)
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun tapIntent(context: Context, sessionId: String, nodeId: String = ""): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("deep_link_session_id", sessionId)
            putExtra("deep_link_node_id", nodeId)
        }
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
