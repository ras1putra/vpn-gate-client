package com.vpngate.client.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vpngate.client.BuildConfig
import com.vpngate.client.model.VpnServer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface BackendApi {

    @GET("/api/servers")
    suspend fun getActiveServers(
        @Query("type") type: String? = null,
        @Query("country") country: String? = null
    ): List<VpnServer>

    @GET("/api/servers/all")
    suspend fun getAllServers(): List<VpnServer>

    @GET("/api/servers/ip/{ip}")
    suspend fun getServerByIp(@Path("ip") ip: String): VpnServer

    @GET("/api/health")
    suspend fun healthCheck(): Map<String, Any>

    companion object {
        fun create(baseUrl: String = BuildConfig.BACKEND_URL): BackendApi {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl.trimEnd('/') + "/")
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(BackendApi::class.java)
        }
    }
}
