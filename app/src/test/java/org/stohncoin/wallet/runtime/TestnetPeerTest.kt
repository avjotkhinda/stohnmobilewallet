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
    fun testnetArgumentsAddTheConfiguredPeer() {
        val args = RuntimeBackend.networkArguments(RuntimeBackend.Network.TESTNET, "node.example.org:47218")
        assertEquals(listOf("-testnet", "-addnode=node.example.org:47218"), args)
    }

    @Test
    fun mainnetIgnoresTheTestnetPeer() {
        val args = RuntimeBackend.networkArguments(RuntimeBackend.Network.MAINNET, "node.example.org")
        assertTrue(args.isEmpty())
    }
}
