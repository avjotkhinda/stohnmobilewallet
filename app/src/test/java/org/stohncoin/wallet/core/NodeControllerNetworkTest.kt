package org.stohncoin.wallet.core

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.runtime.RuntimeBackend

@RunWith(RobolectricTestRunner::class)
class NodeControllerNetworkTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("stohn_wallet", Context.MODE_PRIVATE).edit().clear().commit()
        kotlinx.coroutines.runBlocking {
            NodeController.get(context).applyNetwork(RuntimeBackend.Network.MAINNET)
        }
    }

    private fun node(): NodeController = NodeController.get(context)

    private fun prefs(node: NodeController): android.content.SharedPreferences {
        val field = NodeController::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        return field.get(node) as android.content.SharedPreferences
    }

    private fun activeWalletField(): java.lang.reflect.Field {
        val field = NodeController::class.java.getDeclaredField("activeWalletName")
        field.isAccessible = true
        return field
    }

    @Test
    fun defaultNetworkIsMainnet() {
        assertEquals(RuntimeBackend.Network.MAINNET, NodeController.get(context).network)
    }

    @Test
    fun applyNetworkPersistsTestnetWhileStopped() {
        val node = node()
        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.TESTNET)
        }
        assertEquals(RuntimeBackend.Network.TESTNET, node.network)
        val prefs = prefs(node)
        assertEquals("TESTNET", prefs.getString("network", null))
    }

    @Test
    fun applyNetworkPersistsMainnetAfterSwitchBack() {
        val node = node()
        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.TESTNET)
            node.applyNetwork(RuntimeBackend.Network.MAINNET)
        }
        assertEquals(RuntimeBackend.Network.MAINNET, node.network)
        val prefs = prefs(node)
        assertEquals("MAINNET", prefs.getString("network", null))
    }

    @Test
    fun applyNetworkIsNoOpForSameNetwork() {
        val node = node()
        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.MAINNET)
        }
        assertEquals(RuntimeBackend.Network.MAINNET, node.network)
    }

    @Test
    fun applyNetworkUsesPerNetworkWalletName() {
        val node = node()
        val prefs = prefs(node)
        val activeField = activeWalletField()
        prefs.edit().putString("active_wallet_name_MAINNET", "main-wallet").commit()
        prefs.edit().putString("active_wallet_name_TESTNET", "test-wallet").commit()

        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.TESTNET)
        }
        assertEquals("test-wallet", activeField.get(node))

        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.MAINNET)
        }
        assertEquals("main-wallet", activeField.get(node))
    }

    @Test
    fun applyNetworkClearsWalletNameWhenNetworkHasNone() {
        val node = node()
        val prefs = prefs(node)
        val activeField = activeWalletField()
        prefs.edit().remove("active_wallet_name_TESTNET").commit()

        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.TESTNET)
        }
        assertEquals(null, activeField.get(node))
    }

    @Test
    fun testnetPeerPersistsAndClears() {
        val node = node()
        kotlinx.coroutines.runBlocking {
            node.setTestnetPeer("node.example.org:47218")
        }
        assertEquals("node.example.org:47218", node.testnetPeer())
        kotlinx.coroutines.runBlocking {
            node.setTestnetPeer("")
        }
        assertEquals(null, node.testnetPeer())
    }

    @Test
    fun mainnetPeerPersistsAndClears() {
        val node = node()
        kotlinx.coroutines.runBlocking {
            node.setMainnetPeer("main.example.org")
        }
        assertEquals("main.example.org", node.mainnetPeer())
        kotlinx.coroutines.runBlocking {
            node.setMainnetPeer(null)
        }
        assertEquals(null, node.mainnetPeer())
    }

    @Test
    fun restoreWalletFallsBackWhenSavedWalletMissing() {
        val node = node()
        val prefs = prefs(node)
        val activeField = activeWalletField()
        prefs.edit().putString("active_wallet_name_MAINNET", "missing-wallet").commit()
        activeField.set(node, "missing-wallet")

        val dataDir = File(context.cacheDir, "missing-wallet-test").apply { mkdirs() }
        val backend = object : RuntimeBackend {
            override val dataDir: File = dataDir
            override val rpcPort: Int = 0
            override fun corePath(file: File): String = file.absolutePath
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override suspend fun rpcCall(
                method: String,
                params: JSONArray,
                walletName: String?
            ): JSONObject = when (method) {
                "loadwallet" -> error("wallet not found")
                "listwalletdir" -> JSONObject().put("wallets", JSONArray())
                "createwallet" -> JSONObject()
                else -> JSONObject()
            }
            override suspend fun rpcArray(
                method: String,
                params: JSONArray,
                walletName: String?
            ): JSONArray = when (method) {
                "listwallets" -> JSONArray()
                else -> JSONArray()
            }
            override fun runWalletTool(
                workspace: File,
                walletName: String,
                command: String,
                dumpFile: File?,
                walletFormat: String?
            ): RuntimeBackend.ToolResult = RuntimeBackend.ToolResult(0, "", "")
        }

        kotlinx.coroutines.runBlocking {
            node.restoreWallet(backend)
        }
        assertEquals("stohn-wallet", activeField.get(node))
    }

    @Test
    fun aSavedPeerThatNoLongerValidatesIsReportedForTheScreen() {
        val node = node()
        kotlinx.coroutines.runBlocking { node.applyNetwork(RuntimeBackend.Network.TESTNET) }
        // Written by an older build, when an underscore in a host still passed validation.
        prefs(node).edit().putString("testnet_peer", "node_bad.example:47218").commit()

        assertEquals("node_bad.example:47218", node.skippedPeer())
    }

    @Test
    fun aSavedPeerThatStillValidatesIsNotReportedAsSkipped() {
        val node = node()
        kotlinx.coroutines.runBlocking { node.applyNetwork(RuntimeBackend.Network.TESTNET) }
        prefs(node).edit().putString("testnet_peer", "node.example.org").commit()

        assertNull(node.skippedPeer())
    }

    @Test
    fun noSavedPeerMeansNothingIsReportedAsSkipped() {
        assertNull(node().skippedPeer())
    }
}
