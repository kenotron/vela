package com.vela.hosttools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.vela.core.domain.HostTool.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host tools against the Android Calendar Provider (design doc §4.2, C1 table:
 * `calendar_*` (read/create/modify), latency class "fast (<500ms)").
 *
 * Requires READ_CALENDAR / WRITE_CALENDAR permissions to already be granted
 * (this lane assumes runtime permission grant is handled elsewhere, e.g. via
 * `pm grant` in CI or an app-level permission flow — out of scope here).
 *
 * These tools require the real Android framework (ContentResolver,
 * CalendarContract) and cannot be exercised in a plain JVM unit test. They are
 * verified via an instrumented test (androidTest) — see
 * CalendarToolsInstrumentedTest — which requires a device/emulator and is
 * BLOCKED-named on this headless host (no /dev/kvm). CI's instrumented-tests
 * job (KVM-backed) is the fallback verification path.
 */
class CalendarReadTool(private val context: Context) : BaseHostTool() {
    override val name: String = "calendar_read"
    override val description: String =
        "Read upcoming calendar events, optionally filtered by a text query."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "Optional substring to filter event titles"},
            "limit": {"type": "integer", "description": "Max number of events to return", "default": 20}
          },
          "required": []
        }
    """.trimIndent()

    override suspend fun run(argsJson: String): ToolResult {
        val args = JSONObject(argsJson.ifBlank { "{}" })
        val query = args.optString("query", "")
        val limit = args.optInt("limit", 20)

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
        )
        val selection = if (query.isNotBlank()) "${CalendarContract.Events.TITLE} LIKE ?" else null
        val selectionArgs = if (query.isNotBlank()) arrayOf("%$query%") else null

        val results = JSONArray()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val obj = JSONObject()
                obj.put("id", cursor.getLong(0))
                obj.put("title", cursor.getString(1) ?: "")
                obj.put("start", cursor.getLong(2))
                obj.put("end", cursor.getLong(3))
                results.put(obj)
                count++
            }
        }
        return ToolResult.Success(JSONObject().put("events", results).toString())
    }
}

class CalendarCreateTool(private val context: Context) : BaseHostTool() {
    override val name: String = "calendar_create"
    override val description: String = "Create a new calendar event."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "calendarId": {"type": "integer", "description": "Target calendar id"},
            "title": {"type": "string"},
            "startEpochMs": {"type": "integer"},
            "endEpochMs": {"type": "integer"},
            "timezone": {"type": "string", "default": "UTC"}
          },
          "required": ["calendarId", "title", "startEpochMs", "endEpochMs"]
        }
    """.trimIndent()

    override suspend fun run(argsJson: String): ToolResult {
        val args = JSONObject(argsJson)
        if (!args.has("calendarId") || !args.has("title") ||
            !args.has("startEpochMs") || !args.has("endEpochMs")
        ) {
            return ToolResult.Failure("missing required field(s)")
        }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, args.getLong("calendarId"))
            put(CalendarContract.Events.TITLE, args.getString("title"))
            put(CalendarContract.Events.DTSTART, args.getLong("startEpochMs"))
            put(CalendarContract.Events.DTEND, args.getLong("endEpochMs"))
            put(CalendarContract.Events.EVENT_TIMEZONE, args.optString("timezone", "UTC"))
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return ToolResult.Failure("insert returned null uri")
        val id = ContentUris.parseId(uri)
        return ToolResult.Success(JSONObject().put("eventId", id).toString())
    }
}

class CalendarModifyTool(private val context: Context) : BaseHostTool() {
    override val name: String = "calendar_modify"
    override val description: String = "Modify an existing calendar event's fields."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "eventId": {"type": "integer"},
            "title": {"type": "string"},
            "startEpochMs": {"type": "integer"},
            "endEpochMs": {"type": "integer"}
          },
          "required": ["eventId"]
        }
    """.trimIndent()

    override suspend fun run(argsJson: String): ToolResult {
        val args = JSONObject(argsJson)
        if (!args.has("eventId")) return ToolResult.Failure("missing eventId")
        val eventId = args.getLong("eventId")
        val values = ContentValues()
        if (args.has("title")) values.put(CalendarContract.Events.TITLE, args.getString("title"))
        if (args.has("startEpochMs")) values.put(CalendarContract.Events.DTSTART, args.getLong("startEpochMs"))
        if (args.has("endEpochMs")) values.put(CalendarContract.Events.DTEND, args.getLong("endEpochMs"))
        if (values.size() == 0) return ToolResult.Failure("no fields to modify")

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = context.contentResolver.update(uri, values, null, null)
        return if (rows > 0) {
            ToolResult.Success(JSONObject().put("eventId", eventId).put("updatedRows", rows).toString())
        } else {
            ToolResult.Failure("no rows updated for eventId=$eventId")
        }
    }
}
