package com.vela.app.tailscale

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Tailscale REST API.
 *
 * Authentication: OAuth client credentials or API key.
 * Docs: https://tailscale.com/api
 *
 * Usage: create a new instance per API key (stateless, no singleton needed).
 */
class TailscaleApiClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${res.body?.string()?.take(200)}")
            res.body?.string() ?: throw IOException("Empty response body")
        }
    }

    /**
     * List all devices on the authenticated user's tailnet.
     * Only returns devices with at least one 100.x.x.x (Tailscale) IPv4 address.
     */
    suspend fun listDevices(): List<TailscaleDevice> {
        val json = JSONObject(get("https://api.tailscale.com/api/v2/tailnet/-/devices"))
        val arr: JSONArray = json.optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val d = arr.getJSONObject(i)
            val addresses = d.optJSONArray("addresses") ?: return@mapNotNull null
            // Find first 100.x.x.x address
            val tsIp = (0 until addresses.length())
                .map { addresses.getString(it) }
                .firstOrNull { it.startsWith("100.") }
                ?: return@mapNotNull null  // Not a Tailscale device
            TailscaleDevice(
                id          = d.optString("id", ""),
                name        = d.optString("name", "").substringBefore("."),
                displayName = d.optString("displayName", "").ifBlank {
                    d.optString("name", "").substringBefore(".")
                },
                os          = d.optString("os", ""),
                tailscaleIp = tsIp,
                isOnline    = d.optBoolean("online", false),
                lastSeen    = d.optString("lastSeen", ""),
            )
        }
    }
}
