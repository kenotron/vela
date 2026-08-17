package com.vela.hosttools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vela.core.domain.HostTool.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local note store backed by plain SQLite (design doc §4.2, C1 table:
 * `notes_* | Local store / user's note backend | fast`).
 *
 * Deliberately not Room — a single table with three columns doesn't justify
 * the dependency (IMPLEMENTATION_PHILOSOPHY: "library vs custom code" —
 * SQLiteOpenHelper directly is simpler here and fully sufficient).
 */
class NotesDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "id TEXT PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "body TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "vela_notes.db"
        const val DB_VERSION = 1
        const val TABLE = "notes"
    }
}

class NotesCreateTool(private val dbHelper: NotesDbHelper) : BaseHostTool() {
    override val name: String = "notes_create"
    override val description: String = "Create a new note."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "Client-supplied unique id (e.g. UUID)"},
            "title": {"type": "string"},
            "body": {"type": "string"}
          },
          "required": ["id", "title", "body"]
        }
    """.trimIndent()

    override suspend fun run(argsJson: String): ToolResult {
        val args = JSONObject(argsJson)
        if (!args.has("id") || !args.has("title") || !args.has("body")) {
            return ToolResult.Failure("missing required field(s)")
        }
        val values = ContentValues().apply {
            put("id", args.getString("id"))
            put("title", args.getString("title"))
            put("body", args.getString("body"))
            put("created_at", System.currentTimeMillis())
        }
        val db = dbHelper.writableDatabase
        val rowId = db.insertWithOnConflict(
            NotesDbHelper.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return if (rowId != -1L) {
            ToolResult.Success(JSONObject().put("id", args.getString("id")).toString())
        } else {
            ToolResult.Failure("insert failed")
        }
    }
}

class NotesReadTool(private val dbHelper: NotesDbHelper) : BaseHostTool() {
    override val name: String = "notes_read"
    override val description: String = "List notes, optionally filtered by a text query."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string"},
            "limit": {"type": "integer", "default": 20}
          },
          "required": []
        }
    """.trimIndent()

    override suspend fun run(argsJson: String): ToolResult {
        val args = JSONObject(argsJson.ifBlank { "{}" })
        val query = args.optString("query", "")
        val limit = args.optInt("limit", 20)
        val db = dbHelper.readableDatabase
        val selection = if (query.isNotBlank()) "title LIKE ? OR body LIKE ?" else null
        val selectionArgs = if (query.isNotBlank()) arrayOf("%$query%", "%$query%") else null
        val results = JSONArray()
        db.query(
            NotesDbHelper.TABLE,
            arrayOf("id", "title", "body", "created_at"),
            selection,
            selectionArgs,
            null,
            null,
            "created_at DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val obj = JSONObject()
                obj.put("id", cursor.getString(0))
                obj.put("title", cursor.getString(1))
                obj.put("body", cursor.getString(2))
                obj.put("createdAt", cursor.getLong(3))
                results.put(obj)
            }
        }
        return ToolResult.Success(JSONObject().put("notes", results).toString())
    }
}
