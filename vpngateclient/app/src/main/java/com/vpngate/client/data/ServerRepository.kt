package com.vpngate.client.data

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vpngate.client.model.VpnServer
import com.vpngate.client.scraper.VpnGateScraper
import com.vpngate.client.validator.IpValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ServerRepository(private val context: Context) {

    companion object {
        private const val TAG = "ServerRepository"
        private const val CACHE_FILE = "servers_cache.json"
    }

    private val backendApi: BackendApi = BackendApi.create()
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val listType = Types.newParameterizedType(List::class.java, VpnServer::class.java)
    private val adapter = moshi.adapter<List<VpnServer>>(listType)

    suspend fun getServers(
        type: String? = null,
        country: String? = null
    ): Result<List<VpnServer>> = withContext(Dispatchers.IO) {
        // 1. Try backend first
        try {
            Log.d(TAG, "Fetching from backend API...")
            val servers = backendApi.getActiveServers(type, country)
            if (servers.isNotEmpty()) {
                Log.d(TAG, "Backend returned ${servers.size} servers")
                saveToCache(servers)
                return@withContext Result.success(servers)
            }
            Log.w(TAG, "Backend returned empty list, falling back")
        } catch (e: Exception) {
            Log.w(TAG, "Backend failed: ${e.message}, falling back to VPN Gate")
        }

        // 2. Fallback: scrape VPN Gate directly
        try {
            Log.d(TAG, "Scraping VPN Gate directly...")
            val scraped = VpnGateScraper.fetchServers()
            if (scraped.isNotEmpty()) {
                val validated = IpValidator.validateServers(scraped)
                Log.d(TAG, "VPN Gate returned ${validated.size} servers")
                saveToCache(validated)
                return@withContext Result.success(validated)
            }
            Log.w(TAG, "VPN Gate returned empty list")
        } catch (e: Exception) {
            Log.w(TAG, "VPN Gate scrape failed: ${e.message}")
        }

        // 3. Fallback: disk cache
        val cached = loadFromCache()
        if (cached.isNotEmpty()) {
            Log.d(TAG, "Using ${cached.size} cached servers")
            return@withContext Result.success(cached)
        }

        Result.failure(Exception("All sources failed"))
    }

    suspend fun fetchConfig(ip: String): Result<VpnServer> = withContext(Dispatchers.IO) {
        try {
            val server = backendApi.getServerByIp(ip)
            if (server.openVpnConfigBase64.isNotEmpty()) {
                return@withContext Result.success(server)
            }
            Result.failure(Exception("Empty config"))
        } catch (e: Exception) {
            Log.w(TAG, "fetchConfig failed for $ip: ${e.message}")
            Result.failure(e)
        }
    }

    private fun saveToCache(servers: List<VpnServer>) {
        try {
            val json = adapter.toJson(servers)
            context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE).use {
                it.write(json.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    private fun loadFromCache(): List<VpnServer> {
        return try {
            if (!context.getFileStreamPath(CACHE_FILE).exists()) return emptyList()
            val json = context.openFileInput(CACHE_FILE).use { it.bufferedReader().readText() }
            adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache", e)
            emptyList()
        }
    }
}
