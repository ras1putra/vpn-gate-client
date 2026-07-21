package com.vpngate.client.scraper

import android.util.Log
import com.vpngate.client.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object VpnGateScraper {
    private const val TAG = "VpnGateScraper"
    private const val API_URL = "https://www.vpngate.net/api/iphone/"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchServers(): List<VpnServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<VpnServer>()
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch VPN list: HTTP ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body.string()
                val lines = body.split("\n")
                
                var headerFound = false
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("*")) {
                        continue
                    }

                    if (trimmed.startsWith("#HostName") || trimmed.startsWith("HostName")) {
                        headerFound = true
                        continue
                    }

                    if (headerFound) {
                        val parsed = parseCsvLine(trimmed)
                        if (parsed != null) {
                            servers.add(parsed)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network exception fetching VPN list", e)
        } catch (e: Exception) {
            Log.e(TAG, "Parsing exception", e)
        }

        return@withContext servers
    }

    private fun parseCsvLine(line: String): VpnServer? {
        val tokens = line.split(",")
        if (tokens.size < 11) {
            return null
        }

        return try {
            val hostName = tokens[0].trim()
            val ip = tokens[1].trim()
            val score = tokens[2].trim().toLongOrNull() ?: 0L
            val ping = tokens[3].trim().toIntOrNull() ?: 999
            val speed = tokens[4].trim().toLongOrNull() ?: 0L
            val countryLong = tokens[5].trim()
            val countryShort = tokens[6].trim()
            
            val uptimeSeconds = tokens.getOrNull(8)?.trim()?.toLongOrNull() ?: 0L
            val uptimeText = if (uptimeSeconds > 0) {
                uptimeSeconds.toString()
            } else {
                "Unknown"
            }

            val operator = tokens.getOrNull(12)?.trim() ?: "VPNGate"

            val openVpnConfigBase64 = tokens.last().trim()

            if (ip.isEmpty() || openVpnConfigBase64.isEmpty()) {
                return null
            }

            var parsedMethod = "UDP"
            try {
                val decodedBytes = android.util.Base64.decode(openVpnConfigBase64, android.util.Base64.DEFAULT)
                val decodedConfig = String(decodedBytes, Charsets.UTF_8)
                if (decodedConfig.contains("proto tcp", ignoreCase = true) || 
                    decodedConfig.contains("tcp-client", ignoreCase = true)) {
                    parsedMethod = "TCP"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode Base64 OpenVPN config for $hostName", e)
            }

            VpnServer(
                hostName = hostName,
                ip = ip,
                score = score,
                ping = ping,
                speed = speed,
                countryLong = countryLong,
                countryShort = countryShort,
                operator = operator,
                openVpnConfigBase64 = openVpnConfigBase64,
                uptime = uptimeText,
                method = parsedMethod
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse line: $line", e)
            null
        }
    }
}
