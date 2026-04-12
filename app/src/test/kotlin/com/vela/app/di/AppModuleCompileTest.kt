package com.vela.app.di

import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.GemmaEngine
import com.vela.app.ai.LifecycleAwareEngine
import com.vela.app.ai.MlKitGemma4Engine
import org.junit.Test

class AppModuleCompileTest {
    @Test
    fun `LifecycleAwareEngine extends GemmaEngine`() {
        // Compile-time assertion that the interface hierarchy is correct
        val engineClass: Class<out GemmaEngine> = LifecycleAwareEngine::class.java
        assert(GemmaEngine::class.java.isAssignableFrom(engineClass))
    }

    @Test
    fun `MlKitGemma4Engine implements LifecycleAwareEngine`() {
        assert(LifecycleAwareEngine::class.java.isAssignableFrom(MlKitGemma4Engine::class.java))
    }

    @Test
    fun `FakeGemmaEngine implements GemmaEngine but not LifecycleAwareEngine`() {
        assert(GemmaEngine::class.java.isAssignableFrom(FakeGemmaEngine::class.java))
        assert(!LifecycleAwareEngine::class.java.isAssignableFrom(FakeGemmaEngine::class.java))
    }
}
