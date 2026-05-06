package com.vela.app.ssh

/**
 * Streaming events emitted by the bootstrap pipeline. The UI subscribes to a
 * Flow<BootstrapEvent> to render progress, terminal output, and final outcome.
 */
sealed class BootstrapEvent {
    /** A line of stdout/stderr from a remote command. */
    data class Output(val line: String) : BootstrapEvent()

    /** Bootstrap pipeline has entered a new step. */
    data class StepStart(val step: BootstrapStep) : BootstrapEvent()

    /** A step finished successfully. */
    data class StepComplete(val step: BootstrapStep) : BootstrapEvent()

    /** A step failed. [logs] holds the most recent terminal output for diagnostics. */
    data class Failed(
        val step: BootstrapStep,
        val error: String,
        val logs: List<String> = emptyList(),
    ) : BootstrapEvent()

    /**
         * Bootstrap completed; amplifierd is reachable at [url] with shared secret [token].
         * [tailscaleUrl] is the Tailscale IP URL if detected on the remote machine, empty otherwise.
         * [url] is always the LAN/SSH-host URL (http://{sshHost}:8410).
         */
        data class Complete(
            val url:          String,
            val tailscaleUrl: String = "",
            val token:        String,
        ) : BootstrapEvent()
}

/** Ordered phases of the bootstrap pipeline (see design doc Section 1). */
enum class BootstrapStep {
    CONNECT,          // open JSch session
    DETECT,           // uname -sm platform detection
    INSTALL_UV,       // install uv if absent
    INSTALL_AMPLIFIERD, // uv tool install amplifierd + plugins
    WRITE_CONFIG,     // SFTP settings.json to remote
    INSTALL_SERVICE,  // write + activate launchd plist or systemd unit
    HEALTH_CHECK,     // poll /health until 200 or timeout
    PROMOTE           // upgrade node to AMPLIFIERD in Room
}
