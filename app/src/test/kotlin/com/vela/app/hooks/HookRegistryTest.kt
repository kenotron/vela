package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class HookRegistryTest {

    private val executionOrder = mutableListOf<Int>()

    private val priority0 = object : Hook {
        override val event    = HookEvent.SESSION_START
        override val priority = 0
        override suspend fun execute(ctx: HookContext): HookResult {
            executionOrder.add(0)
            return HookResult.SystemPromptAddendum("first")
        }
    }

    private val priority10 = object : Hook {
        override val event    = HookEvent.SESSION_START
        override val priority = 10
        override suspend fun execute(ctx: HookContext): HookResult {
            executionOrder.add(10)
            return HookResult.SystemPromptAddendum("second")
        }
    }

    private val wrongEvent = object : Hook {
        override val event = HookEvent.SESSION_END
        override suspend fun execute(ctx: HookContext) =
            HookResult.SystemPromptAddendum("should-not-appear")
    }

    // Registry created with high-priority first to confirm sort is applied
    private val registry = HookRegistry(listOf(priority10, priority0, wrongEvent))

    private val ctx = HookContext(
        conversationId = "test-conv",
        activeVaults   = emptyList(),
        event          = HookEvent.SESSION_START,
    )

    @Test fun `hooks execute in ascending priority order regardless of registration order`() = runBlocking {
        registry.fire(HookEvent.SESSION_START, ctx)
        assertThat(executionOrder).isEqualTo(listOf(0, 10))
    }

    @Test fun `only hooks matching the event fire`() = runBlocking {
        val results = registry.fire(HookEvent.SESSION_START, ctx)
        assertThat(results).hasSize(2)
    }

    @Test fun `collectAddenda joins addenda in priority order with double newline`() = runBlocking {
        val text = registry.collectAddenda(HookEvent.SESSION_START, ctx)
        assertThat(text).isEqualTo("first\n\nsecond")
    }

    @Test fun `Continue results are excluded from collectAddenda`() = runBlocking {
        val continueHook = object : Hook {
            override val event = HookEvent.SESSION_START
            override suspend fun execute(ctx: HookContext) = HookResult.Continue
        }
        val r = HookRegistry(listOf(continueHook))
        assertThat(r.collectAddenda(HookEvent.SESSION_START, ctx)).isEmpty()
    }
}
