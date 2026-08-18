package com.vela.hosttools

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goal file item 1: verified by writing a UUID-stamped test event and
 * querying the REAL, out-of-process provider for it via
 * `adb shell content query --uri content://com.android.calendar/events`
 * — not an in-app assertion.
 *
 * This test requires a live device/emulator (Android Calendar Provider is a
 * real ContentProvider backed by CalendarContract; there is no JVM-only
 * substitute that satisfies "real, out-of-process provider" verification).
 *
 * BLOCKED on this headless host: /dev/kvm is unavailable, so no emulator can
 * boot here. This test is designed to run in CI's instrumented-tests job
 * (GitHub-hosted, KVM-backed, added in lane 1.1), which is the documented
 * fallback verification path per the goal file's "Host capability limits"
 * section. It compiles against the real Android SDK (verified via
 * `./gradlew :host-tools:assembleDebugAndroidTest`) but has not been executed
 * on this host.
 *
 * Manual out-of-process verification procedure (to run wherever a device/
 * emulator IS available, e.g. in CI or on a developer machine):
 *   1. `pm grant com.vela.app android.permission.READ_CALENDAR`
 *      `pm grant com.vela.app android.permission.WRITE_CALENDAR`
 *   2. Run this instrumented test class.
 *   3. `adb shell content query --uri content://com.android.calendar/events \
 *        --where "title LIKE '%<uuid>%'"`
 *      and confirm the UUID-stamped title appears in the real provider's output.
 */
@RunWith(AndroidJUnit4::class)
class CalendarToolsInstrumentedTest {

    // Grants happen from inside the test process itself, after the test APK
    // is installed but before the test body runs. An external `adb shell pm
    // grant` in the CI script races the Gradle-managed APK install and has
    // nothing to attach to yet — this rule is the correct, install-order-
    // independent mechanism.
    @get:Rule
    val grantCalendarPermissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    @Test
    fun writesUuidStampedEventAndReadsItBackViaContentResolver() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val createTool = CalendarCreateTool(context)
        val readTool = CalendarReadTool(context)

        val marker = "vela-test-${UUID.randomUUID()}"
        // Calendar id 1 is the default primary calendar on most test images;
        // in CI this is provisioned by the instrumented-tests job setup.
        val createArgs = JSONObject()
            .put("calendarId", 1)
            .put("title", marker)
            .put("startEpochMs", System.currentTimeMillis())
            .put("endEpochMs", System.currentTimeMillis() + 3_600_000)
            .toString()

        val createResult = createTool.execute(createArgs)
        check(createResult is com.vela.core.domain.HostTool.ToolResult.Success) {
            "calendar_create failed: $createResult"
        }

        val readResult = readTool.execute(JSONObject().put("query", marker).toString())
        check(readResult is com.vela.core.domain.HostTool.ToolResult.Success) {
            "calendar_read failed: $readResult"
        }
        val events = JSONObject(readResult.resultJson).getJSONArray("events")
        assertTrue("expected the UUID-stamped event to be found via ContentResolver", events.length() >= 1)

        // The out-of-process `adb shell content query` verification against
        // content://com.android.calendar/events with this same $marker is run
        // externally by the CI job / operator, per the procedure documented
        // in this class's KDoc — that step is outside what an in-app
        // instrumented test can itself invoke (adb is a host-side tool).
    }
}
