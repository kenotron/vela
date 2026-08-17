package com.vela.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vela.app.MainActivity
import com.vela.core.domain.VoiceTransport
import com.vela.voice.Earcon
import com.vela.voice.EarconPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground service holding the active voice session (item 6 / V7 / V8).
 *
 * All state-to-(notification, earcon) decision logic lives in
 * [VoiceServiceStateReducer], a plain-JVM class with no Android or LiveKit
 * dependency, so that logic is unit-testable without a real Service lifecycle
 * or an emulator. This class's only job is Android plumbing: wiring the real
 * [VoiceTransport.state] flow (plus an app-level substate signal) into the
 * reducer, and applying the reducer's output to the real
 * [NotificationManager] and [EarconPlayer].
 *
 * The [VoiceTransport] and [EarconPlayer] instances are expected to be
 * supplied by the app's DI graph (not shown here, out of scope for this
 * lane); this service accepts them via simple setters/companion wiring so it
 * remains substitutable in tests.
 */
public class VoiceForegroundService : Service() {

    private val reducer = VoiceServiceStateReducer()
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Injected by the app's composition root before the service is started. */
    public var voiceTransport: VoiceTransport? = null

    /** Injected by the app's composition root before the service is started. */
    public var earconPlayer: EarconPlayer? = null

    /** Current session substate, updated by whatever component tracks
     * transcript/audio activity (out of scope for this lane's wiring); starts
     * LISTENING on connect. */
    public var currentSubstate: VoiceSessionSubstate = VoiceSessionSubstate.LISTENING

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(reducer.reduce(
            VoiceTransport.TransportState.CONNECTING,
            currentSubstate,
        ).notification))

        val transport = voiceTransport
        if (transport != null) {
            job?.cancel()
            job = transport.state
                .onEach { transportState ->
                    applyReducerOutput(reducer.reduce(transportState, currentSubstate))
                }
                .launchIn(scope)
        }

        return START_STICKY
    }

    /** Called whenever the app-level session substate changes (e.g. driven by
     * incoming TranscriptDelta/AudioChunk events). Re-runs the reducer with
     * the transport's last-known CONNECTED state assumption; callers should
     * only invoke this while genuinely connected. */
    public fun onSubstateChanged(substate: VoiceSessionSubstate) {
        currentSubstate = substate
        applyReducerOutput(reducer.reduce(VoiceTransport.TransportState.CONNECTED, substate))
    }

    private fun applyReducerOutput(output: VoiceServiceUiOutput) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(output.notification))
        output.earcon?.let { earconPlayer?.play(it) }
    }

    private fun buildNotification(content: VoiceNotificationContent): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice session",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "vela_voice_session"
        const val NOTIFICATION_ID = 1001
    }
}
