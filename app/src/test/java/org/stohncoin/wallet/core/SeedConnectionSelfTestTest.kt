package org.stohncoin.wallet.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stohncoin.wallet.runtime.RuntimeBackend
import java.net.UnknownHostException

class SeedConnectionSelfTestTest {

    @Test
    fun mainnetTargetsIncludeBundledFixedSeedsAndCustomPeer() {
        val targets = SeedConnectionSelfTest.targets(
            RuntimeBackend.Network.MAINNET,
            "203.0.113.7:37218",
        )

        assertEquals(SeedConnectionSelfTest.MAINNET_FIXED_SEEDS.size + 1, targets.size)
        assertTrue(targets.take(SeedConnectionSelfTest.MAINNET_FIXED_SEEDS.size).all { it.label == "bundled fixed seed" })
        assertEquals(SeedConnectionSelfTest.MAINNET_PORT, targets.first().port)
        val custom = targets.last()
        assertEquals("custom peer", custom.label)
        assertEquals("203.0.113.7", custom.host)
        assertEquals(37218, custom.port)
    }

    @Test
    fun testnetTargetsOnlyIncludeCustomPeer() {
        assertTrue(SeedConnectionSelfTest.targets(RuntimeBackend.Network.TESTNET, null).isEmpty())

        val targets = SeedConnectionSelfTest.targets(
            RuntimeBackend.Network.TESTNET,
            "testnet.example.org",
        )
        assertEquals(1, targets.size)
        assertEquals("custom peer", targets[0].label)
        assertEquals("testnet.example.org", targets[0].host)
        assertEquals(SeedConnectionSelfTest.TESTNET_PORT, targets[0].port)
    }

    @Test
    fun parsePeerAcceptsBracketedIpv6WithPort() {
        val parsed = SeedConnectionSelfTest.parsePeer("[2001:db8::1]:47218", SeedConnectionSelfTest.TESTNET_PORT)
        assertEquals("2001:db8::1" to 47218, parsed)
    }

    @Test
    fun runReportsNoBundledSeedsOnTestnetWithoutPeer() = runBlocking {
        val report = SeedConnectionSelfTest.run(RuntimeBackend.Network.TESTNET, null)

        assertTrue(report.contains("Network: Testnet"))
        assertTrue(report.contains("no DNS or fixed seeds"))
        assertTrue(report.contains("Custom peer: none"))
        assertTrue(report.contains("No seed or peer targets to probe for this network."))
        assertTrue(report.contains("cannot test reachability without a custom peer"))
    }

    @Test
    fun runReportsDnsFailureAndSkipsTcp() = runBlocking {
        val report = SeedConnectionSelfTest.run(
            network = RuntimeBackend.Network.TESTNET,
            customPeer = "bad.example.org",
            resolver = { Result.failure(UnknownHostException("unable to resolve host")) },
            connector = { _, _, _ -> error("TCP should not run when DNS fails") },
        )

        assertTrue(report.contains("custom peer: bad.example.org:${SeedConnectionSelfTest.TESTNET_PORT}"))
        assertTrue(report.contains("DNS: FAILED"))
        assertTrue(report.contains("DNS lookup failed"))
        assertTrue(report.contains("TCP: skipped (no resolved addresses)"))
    }

    @Test
    fun runReportsTcpSuccessAndFailureForResolvedAddresses() = runBlocking {
        val report = SeedConnectionSelfTest.run(
            network = RuntimeBackend.Network.TESTNET,
            customPeer = "node.example.org:47218",
            resolver = { Result.success(listOf("203.0.113.10", "203.0.113.11")) },
            connector = { host, _, _ ->
                if (host == "203.0.113.10") Result.success(Unit)
                else Result.failure(java.net.ConnectException("Connection refused"))
            },
        )

        assertTrue(report.contains("DNS: resolved 2 address(es): 203.0.113.10, 203.0.113.11"))
        assertTrue(report.contains("TCP 203.0.113.10:47218: OK (connected)"))
        assertTrue(report.contains("TCP 203.0.113.11:47218: FAILED"))
        assertTrue(report.contains("Connection refused"))
    }
}
