package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun parsesSimpleToolCallWithNoArgs() {
        val json = """{"tool":"get_time","args":{}}"""
        val result = ToolCallParser.parse(json)
        assertThat(result).isNotNull()
        assertThat(result!!.toolName).isEqualTo("get_time")
        assertThat(result.args).isEmpty()
    }

    @Test
    fun parsesToolCallWithStringArg() {
        val json = """{"tool":"set_alarm","args":{"time":"07:30"}}"""
        val result = ToolCallParser.parse(json)
        assertThat(result).isNotNull()
        assertThat(result!!.toolName).isEqualTo("set_alarm")
        assertThat(result.args["time"]).isEqualTo("07:30")
    }

    @Test
    fun parsesToolCallEmbeddedInProse() {
        val response = """Let me check for you. {"tool":"get_date","args":{}}"""
        val result = ToolCallParser.parse(response)
        assertThat(result).isNotNull()
        assertThat(result!!.toolName).isEqualTo("get_date")
    }

    @Test
    fun returnsNullForPlainText() {
        assertThat(ToolCallParser.parse("It is 3 PM.")).isNull()
        assertThat(ToolCallParser.parse("")).isNull()
    }

    @Test
    fun returnsNullForVelaUiJson() {
        // vela-ui JSON has no "tool" key — should not be detected as a tool call
        val velaUi = """{"type":"vela-ui","components":[{"t":"card","title":"Test"}]}"""
        assertThat(ToolCallParser.parse(velaUi)).isNull()
    }

    @Test
    fun returnsNullForMalformedJson() {
        assertThat(ToolCallParser.parse("""{"tool":""")).isNull()
    }

    @Test
    fun returnsNullForJsonWithoutToolKey() {
        assertThat(ToolCallParser.parse("""{"name":"get_time"}""")).isNull()
    }

    @Test
    fun parsesGetBatteryCall() {
        val json = """{"tool":"get_battery","args":{}}"""
        val result = ToolCallParser.parse(json)
        assertThat(result).isNotNull()
        assertThat(result!!.toolName).isEqualTo("get_battery")
    }
}
