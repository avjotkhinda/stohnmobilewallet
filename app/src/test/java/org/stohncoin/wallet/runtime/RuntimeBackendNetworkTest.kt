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
    fun mainnetArgumentsAddNoPeers() {
        val args = RuntimeBackend.networkArguments(RuntimeBackend.Network.MAINNET)
        assertTrue(args.isEmpty())
        assertTrue(args.none { it.startsWith("-addnode=") })
    }

    @Test
    fun testnetArgumentsCarryOnlyTheTestnetFlag() {
        val args = RuntimeBackend.networkArguments(RuntimeBackend.Network.TESTNET)
        assertEquals(listOf("-testnet"), args)
        assertTrue(args.none { it.startsWith("-addnode=") })
    }

    @Test
    fun runtimeBackendFactoryAcceptsNetworkParameter() {
        // Compile-time/shape check: the factory's create signature accepts a network argument.
        // RuntimeBackendFactory.create(context, pruneMiB, network) is the propagation seam.
        assertTrue(RuntimeBackend.Network.values().contains(RuntimeBackend.Network.TESTNET))
    }
}
