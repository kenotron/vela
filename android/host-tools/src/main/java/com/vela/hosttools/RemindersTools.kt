package com.vela.hosttools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vela.core.domain.HostTool.ToolResult
import org.json.JSONObject

/**
 * Host tools against Android AlarmManager/NotificationManager (design doc §4.2,
 * C1 table: `reminders_* | Android alarm/notification APIs | fast`).
 *
 * These require live Android framework services (AlarmManager scheduling,
 * NotificationManager channel registration) and cannot be exercised in a
 * plain JVM unit test — verified only via instrumented test, BLOCKED-named on
 * this headless host (no /dev/kvm); CI's instrumented-tests job is the
 * fallback verification path.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, 0)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(channel)
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        nm.notify(requestCode, notification)
    }

    companion object {
        const val CHANNEL_ID = "vela_reminders"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_REQUEST_CODE = "requestCode"
    }
}

class ReminderCreateTool(private val context: Context) : BaseHostTool() {
    override val name: String = "reminders_create"
    override val description: String = "Schedule a reminder notification at a future time."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "requestCode": {"type": "integer", "description": "Unique id for this reminder"},
            "title": {"type": "string"},
            "body": {"type": "string"},
            "triggerAtEpochMs": {"type": "integer"}
          },
          "required": ["requestCode", "title", "triggerAtEpochMs"]
        }
    """.trimIndent()

    override suspend fun run(argsJson: String): ToolResult {
        val args = JSONObject(argsJson)
        if (!args.has("requestCode") || !args.has("title") || !args.has("triggerAtEpochMs")) {
            return ToolResult.Failure("missing required field(s)")
        }
        val requestCode = args.getInt("requestCode")
        val title = args.getString("title")
        val body = args.optString("body", "")
        val triggerAt = args.getLong("triggerAtEpochMs")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_BODY, body)
            putExtra(ReminderReceiver.EXTRA_REQUEST_CODE, requestCode)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent,
        )
        return ToolResult.Success(
            JSONObject().put("requestCode", requestCode).put("scheduledFor", triggerAt).toString(),
        )
    }
}

class ReminderCancelTool(private val context: Context) : BaseHostTool() {
    override val name: String = "reminders_cancel"
    override val description: String = "Cancel a previously scheduled reminder."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "requestCode": {"type": "integer"}
          },
          "required": ["requestCode"]
        }
    """.trimIndent()

    override suspend fun run(argsJson: String): ToolResult {
        val args = JSONObject(argsJson)
        if (!args.has("requestCode")) return ToolResult.Failure("missing requestCode")
        val requestCode = args.getInt("requestCode")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        alarmManager.cancel(pendingIntent)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(requestCode)
        return ToolResult.Success(JSONObject().put("requestCode", requestCode).put("cancelled", true).toString())
    }
}
