package com.vpngate.client

import com.vpngate.client.validator.IpValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnClientTest {

    @Test
    fun testIsHostnameResidential() {
        // Assert verified residential domains are identified as residential
        assertTrue(IpValidator.isHostnameResidential("vg189.ocn.ne.jp"))
        assertTrue(IpValidator.isHostnameResidential("pc-cust.hinet.net"))
        assertTrue(IpValidator.isHostnameResidential("dynamic-cable.softbank.jp"))

        // Assert datacenter/VPS domains are rejected
        assertFalse(IpValidator.isHostnameResidential("vps-node42.digitalocean.com"))
        assertFalse(IpValidator.isHostnameResidential("compute.amazonaws.com"))
        assertFalse(IpValidator.isHostnameResidential("server.hetzner.de"))
    }
}
