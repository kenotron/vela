package com.vela.app

import android.content.Context
import com.vela.core.domain.HostTool
import com.vela.core.domain.HostToolRegistry
import com.vela.core.domain.LedgerRepository
import com.vela.events.OkHttpC2EventClient
import com.vela.hosttools.AmplifierToolLoopClient
import com.vela.hosttools.CalendarCreateTool
import com.vela.hosttools.CalendarModifyTool
import com.vela.hosttools.CalendarReadTool
import com.vela.hosttools.DefaultHostToolRegistry
import com.vela.hosttools.NotesCreateTool
import com.vela.hosttools.NotesDbHelper
import com.vela.hosttools.NotesReadTool
import com.vela.hosttools.ReminderCancelTool
import com.vela.hosttools.ReminderCreateTool
import com.vela.hosttools.DispatchToFleetTool
import com.vela.hosttools.SshFleetPlane
import com.vela.ledger.LedgerDatabase
import com.vela.ledger.SqliteLedgerRepository
import com.vela.ledger.server.LedgerApiClient
import com.vela.ledger.server.ServerLedgerRepository

/**
 * Composition root (goal item 2/3): lazily constructs the real, server-backed
 * implementations that replace the mock data/DI wiring previously in
 * MainActivity. No hardcoded server URL/token here -- both come from
 * [BuildConfig], itself populated from android/local.properties (see
 * app/build.gradle.kts).
 *
 * Kept intentionally simple (no Hilt/DI framework) per the goal's own
 * instruction -- a small lazily-constructed container is sufficient for this
 * lane's scope.
 */
class VelaAppContainer(private val appContext: Context) {

    /**
     * Goal ledger-l2-android (#30/#37/#38): server-backed when a base URL is configured
     * (matching the same [BuildConfig.VELA_SERVER_BASE_URL] the tool-loop client already
     * uses), local-only [SqliteLedgerRepositoryAdapter] otherwise -- e.g. a dev build with
     * no `android/local.properties` configured yet. Minimal selection logic per the
     * goal's own instruction not to redesign this container.
     */
    val ledgerRepository: LedgerRepository by lazy {
        val jobDao = LedgerDatabase.getInstance(appContext).jobDao()
        if (BuildConfig.VELA_SERVER_BASE_URL.isNotBlank()) {
            ServerLedgerRepository(
                api = LedgerApiClient(
                    baseUrl = BuildConfig.VELA_SERVER_BASE_URL,
                    apiKey = BuildConfig.VELA_SERVER_BEARER_TOKEN.ifBlank { null },
                ),
                mirror = SqliteLedgerRepository(jobDao),
                outbox = LedgerDatabase.getInstance(appContext).decisionOutboxDao(),
            )
        } else {
            SqliteLedgerRepositoryAdapter(jobDao)
        }
    }

    /**
     * Real [HostToolRegistry] wired to the concrete [HostTool] implementations
     * that exist in android/host-tools/ (read-only from this lane's
     * perspective -- no changes made to that module). [DispatchToFleetTool]
     * is wired against [SshFleetPlane] (F0.2, design doc \u00a78.4/\u00a79.1) rather
     * than `StubFleetPlane` -- the one-line swap called for by the design
     * doc's rollback table ("F0 | Re-point VelaAppContainer at
     * StubFleetPlane. One line.").
     */
    val hostToolRegistry: HostToolRegistry by lazy {
        val notesDbHelper = NotesDbHelper(appContext)
        DefaultHostToolRegistry(
            listOf(
                CalendarReadTool(appContext),
                CalendarCreateTool(appContext),
                CalendarModifyTool(appContext),
                NotesCreateTool(notesDbHelper),
                NotesReadTool(notesDbHelper),
                ReminderCreateTool(appContext),
                ReminderCancelTool(appContext),
                DispatchToFleetTool(
                    ledgerRepository,
                    SshFleetPlane(baseUrl = BuildConfig.VELA_SERVER_BASE_URL, apiKey = BuildConfig.VELA_SERVER_BEARER_TOKEN),
                ),
            ),
        )
    }

    /**
     * A stable id for this device's conversation with vela-agentd, persisted in
     * SharedPreferences so it survives process death/app restart. Sent as
     * `X-Client-Session-Id` on every chat-completions request -- the server derives a
     * deterministic amplifier session id from it and persists/resumes conversation state
     * server-side. Generating a NEW value here would start a brand-new server-side
     * session bucket, losing continuity -- this must stay stable for the life of the
     * install (or until an explicit "new conversation" action exists).
     */
    private val clientSessionId: String by lazy {
        val prefs = appContext.getSharedPreferences("vela_app_container", Context.MODE_PRIVATE)
        prefs.getString(KEY_CLIENT_SESSION_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_SESSION_ID, it).apply()
        }
    }

    val toolLoopClient: AmplifierToolLoopClient by lazy {
        AmplifierToolLoopClient(
            baseUrl = BuildConfig.VELA_SERVER_BASE_URL,
            apiKey = BuildConfig.VELA_SERVER_BEARER_TOKEN,
            registry = hostToolRegistry,
            clientSessionId = clientSessionId,
        )
    }

    val c2EventClient: OkHttpC2EventClient by lazy { OkHttpC2EventClient() }

    companion object {
        private const val KEY_CLIENT_SESSION_ID = "client_session_id"

        @Volatile
        private var instance: VelaAppContainer? = null

        fun getInstance(context: Context): VelaAppContainer {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = VelaAppContainer(context.applicationContext)
                instance = created
                return created
            }
        }
    }
}
