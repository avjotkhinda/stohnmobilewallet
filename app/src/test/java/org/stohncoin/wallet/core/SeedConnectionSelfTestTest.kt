package org.stohncoin.wallet.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stohncoin.wallet.runtime.RuntimeBackend
import org.stohncoin.wallet.runtime.TestnetPeer
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
    fun parsePeerKeepsAPeerThatFailsValidation() {
        assertEquals("node_bad.example" to 47218, SeedConnectionSelfTest.parsePeer("node_bad.example:47218", 47218))
        assertEquals("node:abc" to 47218, SeedConnectionSelfTest.parsePeer("node:abc", 47218))
        assertEquals("http://node" to 47218, SeedConnectionSelfTest.parsePeer("http://node", 47218))
    }

    @Test
    fun runReportsACustomPeerThatValidationRejects() = runBlocking {
        val rejected = "node_bad.example:47218"
        var accepted = true
        try {
            TestnetPeer.normalize(rejected)
        } catch (_: IllegalArgumentException) {
            accepted = false
        }
        assertFalse("expected TestnetPeer to reject $rejected", accepted)

        val report = SeedConnectionSelfTest.run(
            network = RuntimeBackend.Network.TESTNET,
            customPeer = rejected,
            resolver = { Result.failure(UnknownHostException("unable to resolve host")) },
            connector = { _, _, _ -> error("TCP should not run when DNS fails") },
        )

        assertTrue(report.contains("Custom peer: $rejected"))
        assertTrue(report.contains("[1/1] custom peer: node_bad.example:47218"))
        assertTrue(report.contains("DNS: FAILED"))
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

    @Test
    fun reachabilitySaysSuccessIsNotACompatibilityCheck() = runBlocking {
        val result = SeedConnectionSelfTest.probePeer(
            host = "node.example.org",
            port = SeedConnectionSelfTest.TESTNET_PORT,
            resolver = { Result.success(listOf("203.0.113.10")) },
            connector = { _, _, _ -> Result.success(Unit) },
        )

        val message = SeedConnectionSelfTest.reachability(result)
        assertTrue(message.contains("Reached node.example.org:47218 over TCP"))
        assertTrue(message.contains("does not confirm a compatible Stohn peer"))
    }

    @Test
    fun reachabilityExplainsRefusedAndTimeout() = runBlocking {
        val refused = SeedConnectionSelfTest.probePeer(
            host = "203.0.113.10",
            port = SeedConnectionSelfTest.TESTNET_PORT,
            resolver = { Result.failure(UnknownHostException("unused")) },
            connector = { _, _, _ -> Result.failure(java.net.ConnectException("Connection refused")) },
        )
        assertTrue(SeedConnectionSelfTest.reachability(refused).contains("refused the connection"))

        val timedOut = SeedConnectionSelfTest.probePeer(
            host = "node.example.org",
            port = SeedConnectionSelfTest.TESTNET_PORT,
            resolver = { Result.success(listOf("203.0.113.10")) },
            connector = { _, _, _ -> Result.failure(java.net.SocketTimeoutException("connect timed out")) },
        )
        assertTrue(SeedConnectionSelfTest.reachability(timedOut).contains("No TCP answer"))
    }

    @Test
    fun reachabilityExplainsDnsFailure() = runBlocking {
        val result = SeedConnectionSelfTest.probePeer(
            host = "bad.example.org",
            port = SeedConnectionSelfTest.TESTNET_PORT,
            resolver = { Result.failure(UnknownHostException("unable to resolve host")) },
            connector = { _, _, _ -> error("TCP should not run when DNS fails") },
        )

        val message = SeedConnectionSelfTest.reachability(result)
        assertTrue(message.contains("Could not resolve bad.example.org"))
        assertTrue(message.contains("DNS lookup failed"))
    }

    @Test
    fun summaryExplainsCustomPeerDnsFailure() = runBlocking {
        val result = SeedConnectionSelfTest.probe(
            network = RuntimeBackend.Network.TESTNET,
            customPeer = "bad.example.org",
            resolver = { Result.failure(UnknownHostException("unable to resolve host")) },
            connector = { _, _, _ -> error("TCP should not run when DNS fails") },
        )

        val summary = SeedConnectionSelfTest.summary(result)
        assertTrue(summary.contains("Could not resolve bad.example.org"))
        assertTrue(summary.contains("DNS lookup failed"))
    }

    @Test
    fun summaryReportsCustomPeerTimeout() = runBlocking {
        val result = SeedConnectionSelfTest.probe(
            network = RuntimeBackend.Network.TESTNET,
            customPeer = "node.example.org",
            resolver = { Result.success(listOf("203.0.113.10")) },
            connector = { _, _, _ -> Result.failure(java.net.SocketTimeoutException("connect timed out")) },
        )

        val summary = SeedConnectionSelfTest.summary(result)
        assertTrue(summary.contains("node.example.org:${SeedConnectionSelfTest.TESTNET_PORT}"))
        assertTrue(summary.contains("timed out"))
    }

    @Test
    fun summaryReportsCustomPeerRefused() = runBlocking {
        val result = SeedConnectionSelfTest.probe(
            network = RuntimeBackend.Network.TESTNET,
            customPeer = "node.example.org",
            resolver = { Result.success(listOf("203.0.113.10")) },
            connector = { _, _, _ -> Result.failure(java.net.ConnectException("Connection refused")) },
        )

        val summary = SeedConnectionSelfTest.summary(result)
        assertTrue(summary.contains("refused the connection"))
    }

    @Test
    fun summaryReportsCustomPeerReachable() = runBlocking {
        val result = SeedConnectionSelfTest.probe(
            network = RuntimeBackend.Network.TESTNET,
            customPeer = "node.example.org:47218",
            resolver = { Result.success(listOf("203.0.113.10")) },
            connector = { _, _, _ -> Result.success(Unit) },
        )

        val summary = SeedConnectionSelfTest.summary(result)
        assertTrue(summary.contains("node.example.org:47218 is reachable"))
        assertTrue(summary.contains("restart the node"))
    }

    @Test
    fun summaryReportsNothingToTestOnTestnetWithoutPeer() = runBlocking {
        val result = SeedConnectionSelfTest.probe(RuntimeBackend.Network.TESTNET, null)

        val summary = SeedConnectionSelfTest.summary(result)
        assertTrue(summary.contains("no bundled seeds to test"))
        assertTrue(summary.contains("Set a custom peer"))
    }

    @Test
    fun summaryCountsReachableBundledSeedsWhenNoCustomPeerIsSet() = runBlocking {
        val result = SeedConnectionSelfTest.probe(
            network = RuntimeBackend.Network.MAINNET,
            customPeer = null,
            resolver = { host -> Result.success(listOf(host)) },
            connector = { _, _, _ -> Result.success(Unit) },
        )

        val seeds = SeedConnectionSelfTest.MAINNET_FIXED_SEEDS.size
        val summary = SeedConnectionSelfTest.summary(result)
        assertTrue(summary.contains("$seeds of $seeds bundled seeds responded"))
    }

    @Test
    fun summaryAddsSeedCountWhenTheCustomPeerFailsOnMainnet() = runBlocking {
        val result = SeedConnectionSelfTest.probe(
            network = RuntimeBackend.Network.MAINNET,
            customPeer = "203.0.113.7",
            resolver = { host -> Result.success(listOf(host)) },
            connector = { host, _, _ ->
                if (host == "203.0.113.7") Result.failure(java.net.ConnectException("Connection refused")) else Result.success(Unit)
            },
        )

        val seeds = SeedConnectionSelfTest.MAINNET_FIXED_SEEDS.size
        val summary = SeedConnectionSelfTest.summary(result)
        assertTrue(summary.contains("refused the connection"))
        assertTrue(summary.contains("Bundled seeds: $seeds of $seeds responded"))
    }
}
