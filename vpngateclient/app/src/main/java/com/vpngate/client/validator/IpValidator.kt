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

    private val RESIDENTIAL_PATTERNS = listOf(
        "softbank", "ocn", "so-net", "kopt", "plala", "dion", "mesh", "asahi-net", "ucom", "t-com", "spmode",
        "hinet", "seed.net", "kbronet", "kornet", "bora", "dacom", "skbroadband",
        "comcast", "charter", "rr.com", "twc.com", "cox.net", "verizon", "bell.ca", "rogers", "shaw.ca",
        "btcentralplus", "virginmedia", "telekom", "proxad", "free.fr", "adsl", "cable", "fiber", "home",
        "dynamic", "pool", "cust", "user", "client", "isp", "telecom"
    )

    private val DATACENTER_PATTERNS = listOf(
        "vps", "server", "cloud", "host", "dedi", "m2m", "datacenter", "dedicated",
        "amazon", "aws", "google", "azure", "digitalocean", "linode", "ovh", "scaleway", "hetzner",
        "colocation", "leaseweb", "vultr", "choopa", "m2m", "node", "daemon", "proxy"
    )

    fun isHostnameResidential(hostname: String): Boolean {
        val lower = hostname.lowercase(Locale.ROOT)
        
        if (DATACENTER_PATTERNS.any { lower.contains(it) }) {
            return false
        }

        if (RESIDENTIAL_PATTERNS.any { lower.contains(it) }) {
            return true
        }

        return true
    }

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

        val isAcademic = h.contains("open.ad.jp") || c.contains("open.ad.jp") ||
                         h.contains("academic") || c.contains("academic") || o.contains("academic") ||
                         o.contains("nobori") || o.contains("tsukuba")
        if (isAcademic) return ServerType.ACADEMIC

        val isDatacenter = DATACENTER_PATTERNS.any { h.contains(it) || c.contains(it) || o.contains(it) }
        if (isDatacenter) return ServerType.DATACENTER

        return ServerType.RESIDENTIAL
    }

    suspend fun validateServers(servers: List<VpnServer>): List<VpnServer> = withContext(Dispatchers.Default) {
        val semaphore = Semaphore(16)
        val deferredResolutions = servers.map { server ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val canonicalHost = withTimeoutOrNull(800) {
                            resolveCanonicalHostName(server.ip)
                        } ?: ""
                        val type = classifyServer(server.hostName, canonicalHost, server.operator)
                        server.copy(
                            serverType = type,
                            isStealth = (type == ServerType.RESIDENTIAL)
                        )
                    } catch (e: Exception) {
                        val type = classifyServer(server.hostName, "", server.operator)
                        server.copy(
                            serverType = type,
                            isStealth = (type == ServerType.RESIDENTIAL)
                        )
                    }
                }
            }
        }
        deferredResolutions.awaitAll()
    }
}
