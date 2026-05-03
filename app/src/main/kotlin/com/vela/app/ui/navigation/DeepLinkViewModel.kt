package com.vela.app.ui.navigation

    import androidx.lifecycle.ViewModel
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import javax.inject.Inject

    /**
     * Activity-scoped ViewModel that carries notification deep-link routes
     * from [com.vela.app.MainActivity] (where Intent extras are read) into
     * [VelaApp] (where the NavController lives).
     *
     * Survives configuration changes. [navigate] is called from both
     * [MainActivity.onCreate] (cold start) and [MainActivity.onNewIntent]
     * (notification tap while app is running). [consumed] must be called
     * after navigation to prevent re-navigation on recomposition.
     */
    @HiltViewModel
    class DeepLinkViewModel @Inject constructor() : ViewModel() {

        private val _pendingRoute = MutableStateFlow<String?>(null)
        val pendingRoute: StateFlow<String?> = _pendingRoute

        /** Enqueue [route] to be navigated to by the NavHost. */
        fun navigate(route: String) { _pendingRoute.value = route }

        /** Mark the pending route as consumed after navigation. */
        fun consumed() { _pendingRoute.value = null }
    }
    