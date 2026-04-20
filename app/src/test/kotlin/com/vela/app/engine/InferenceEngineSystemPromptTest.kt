package com.vela.app.engine

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.tools.ToolRegistry
import com.vela.app.data.db.ConversationDao
import com.vela.app.data.db.ConversationEntity
import com.vela.app.data.db.TurnDao
import com.vela.app.data.db.TurnEntity
import com.vela.app.data.db.TurnEventDao
import com.vela.app.data.db.TurnEventEntity
import com.vela.app.data.db.TurnWithEvents
import com.vela.app.data.db.VaultDao
import com.vela.app.data.db.VaultEntity
import com.vela.app.harness.SessionHarness
import com.vela.app.hooks.HookRegistry
import com.vela.app.vault.VaultRegistry
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.mockito.Mockito.mock

/**
 * Verifies that InferenceEngine threads the correct systemPrompt through to
 * InferenceSession.runTurn() based on vault-mode and harness initialization state.
 *
 * RED: Fails to compile before InferenceEngine gets the new constructor params
 *      (conversationDao, vaultRegistry, harness).
 * GREEN: Compiles and passes after the constructor and processTurn() are updated.
 */
class InferenceEngineSystemPromptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Capturing InferenceSession ────────────────────────────────────────────

    /**
     * Sends each received systemPrompt to [prompts] so tests can assert the value
     * synchronously without polling or sleeping.
     */
    private class FakeSession : InferenceSession {
        val prompts = Channel<String>(Channel.UNLIMITED)

        override fun isConfigured() = true

        override suspend fun runTurn(
            historyJson: String,
            userInput: String,
            userContentJson: String?,
            systemPrompt: String,
            onToolStart: suspend (String, String) -> String,
            onToolEnd: suspend (String, String) -> Unit,
            onToken: suspend (String) -> Unit,
            onProviderRequest: suspend () -> String?,
            onServerTool: suspend (String, String) -> Unit,
        ) {
            prompts.send(systemPrompt)
            onToken("ok")
        }
    }

    // ── Fake DAOs ─────────────────────────────────────────────────────────────

    private fun fakeTurnDao() = object : TurnDao {
        override fun getTurnsWithEvents(convId: String): Flow<List<TurnWithEvents>> = emptyFlow()
        override suspend fun getCompletedTurnsWithEvents(convId: String): List<TurnWithEvents> = emptyList()
        override suspend fun getRecentCompletedTurns(limit: Int): List<TurnWithEvents> = emptyList()
        override suspend fun insert(turn: TurnEntity) {}
        override suspend fun updateStatus(id: String, status: String, error: String?) {}
        override suspend fun deleteForConversation(convId: String) {}
    }

    private fun fakeTurnEventDao() = object : TurnEventDao {
        override fun getEventsForTurn(turnId: String): Flow<List<TurnEventEntity>> = emptyFlow()
        override suspend fun insert(event: TurnEventEntity) {}
        override suspend fun updateEvent(id: String, status: String?, result: String?, text: String?) {}
    }

    private fun fakeConversationDao(mode: String) = object : ConversationDao {
        override fun getAllConversations(): Flow<List<ConversationEntity>> = emptyFlow()
        override suspend fun getById(id: String) = ConversationEntity(
            id = id, title = "Test", createdAt = 0L, updatedAt = 0L, mode = mode,
        )
        override suspend fun insert(conversation: ConversationEntity) {}
        override suspend fun updateTitle(id: String, title: String, updatedAt: Long) {}
        override suspend fun touch(id: String, updatedAt: Long) {}
        override suspend fun delete(id: String) {}
    }

    private fun fakeVaultRegistry() = VaultRegistry(
        dao = object : VaultDao {
            override fun observeAll(): Flow<List<VaultEntity>> = emptyFlow()
            override suspend fun getEnabled(): List<VaultEntity> = emptyList()
            override suspend fun getById(id: String): VaultEntity? = null
            override suspend fun insert(vault: VaultEntity) {}
            override suspend fun update(vault: VaultEntity) {}
            override suspend fun delete(vault: VaultEntity) {}
        },
        root = tmp.newFolder("vaults"),
    )

    private fun fakeVaultRegistryWithVaults(vaults: List<VaultEntity>) = VaultRegistry(
        dao = object : VaultDao {
            override fun observeAll(): Flow<List<VaultEntity>> = emptyFlow()
            override suspend fun getEnabled(): List<VaultEntity> = vaults.filter { it.isEnabled }
            override suspend fun getById(id: String): VaultEntity? = vaults.firstOrNull { it.id == id }
            override suspend fun insert(vault: VaultEntity) {}
            override suspend fun update(vault: VaultEntity) {}
            override suspend fun delete(vault: VaultEntity) {}
        },
        root = tmp.newFolder("vaults-filtered"),
    )

    private fun fakeHarness() = SessionHarness(HookRegistry(emptyList()))

    /** Returns a non-null Context mock; never called in these tests (fake DAOs return empty). */
    @Suppress("UNCHECKED_CAST")
    private fun fakeContext(): android.content.Context =
        mock(android.content.Context::class.java)

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun makeEngine(
        session: FakeSession,
        mode: String,
        harness: SessionHarness = fakeHarness(),
    ) = InferenceEngine(
        context         = fakeContext(),
        session         = session,
        toolRegistry    = ToolRegistry(emptyList()),
        hookRegistry    = HookRegistry(emptyList()),
        turnDao         = fakeTurnDao(),
        turnEventDao    = fakeTurnEventDao(),
        conversationDao = fakeConversationDao(mode),
        vaultRegistry   = fakeVaultRegistry(),
        vaultManager    = VaultManager(tmp.newFolder("vm"), MutableStateFlow(emptySet())),
        harness         = harness,
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * First turn of a vault-mode conversation → harness not yet initialized →
     * systemPrompt must be non-empty (built from SYSTEM.md / fallback).
     */
    @Test
    fun `vault-mode first turn receives non-empty systemPrompt`() = runBlocking {
        val session = FakeSession()
        val engine  = makeEngine(session = session, mode = "vault")

        engine.startTurn("conv-vault-first", "hello")

        val prompt = session.prompts.receive()
        assertThat(prompt).isNotEmpty()
    }

    /**
     * Non-vault conversation, first turn → harness not yet initialized →
     * systemPrompt must be non-empty (lifeos context injected for ALL conversations).
     */
    @Test
    fun `non-vault conversation first turn receives non-empty systemPrompt`() = runBlocking {
        val session = FakeSession()
        val engine  = makeEngine(session = session, mode = "default")

        engine.startTurn("conv-default-first", "hello")

        val prompt = session.prompts.receive()
        assertThat(prompt).isNotEmpty()
    }

    /**
     * Any conversation, second turn → harness already initialized →
     * systemPrompt still contains date + lifeos-config (always injected),
     * but hook addenda are NOT re-run (initialized set gates them to first turn only).
     */
    @Test
    fun `any conversation second turn receives non-empty systemPrompt`() = runBlocking {
        val session = FakeSession()
        val engine  = makeEngine(session = session, mode = "default")

        engine.startTurn("conv-default-multi", "first message")
        session.prompts.receive() // consume first prompt

        engine.startTurn("conv-default-multi", "second message")
        val secondPrompt = session.prompts.receive()
        // SessionHarness always builds date + lifeos-config + system.md, even on second turn.
        assertThat(secondPrompt).isNotEmpty()
    }

    /**
     * RED: Fails to compile until startTurn accepts activeVaultIds.
     *
     * When activeVaultIds is non-empty only the specified vaults should be passed to
     * buildSystemPrompt.  Here vault-b has its own SYSTEM.md and vault-a does not —
     * filtering to {vault-b} must yield vault-b's prompt, not vault-a's.
     */
    @Test
    fun `startTurn with activeVaultIds filters vaults used for system prompt`() = runBlocking {
        val dirA = tmp.newFolder("sysA")
        // vault-a has no SYSTEM.md — would fall through to fallback if selected
        val dirB = tmp.newFolder("sysB")
        File(dirB, "SYSTEM.md").writeText("CONTENT_FROM_VAULT_B")

        val entityA = VaultEntity(id = "vault-a", name = "A", localPath = dirA.absolutePath)
        val entityB = VaultEntity(id = "vault-b", name = "B", localPath = dirB.absolutePath)

        val session = FakeSession()
        val engine = InferenceEngine(
            context         = fakeContext(),
            session         = session,
            toolRegistry    = ToolRegistry(emptyList()),
            hookRegistry    = HookRegistry(emptyList()),
            turnDao         = fakeTurnDao(),
            turnEventDao    = fakeTurnEventDao(),
            conversationDao = fakeConversationDao("default"),
            vaultRegistry   = fakeVaultRegistryWithVaults(listOf(entityA, entityB)),
            vaultManager    = VaultManager(tmp.newFolder("vm2"), MutableStateFlow(emptySet())),
            harness         = fakeHarness(),
        )

        // Only vault-b is active — vault-a should be excluded
        engine.startTurn("conv-vault-filter", "hello", activeVaultIds = setOf("vault-b"))

        val prompt = session.prompts.receive()
        assertThat(prompt).contains("CONTENT_FROM_VAULT_B")
    }

    /**
     * Second turn of a vault-mode conversation → harness already initialized →
     * systemPrompt still contains date + lifeos-config (always injected),
     * but hook addenda are NOT re-run (initialized set gates them to first turn only).
     */
    @Test
    fun `vault-mode second turn receives non-empty systemPrompt`() = runBlocking {
        val harness = fakeHarness()
        val session = FakeSession()
        val engine  = makeEngine(session = session, mode = "vault", harness = harness)

        // First turn — initializes the harness for this conversation
        engine.startTurn("conv-vault-multi", "first message")
        val firstPrompt = session.prompts.receive()
        assertThat(firstPrompt).isNotEmpty()

        // Second turn — harness already initialized; date + lifeos-config still injected
        engine.startTurn("conv-vault-multi", "second message")
        val secondPrompt = session.prompts.receive()
        // SessionHarness always builds date + lifeos-config + system.md, even on second turn.
        assertThat(secondPrompt).isNotEmpty()
    }
}
