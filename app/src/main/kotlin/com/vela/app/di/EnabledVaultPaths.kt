package com.vela.app.di

import javax.inject.Qualifier

/**
 * Qualifier for the StateFlow<Set<String>> that carries canonical paths of all
 * currently-enabled vaults. Injected into VaultManager to gate file access.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EnabledVaultPaths
