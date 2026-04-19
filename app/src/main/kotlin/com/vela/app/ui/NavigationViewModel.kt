package com.vela.app.ui

import androidx.lifecycle.ViewModel
import com.vela.app.events.EventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel scoped to [NavigationScaffold].
 *
 * Carries [EventBus] into the Compose navigation layer so that system events
 * (`vela:theme-changed`, `vela:layout-changed`) can be published via
 * [LaunchedEffect] blocks without requiring a Compose-side coroutine scope.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    val eventBus: EventBus,
) : ViewModel()
