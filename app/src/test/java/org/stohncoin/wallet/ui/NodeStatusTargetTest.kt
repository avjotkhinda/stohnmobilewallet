package org.stohncoin.wallet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.stohncoin.wallet.core.NodeStorage
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * [NodeStatusTarget.activePeer] replaced three copies of the network check, so this pins the rule
 * they shared: the saved peers never cross networks. Dialling a testnet node on mainnet (or the
 * reverse) would point Core at the wrong chain, which no UI test would otherwise catch.
 */
class NodeStatusTargetTest {

    private fun target(
        network: RuntimeBackend.Network,
        testnetPeer: String? = null,
        mainnetPeer: String? = null
    ) = NodeStatusTarget(
        network = network,
        pruneMode = NodeStorage.Mode.FULL,
        testnetPeer = testnetPeer,
        mainnetPeer = mainnetPeer
    )

    @Test
    fun testnetUsesTheTestnetPeer() {
        val target = target(RuntimeBackend.Network.TESTNET, testnetPeer = "node.testnet.example:47218")

        assertEquals("node.testnet.example:47218", target.activePeer)
    }

    @Test
    fun mainnetUsesTheMainnetPeer() {
        val target = target(RuntimeBackend.Network.MAINNET, mainnetPeer = "node.mainnet.example")

        assertEquals("node.mainnet.example", target.activePeer)
    }

    @Test
    fun withoutANetworkTheDefaultIsMainnetAndItPicksTheMainnetPeer() {
        // The screen is opened without a network, so the default and the peer it selects arrive
        // together: asserting either alone would leave the other free to drift.
        val target = NodeStatusTarget(
            pruneMode = NodeStorage.Mode.FULL,
            testnetPeer = "node.testnet.example:47218",
            mainnetPeer = "node.mainnet.example"
        )

        assertEquals(RuntimeBackend.Network.MAINNET, target.network)
        assertEquals("node.mainnet.example", target.activePeer)
    }

    @Test
    fun testnetNeverUsesTheMainnetPeer() {
        val target = target(RuntimeBackend.Network.TESTNET, mainnetPeer = "node.mainnet.example")

        assertNull(target.activePeer)
    }

    @Test
    fun mainnetNeverUsesTheTestnetPeer() {
        val target = target(RuntimeBackend.Network.MAINNET, testnetPeer = "node.testnet.example:47218")

        assertNull(target.activePeer)
    }

    @Test
    fun eachNetworkPicksOnlyItsOwnSavedPeer() {
        val bothSaved = target(
            RuntimeBackend.Network.TESTNET,
            testnetPeer = "testnet.example:47218",
            mainnetPeer = "mainnet.example"
        )

        assertEquals("testnet.example:47218", bothSaved.activePeer)
        assertEquals(
            "mainnet.example",
            bothSaved.copy(network = RuntimeBackend.Network.MAINNET).activePeer
        )
    }
}
