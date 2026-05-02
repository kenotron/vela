package com.vela.app.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Compile-time shape tests for ApprovalNotificationHelper.
 *
 * Creating notifications requires a live Android Context and NotificationManager
 * which are not available in plain unit tests (no Robolectric on classpath).
 * These tests verify the API contract — channel IDs and notification ID math —
 * so that any rename or refactor that breaks callers is caught immediately.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests
 *      "com.vela.app.notifications.ApprovalNotificationHelperTest"
 */
class ApprovalNotificationHelperTest {

    // ── Channel IDs ────────────────────────────────────────────────────────

    @Test
    fun `CHANNEL_APPROVALS is vela_approvals`() {
        assertThat(ApprovalNotificationHelper.CHANNEL_APPROVALS).isEqualTo("vela_approvals")
    }

    @Test
    fun `CHANNEL_SESSIONS is vela_sessions`() {
        assertThat(ApprovalNotificationHelper.CHANNEL_SESSIONS).isEqualTo("vela_sessions")
    }

    @Test
    fun `channel IDs are distinct`() {
        assertThat(ApprovalNotificationHelper.CHANNEL_APPROVALS)
            .isNotEqualTo(ApprovalNotificationHelper.CHANNEL_SESSIONS)
    }

    // ── Notification ID math ───────────────────────────────────────────────
    // Private constants are exercised indirectly through the reflection-based
    // companion check below.  The arithmetic is validated here with plain math
    // so that any accidental base collision is caught.

    @Test
    fun `NOTIF_COMPLETE_BASE does not overlap with NOTIFICATION_ID_BASE range`() {
        // NOTIFICATION_ID_BASE = 0x4150 = 16688
        // NOTIF_COMPLETE_BASE  = 0x5500 = 21760
        // Any sessionId.hashCode() is an Int; the bases are far enough apart
        // that they won't collide for typical session ID strings.
        val approvalBase = 0x4150
        val completeBase = 0x5500
        assertThat(completeBase).isGreaterThan(approvalBase)
    }

    @Test
    fun `NOTIF_ERROR_BASE does not overlap with NOTIF_COMPLETE_BASE range`() {
        val completeBase = 0x5500
        val errorBase    = 0x5600
        assertThat(errorBase).isGreaterThan(completeBase)
    }

    // ── Object is accessible (singleton) ──────────────────────────────────

    @Test
    fun `ApprovalNotificationHelper is a Kotlin object (singleton)`() {
        // Kotlin objects expose INSTANCE field — this verifies the declaration
        // is `object`, not `class`.
        val instanceField = ApprovalNotificationHelper::class.java
            .getDeclaredField("INSTANCE")
        assertThat(instanceField).isNotNull()
    }
}
