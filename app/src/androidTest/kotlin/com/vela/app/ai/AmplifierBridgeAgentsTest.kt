package com.vela.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AmplifierBridgeAgentsTest {

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun nativeListAgents_returns_six_foundation_agents_for_empty_vault() {
        val emptyVault = File(cacheDir, "vault_empty_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val json = AmplifierBridge.nativeListAgents(emptyVault.absolutePath)
            val agents = AgentRef.parseJsonArray(json)

            // All six foundation agents must be present
            val names = agents.map { it.name }.toSet()
            for (expected in listOf(
                "explorer", "zen-architect", "bug-hunter",
                "git-ops", "modular-builder", "security-guardian",
            )) {
                assertThat(names).contains(expected)
            }
        } finally {
            emptyVault.deleteRecursively()
        }
    }

    @Test
    fun nativeListAgents_picks_up_vault_agents_directory() {
        val vault = File(cacheDir, "vault_with_agents_${System.currentTimeMillis()}").apply { mkdirs() }
        val agentsDir = File(vault, ".agents").apply { mkdirs() }
        File(agentsDir, "smoke.md").writeText(
            """
            ---
            meta:
              name: smoke-agent
              description: A test agent installed in the vault.
            ---
            You are a smoke-test agent.
            """.trimIndent()
        )
        try {
            val json   = AmplifierBridge.nativeListAgents(vault.absolutePath)
            val agents = AgentRef.parseJsonArray(json)
            assertThat(agents.map { it.name }).contains("smoke-agent")
            // Foundation agents must still be there alongside.
            assertThat(agents.map { it.name }).contains("explorer")
        } finally {
            vault.deleteRecursively()
        }
    }

    @Test
    fun nativeListAgents_returns_valid_json_for_blank_vault_path() {
        val json = AmplifierBridge.nativeListAgents("")
        // Must not crash; must parse; must contain the foundation agents (registry built with "")
        val agents = AgentRef.parseJsonArray(json)
        assertThat(agents.map { it.name }).contains("explorer")
    }
}
