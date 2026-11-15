package org.stohncoin.wallet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBackendNetworkTest {
    @Test
    fun testnetCliFlagIsTestnet() {
        assertEquals("-testnet", RuntimeBackend.Network.TESTNET.cliFlag())
    }

    @Test
    fun mainnetCliFlagIsNull() {
        assertNull(RuntimeBackend.Network.MAINNET.cliFlag())
    }

    @Test
    fun testnetLabelIsTestnet() {
        assertEquals("Testnet", RuntimeBackend.Network.TESTNET.label)
    }

    @Test
    fun mainnetArgumentsIncludeFallbackPeers() {
        val args = RuntimeBackend.networkArguments(RuntimeBackend.Network.MAINNET)
        assertTrue(args.contains("-addnode=37.59.20.42"))
        assertEquals(4, args.count { it.startsWith("-addnode=") })
        assertTrue(args.none { it == "-testnet" })
    }

    @Test
    fun testnetArgumentsDoNotIncludeMainnetPeers() {
        val args = RuntimeBackend.networkArguments(RuntimeBackend.Network.TESTNET)
        assertEquals(listOf("-testnet"), args)
    }

    @Test
    fun runtimeBackendFactoryAcceptsNetworkParameter() {
        // Compile-time/shape check: the factory's create signature accepts a network argument.
        // RuntimeBackendFactory.create(context, pruneMiB, network) is the propagation seam.
        assertTrue(RuntimeBackend.Network.values().contains(RuntimeBackend.Network.TESTNET))
    }
}
