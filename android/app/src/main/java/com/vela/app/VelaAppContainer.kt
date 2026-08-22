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
import com.vela.ledger.LedgerDatabase

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

    val ledgerRepository: LedgerRepository by lazy {
        SqliteLedgerRepositoryAdapter(LedgerDatabase.getInstance(appContext).jobDao())
    }

    /**
     * Real [HostToolRegistry] wired to the concrete [HostTool] implementations
     * that exist in android/host-tools/ (read-only from this lane's
     * perspective -- no changes made to that module). [DispatchToFleetTool] is
     * deliberately excluded: the goal file's SCOPE-OUTS section states
     * `dispatch_to_fleet` stays against its existing stub and is out of scope
     * for this lane's real-server wiring.
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
            ),
        )
    }

    val toolLoopClient: AmplifierToolLoopClient by lazy {
        AmplifierToolLoopClient(
            baseUrl = BuildConfig.VELA_SERVER_BASE_URL,
            apiKey = BuildConfig.VELA_SERVER_BEARER_TOKEN,
            registry = hostToolRegistry,
        )
    }

    val c2EventClient: OkHttpC2EventClient by lazy { OkHttpC2EventClient() }

    companion object {
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
