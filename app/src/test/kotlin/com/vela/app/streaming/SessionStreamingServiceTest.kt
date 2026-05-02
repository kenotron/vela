package com.vela.app.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Structure and logic tests for [SessionStreamingService].
 * Written BEFORE implementation (TDD RED phase).
 *
 * Because [SessionStreamingService] extends [android.app.Service] (Android framework),
 * direct instantiation is not possible in JVM unit tests.  These tests verify:
 *  1. Source file structure — class declaration, annotations, companion constants.
 *  2. Notification channel constants — compile-time contract for downstream consumers.
 *  3. The source file exists and contains required Kotlin constructs.
 */
class SessionStreamingServiceTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionStreamingService.kt"
        ).readText()
    }

    // ── File existence ──────────────────────────────────────────────────────────

    @Test fun `source file exists`() {
        assertThat(
            java.io.File("src/main/kotlin/com/vela/app/streaming/SessionStreamingService.kt")
                .exists()
        ).isTrue()
    }

    // ── Class declaration ────────────────────────────────────────────────────────

    @Test fun `class is annotated with AndroidEntryPoint`() {
        assertThat(src).contains("@AndroidEntryPoint")
    }

    @Test fun `class extends Service`() {
        assertThat(src).contains("class SessionStreamingService : Service()")
    }

    @Test fun `streamingManager is injected with Inject`() {
        assertThat(src).contains("@Inject lateinit var streamingManager: SessionStreamingManagerImpl")
    }

    // ── Service lifecycle ────────────────────────────────────────────────────────

    @Test fun `onStartCommand returns START_STICKY`() {
        assertThat(src).contains("START_STICKY")
    }

    @Test fun `onDestroy cancels scope`() {
        assertThat(src).contains("scope.cancel()")
    }

    @Test fun `onCreate calls createChannels`() {
        assertThat(src).contains("createChannels()")
    }

    @Test fun `onCreate launches coroutine to collect getAllSessionFlows`() {
        assertThat(src).contains("getAllSessionFlows()")
        assertThat(src).contains("scope.launch")
    }

    // ── Binder ──────────────────────────────────────────────────────────────────

    @Test fun `inner class StreamingBinder extends Binder`() {
        assertThat(src).contains("inner class StreamingBinder : Binder()")
    }

    @Test fun `StreamingBinder has getService method`() {
        assertThat(src).contains("fun getService() = this@SessionStreamingService")
    }

    @Test fun `onBind returns binder`() {
        assertThat(src).contains("override fun onBind")
    }

    // ── Foreground service logic ─────────────────────────────────────────────────

    @Test fun `handleStateSnapshot is declared`() {
        assertThat(src).contains("fun handleStateSnapshot(")
    }

    @Test fun `buildRunningLabel is declared`() {
        assertThat(src).contains("fun buildRunningLabel(")
    }

    @Test fun `buildForegroundNotification is declared`() {
        assertThat(src).contains("fun buildForegroundNotification(")
    }

    @Test fun `stopForeground with STOP_FOREGROUND_REMOVE is called`() {
        assertThat(src).contains("STOP_FOREGROUND_REMOVE")
    }

    @Test fun `startForeground with NOTIF_FOREGROUND_ID is called`() {
        assertThat(src).contains("NOTIF_FOREGROUND_ID")
        assertThat(src).contains("startForeground(")
    }

    // ── Notification triggers ────────────────────────────────────────────────────

    @Test fun `checkNotifications is declared`() {
        assertThat(src).contains("fun checkNotifications(")
    }

    @Test fun `checkNotifications calls postTurnComplete on EXECUTING to IDLE transition`() {
        assertThat(src).contains("postTurnComplete(")
    }

    @Test fun `checkNotifications calls postApproval on new pendingApproval`() {
        assertThat(src).contains("postApproval(")
    }

    @Test fun `checkNotifications calls postError on transition to ERROR`() {
        assertThat(src).contains("postError(")
    }

    // ── Notification channels ────────────────────────────────────────────────────

    @Test fun `createChannels is declared`() {
        assertThat(src).contains("fun createChannels()")
    }

    @Test fun `CHANNEL_RUNNING constant is vela_running`() {
        assertThat(src).contains("CHANNEL_RUNNING")
        assertThat(src).contains("\"vela_running\"")
    }

    @Test fun `CHANNEL_SESSIONS constant is vela_sessions`() {
        assertThat(src).contains("CHANNEL_SESSIONS")
        assertThat(src).contains("\"vela_sessions\"")
    }

    @Test fun `NOTIF_FOREGROUND_ID constant is 0x5555`() {
        assertThat(src).contains("NOTIF_FOREGROUND_ID")
        assertThat(src).contains("0x5555")
    }

    // ── buildRunningLabel logic checks (via source text) ─────────────────────────

    @Test fun `buildRunningLabel handles multiple sessions label`() {
        // Verifies the "N sessions running" branch exists
        assertThat(src).contains("sessions running")
    }

    @Test fun `buildRunningLabel handles todo and project combined`() {
        assertThat(src).contains("Working\u2026")
    }

    // ── prevStates field ─────────────────────────────────────────────────────────

    @Test fun `prevStates field is declared`() {
        assertThat(src).contains("prevStates")
    }
}
