package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentRefTest {

    @Test
    fun parsesEmptyJsonArray() {
        val result = AgentRef.parseJsonArray("[]")
        assertThat(result).isEmpty()
    }

    @Test
    fun parsesSingleAgent() {
        val json = """[{"name":"explorer","description":"recon","tools":["filesystem"]}]"""
        val result = AgentRef.parseJsonArray(json)
        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("explorer")
        assertThat(result[0].description).isEqualTo("recon")
        assertThat(result[0].tools).containsExactly("filesystem")
    }

    @Test
    fun parsesMultipleFoundationAgents() {
        val json = """
            [
                {"name":"explorer","description":"a","tools":[]},
                {"name":"zen-architect","description":"b","tools":[]},
                {"name":"bug-hunter","description":"c","tools":[]}
            ]
        """.trimIndent()
        val result = AgentRef.parseJsonArray(json)
        assertThat(result.map { it.name }).containsExactly("explorer", "zen-architect", "bug-hunter").inOrder()
    }

    @Test
    fun toleratesMissingToolsField() {
        val json = """[{"name":"x","description":"y"}]"""
        val result = AgentRef.parseJsonArray(json)
        assertThat(result).hasSize(1)
        assertThat(result[0].tools).isEmpty()
    }

    @Test
    fun returnsEmptyListOnMalformedInput() {
        assertThat(AgentRef.parseJsonArray("not json")).isEmpty()
        assertThat(AgentRef.parseJsonArray("")).isEmpty()
    }
}
