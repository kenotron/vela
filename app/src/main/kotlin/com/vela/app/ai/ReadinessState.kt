package com.vela.app.ai

    sealed class ReadinessState {
        object Available : ReadinessState()
        data class Downloading(val progress: Float? = null) : ReadinessState()
        object Downloadable : ReadinessState()
        object Unavailable : ReadinessState()
    }
    