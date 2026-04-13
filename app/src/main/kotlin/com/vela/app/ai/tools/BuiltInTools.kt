    package com.vela.app.ai.tools

    import android.content.Context
    import android.content.Intent
    import android.content.IntentFilter
    import android.os.BatteryManager
    import java.time.LocalDate
    import java.time.LocalTime
    import java.time.format.DateTimeFormatter
    import java.time.format.TextStyle
    import java.util.Locale

    class GetTimeTool : Tool {
        override val name        = "get_time"
        override val displayName = "Clock"
        override val icon        = "🕐"
        override val description = "Returns the current local time"

        override suspend fun execute(args: Map<String, Any>): String {
            val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            return LocalTime.now().format(formatter)
        }
    }

    class GetDateTool : Tool {
        override val name        = "get_date"
        override val displayName = "Calendar"
        override val icon        = "📅"
        override val description = "Returns today's date and day of the week"

        override suspend fun execute(args: Map<String, Any>): String {
            val now = LocalDate.now()
            val dayName   = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            val monthName = now.month.getDisplayName(TextStyle.FULL, Locale.US)
            return "$dayName, $monthName ${now.dayOfMonth}, ${now.year}"
        }
    }

    class GetBatteryTool(private val context: Context) : Tool {
        override val name        = "get_battery"
        override val displayName = "Battery"
        override val icon        = "🔋"
        override val description = "Returns the device battery level and charging state"

        override suspend fun execute(args: Map<String, Any>): String {
            val intent: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            if (intent == null) return "Battery info unavailable"

            val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            val pct = if (level >= 0 && scale > 0) "${(level * 100 / scale)}%" else "unknown"
            val charging = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING     -> " (charging)"
                BatteryManager.BATTERY_STATUS_FULL         -> " (full)"
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> " (not charging)"
                else                                       -> ""
            }
            return "$pct$charging"
        }
    }
    