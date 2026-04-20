package com.vela.app.server

    import android.util.Log
    import io.ktor.server.cio.CIO
    import io.ktor.server.engine.ApplicationEngine
    import io.ktor.server.engine.embeddedServer
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.SupervisorJob
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.launch
    import javax.inject.Inject
    import javax.inject.Singleton

    @Singleton
    class VelaMiniAppServer @Inject constructor(
        private val routes: VelaMiniAppRoutes,
    ) {
        companion object {
            const val DEFAULT_HOST = "127.0.0.1"
            const val DEFAULT_PORT = 7701
            private val FALLBACK_PORTS = listOf(7701, 7702, 7703)
            const val TAG = "VelaMiniAppServer"
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var engine: ApplicationEngine? = null

        private val _port = MutableStateFlow(DEFAULT_PORT)
        val port: StateFlow<Int> = _port.asStateFlow()

        private val _isReady = MutableStateFlow(false)
        val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

        private val _lanEnabled = MutableStateFlow(false)
        val lanEnabled: StateFlow<Boolean> = _lanEnabled.asStateFlow()

        fun start(host: String = DEFAULT_HOST) {
            scope.launch {
                _isReady.value = false
                var bound = false
                for (tryPort in FALLBACK_PORTS) {
                    try {
                        engine = embeddedServer(CIO, host = host, port = tryPort) {
                            routes.install(this)
                        }.start(wait = false)
                        _port.value = tryPort
                        _lanEnabled.value = host != DEFAULT_HOST
                        _isReady.value = true
                        bound = true
                        Log.i(TAG, "Server started on $host:$tryPort")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Port $tryPort unavailable: ${e.message}")
                    }
                }
                if (!bound) Log.e(TAG, "All ports unavailable — mini app server not started")
            }
        }

        fun restart(host: String) {
            engine?.stop(1_000, 2_000)
            engine = null
            start(host)
        }

        fun stop() {
            engine?.stop(1_000, 2_000)
            _isReady.value = false
        }
    }
    