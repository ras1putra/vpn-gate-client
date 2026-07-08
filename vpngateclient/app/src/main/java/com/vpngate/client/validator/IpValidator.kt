package com.vpngate.client.validator

import android.util.Log
import com.vpngate.client.model.VpnServer
import com.vpngate.client.model.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.Locale

object IpValidator {
    private const val TAG = "IpValidator"

    // Common residential ISP signatures in hostnames / reverse DNS
    private val RESIDENTIAL_PATTERNS = listOf(
        "softbank", "ocn", "so-net", "kopt", "plala", "dion", "mesh", "asahi-net", "ucom", "t-com", "spmode",
        "hinet", "seed.net", "kbronet", "kornet", "bora", "dacom", "skbroadband",
        "comcast", "charter", "rr.com", "twc.com", "cox.net", "verizon", "bell.ca", "rogers", "shaw.ca",
        "btcentralplus", "virginmedia", "telekom", "proxad", "free.fr", "adsl", "cable", "fiber", "home",
        "dynamic", "pool", "cust", "user", "client", "isp", "telecom"
    )

    // Datacenter/VPS signatures that we want to filter out
    private val DATACENTER_PATTERNS = listOf(
        "vps", "server", "cloud", "host", "dedi", "m2m", "datacenter", "dedicated",
        "amazon", "aws", "google", "azure", "digitalocean", "linode", "ovh", "scaleway", "hetzner",
        "colocation", "leaseweb", "vultr", "choopa", "m2m", "node", "daemon", "proxy"
    )

    /**
     * Checks if a single hostname matches residential indicators.
     */
    fun isHostnameResidential(hostname: String): Boolean {
        val lower = hostname.lowercase(Locale.ROOT)
        
        if (DATACENTER_PATTERNS.any { lower.contains(it) }) {
            return false
        }

        if (RESIDENTIAL_PATTERNS.any { lower.contains(it) }) {
            return true
        }

        // Fallback: treat as residential unless matched with datacenter signatures
        return true
    }

    /**
     * Performs reverse DNS (rDNS) resolution to find the actual ISP domain of the IP address.
     * Runs asynchronously on the IO dispatcher.
     */
    suspend fun resolveCanonicalHostName(ip: String): String = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(ip)
            address.canonicalHostName
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve reverse DNS for IP: $ip", e)
            ""
        }
    }

    fun classifyServer(hostname: String, canonicalHost: String, operator: String): ServerType {
        val h = hostname.lowercase(Locale.ROOT)
        val c = canonicalHost.lowercase(Locale.ROOT)
        val o = operator.lowercase(Locale.ROOT)

        // 1. Academic check
        val isAcademic = h.contains("open.ad.jp") || c.contains("open.ad.jp") ||
                         h.contains("academic") || c.contains("academic") || o.contains("academic") ||
                         o.contains("nobori") || o.contains("tsukuba")
        if (isAcademic) {
            return ServerType.ACADEMIC
        }

        // 2. Datacenter check
        val isDatacenter = DATACENTER_PATTERNS.any { h.contains(it) || c.contains(it) || o.contains(it) }
        if (isDatacenter) {
            return ServerType.DATACENTER
        }

        // 3. Residential fallback
        return ServerType.RESIDENTIAL
    }

    /**
     * Validates a list of VPN servers. It runs DNS resolutions in parallel on the IO dispatcher,
     * limited by a Semaphore of 16 concurrent requests, and capped at an 800ms timeout per lookup
     * to prevent the loading screen from hanging.
     */
    suspend fun validateServers(servers: List<VpnServer>): List<VpnServer> = withContext(Dispatchers.Default) {
        val semaphore = Semaphore(16)
        val deferredResolutions = servers.map { server ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val canonicalHost = withTimeoutOrNull(800) {
                            resolveCanonicalHostName(server.ip)
                        } ?: ""
                        server.serverType = classifyServer(server.hostName, canonicalHost, server.operator)
                    } catch (e: Exception) {
                        server.serverType = classifyServer(server.hostName, "", server.operator)
                    }
                    server
                }
            }
        }
        deferredResolutions.awaitAll()
    }
}
