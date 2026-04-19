package com.vela.app.di

import javax.inject.Qualifier

/**
 * Qualifier for the Vela system event bus — the [EventBus] singleton
 * that carries system and mini-app events between native Kotlin code and WebViews.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VelaEventBus
