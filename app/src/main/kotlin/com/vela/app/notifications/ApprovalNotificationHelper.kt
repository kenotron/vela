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
     * Posts a high-priority Android notification when an amplifierd approval request arrives.
     *
     * Tapping the notification opens the app so the user can approve/deny inline.
     *
     * Channel: CHANNEL_APPROVALS ("vela_approvals"), HIGH importance.
     * Permission: POST_NOTIFICATIONS is requested at runtime on Android 13+ from MainActivity.
     */
    object ApprovalNotificationHelper {

        const val CHANNEL_APPROVALS = "vela_approvals"
        private const val NOTIFICATION_ID_BASE = 0x4150  // "AP" for Approvals

        /**
         * Create the notification channel. Call once from [Application.onCreate] so the
         * channel exists before any notification is posted.
         */
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

        /**
         * Post a notification for an approval request.
         *
         * @param context   Application or service context.
         * @param sessionId The session that needs approval (used to make notification ID unique).
         * @param question  The approval question shown in the notification body.
         */
        fun notify(context: Context, sessionId: String, question: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return  // Permission not yet granted — skip silently
            }

            // Tap opens MainActivity; the NavBackStack retains the open session screen
            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId.hashCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Vela — Action needed")
                .setContentText(question.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(question))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + sessionId.hashCode(), notification)
        }
    }
    