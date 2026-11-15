package org.stohncoin.wallet.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    @Test
    fun defaultNetworkIsMainnet() {
        assertEquals(RuntimeBackend.Network.MAINNET, NodeController.get(context).network)
    }

    @Test
    fun applyNetworkPersistsTestnetWhileStopped() {
        val node = NodeController.get(context)
        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.TESTNET)
        }
        assertEquals(RuntimeBackend.Network.TESTNET, node.network)
        val prefsField = NodeController::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        val prefs = prefsField.get(node) as android.content.SharedPreferences
        assertEquals("TESTNET", prefs.getString("network", null))
    }

    @Test
    fun applyNetworkPersistsMainnetAfterSwitchBack() {
        val node = NodeController.get(context)
        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.TESTNET)
            node.applyNetwork(RuntimeBackend.Network.MAINNET)
        }
        assertEquals(RuntimeBackend.Network.MAINNET, node.network)
        val prefsField = NodeController::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        val prefs = prefsField.get(node) as android.content.SharedPreferences
        assertEquals("MAINNET", prefs.getString("network", null))
    }

    @Test
    fun applyNetworkIsNoOpForSameNetwork() {
        val node = NodeController.get(context)
        kotlinx.coroutines.runBlocking {
            node.applyNetwork(RuntimeBackend.Network.MAINNET)
        }
        assertEquals(RuntimeBackend.Network.MAINNET, node.network)
    }
}
