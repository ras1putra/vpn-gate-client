package com.vpngate.client.model

enum class ServerType {
    RESIDENTIAL,
    ACADEMIC,
    DATACENTER
}

data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val ping: Int,
    val speed: Long, // speed in bps
    val countryLong: String,
    val countryShort: String,
    val operator: String,
    val openVpnConfigBase64: String,
    var serverType: ServerType = ServerType.RESIDENTIAL,
    val uptime: String = "Unknown",
    val method: String = "UDP"
) {
    val speedMbs: Double
        get() = speed.toDouble() / 1_000_000.0

    val isResidential: Boolean
        get() = serverType == ServerType.RESIDENTIAL
}
