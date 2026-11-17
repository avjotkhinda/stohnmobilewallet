package org.stohncoin.wallet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestnetPeerTest {
    @Test
    fun blankPeerClearsTheSetting() {
        assertNull(TestnetPeer.normalize(null))
        assertNull(TestnetPeer.normalize(""))
        assertNull(TestnetPeer.normalize("   "))
    }

    @Test
    fun hostAndPortAreAccepted() {
        assertEquals("node.example.org", TestnetPeer.normalize(" node.example.org "))
        assertEquals("1.2.3.4:47218", TestnetPeer.normalize("1.2.3.4:47218"))
        assertEquals("[2001:db8::1]:47218", TestnetPeer.normalize("[2001:db8::1]:47218"))
    }

    @Test
    fun invalidPeersAreRejected() {
        for (bad in listOf("http://node", "node/path", "node name", "1.2.3.4:0", "1.2.3.4:70000", "node:abc", "2001:db8::1")) {
            var failed = false
            try {
                TestnetPeer.normalize(bad)
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue("expected rejection: $bad", failed)
        }
    }

    @Test
    fun addressFillsInTheNetworkDefaultPort() {
        val testnet = TestnetPeer.address("node.example.org", 47218)

        assertEquals(TestnetPeer.Address("node.example.org", 47218, explicitPort = false), testnet)
        assertEquals("node.example.org:47218", testnet?.formatted)
        assertEquals("node.example.org", testnet?.normalized)
        assertNull(TestnetPeer.address("   ", 47218))
    }

    @Test
    fun addressKeepsAnExplicitPortAndUnwrapsIpv6() {
        val ipv6 = TestnetPeer.address("[2001:db8::1]", 47218)

        assertEquals("[2001:db8::1]", ipv6?.host)
        assertEquals(47218, ipv6?.port ?: -1)
        assertEquals("[2001:db8::1]:47218", ipv6?.formatted)
        assertEquals("[2001:db8::1]", ipv6?.normalized)
        assertEquals("2001:db8::1", ipv6?.bareHost)

        val explicit = TestnetPeer.address("1.2.3.4:37218", 47218)
        assertEquals("1.2.3.4:37218", explicit?.formatted)
        assertTrue(explicit?.explicitPort == true)
    }

    @Test
    fun malformedHostsAreRejected() {
        for (bad in listOf("-node.example", "node-.example", "node..example", "1.2.3.256", "12345")) {
            var failed = false
            try {
                TestnetPeer.address(bad, 47218)
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue("expected rejection: $bad", failed)
        }
    }

    @Test
    fun networkArgumentsSkipASavedPeerThatNoLongerValidates() {
        // Both were accepted before the host rules tightened (underscore, leading dash).
        assertEquals(listOf("-testnet"), RuntimeBackend.networkArguments(RuntimeBackend.Network.TESTNET, "node_bad.example:47218"))
        assertTrue(RuntimeBackend.networkArguments(RuntimeBackend.Network.MAINNET, "-node.example").isEmpty())
        assertEquals(
            "Skipping saved Testnet peer that is no longer valid: node_bad.example:47218",
            RuntimeBackend.peerArgumentWarning(RuntimeBackend.Network.TESTNET, "node_bad.example:47218")
        )
        assertNull(RuntimeBackend.peerArgumentWarning(RuntimeBackend.Network.TESTNET, "node.example.org"))
        assertNull(RuntimeBackend.peerArgumentWarning(RuntimeBackend.Network.TESTNET, null))
    }

    @Test
    fun testnetArgumentsAddTheConfiguredPeer() {
        val args = RuntimeBackend.networkArguments(RuntimeBackend.Network.TESTNET, "node.example.org:47218")
        assertEquals(listOf("-testnet", "-addnode=node.example.org:47218"), args)
    }

    @Test
    fun mainnetAlsoAcceptsACustomPeer() {
        val args = RuntimeBackend.networkArguments(RuntimeBackend.Network.MAINNET, "node.example.org")
        assertEquals(listOf("-addnode=node.example.org"), args)
    }
}
