package com.vela.app.tailscale

/**
 * A device on the user's Tailscale tailnet, as returned by the Tailscale API.
 * https://tailscale.com/api#tag/devices/GET/tailnet/{tailnet}/devices
 */
data class TailscaleDevice(
    val id:          String,
    val name:        String,     // e.g. "ken-mbp"
    val displayName: String,     // e.g. "ken-mbp"
    val os:          String,     // "macOS", "linux", etc.
    val tailscaleIp: String,     // first 100.x.x.x address
    val isOnline:    Boolean,
    val lastSeen:    String,     // ISO-8601
) {
    /** Friendly label for display: cleaned hostname. */
    val label: String get() = displayName.ifBlank { name }
}
