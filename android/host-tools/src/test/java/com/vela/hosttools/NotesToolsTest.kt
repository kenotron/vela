package com.vela.hosttools

import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Goal file item 2: "notes_* executor implemented against a local store.
 * This does NOT strictly require an emulator to test — write a local unit
 * test using Robolectric". Robolectric provides a simulated Android
 * framework (Context, SQLiteDatabase) that runs on the plain JVM, so this
 * is a REAL passing test on this headless host, not an instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
class NotesToolsTest {

    @Test
    fun `create then read note round-trips through sqlite`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbHelper = NotesDbHelper(context)
        val createTool = NotesCreateTool(dbHelper)
        val readTool = NotesReadTool(dbHelper)

        val noteId = UUID.randomUUID().toString()
        val createArgs = JSONObject()
            .put("id", noteId)
            .put("title", "Test note $noteId")
            .put("body", "Body content")
            .toString()

        val createResult = createTool.execute(createArgs)
        check(createResult is com.vela.core.domain.HostTool.ToolResult.Success) {
            "create failed: $createResult"
        }
        assertEquals(noteId, JSONObject(createResult.resultJson).getString("id"))

        val readResult = readTool.execute(JSONObject().put("query", noteId).toString())
        check(readResult is com.vela.core.domain.HostTool.ToolResult.Success) {
            "read failed: $readResult"
        }
        val notes = JSONObject(readResult.resultJson).getJSONArray("notes")
        assertTrue("expected at least one note matching query", notes.length() >= 1)
        assertEquals(noteId, notes.getJSONObject(0).getString("id"))
        assertEquals("Body content", notes.getJSONObject(0).getString("body"))
    }
}
