package org.stohncoin.wallet.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.core.NodeStorage
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * The closing half of the storage and network dialogs. Opening them was already asserted through
 * [NodeStatusDialogs]; these drive the same option rows and Cancel button the alert dialog shows, so
 * a tap that leaves [NodeStatusDialogs.open] set would let the dialog sit over the screen.
 *
 * [StorageModeChoices], [NetworkModeChoices] and [DialogCancelButton] are rendered directly instead
 * of through their [androidx.compose.material3.AlertDialog], because that window is a separate
 * composition Robolectric does not surface to the test rule.
 */
@RunWith(RobolectricTestRunner::class)
class ModeDialogCloseTest {

    @get:Rule
    val compose = createComposeRule()

    private val dialogs = NodeStatusDialogs()
    private val storageChoices = mutableListOf<NodeStorage.Mode>()
    private val networkChoices = mutableListOf<RuntimeBackend.Network>()

    private fun renderStorage(current: NodeStorage.Mode) {
        compose.setContent {
            Column {
                StorageModeChoices(current, dialogs) { storageChoices += it }
                DialogCancelButton(dialogs)
            }
        }
    }

    private fun renderNetwork(current: RuntimeBackend.Network) {
        compose.setContent {
            Column {
                NetworkModeChoices(current, dialogs) { networkChoices += it }
                DialogCancelButton(dialogs)
            }
        }
    }

    @Test
    fun choosingADifferentStorageModeClosesTheDialogAndAppliesIt() {
        dialogs.openStorage()
        renderStorage(current = NodeStorage.Mode.FULL)

        compose.onNodeWithText(NodeStorage.Mode.MINIMAL.label).performClick()

        assertNull("choosing a mode closes the dialog", dialogs.open)
        assertEquals(listOf(NodeStorage.Mode.MINIMAL), storageChoices)
    }

    @Test
    fun choosingTheStorageModeAlreadyInUseClosesWithoutApplyingIt() {
        dialogs.openStorage()
        renderStorage(current = NodeStorage.Mode.MINIMAL)

        compose.onNodeWithText(NodeStorage.Mode.MINIMAL.label).performClick()

        assertNull("a no-op choice still closes the dialog", dialogs.open)
        assertTrue("nothing to change, so nothing is applied", storageChoices.isEmpty())
    }

    @Test
    fun cancellingTheStorageDialogClosesItWithoutApplyingAnything() {
        dialogs.openStorage()
        renderStorage(current = NodeStorage.Mode.FULL)

        compose.onNodeWithText("Cancel").performClick()

        assertNull("Cancel closes the dialog", dialogs.open)
        assertTrue(storageChoices.isEmpty())
    }

    @Test
    fun choosingADifferentNetworkClosesTheDialogAndAppliesIt() {
        dialogs.openNetwork()
        renderNetwork(current = RuntimeBackend.Network.TESTNET)

        compose.onNodeWithText(RuntimeBackend.Network.MAINNET.label).performClick()

        assertNull("choosing a network closes the dialog", dialogs.open)
        assertEquals(listOf(RuntimeBackend.Network.MAINNET), networkChoices)
    }

    @Test
    fun choosingTheNetworkAlreadyInUseClosesWithoutApplyingIt() {
        dialogs.openNetwork()
        renderNetwork(current = RuntimeBackend.Network.MAINNET)

        compose.onNodeWithText(RuntimeBackend.Network.MAINNET.label).performClick()

        assertNull("a no-op choice still closes the dialog", dialogs.open)
        assertTrue("nothing to change, so nothing is applied", networkChoices.isEmpty())
    }

    @Test
    fun cancellingTheNetworkDialogClosesItWithoutApplyingAnything() {
        dialogs.openNetwork()
        renderNetwork(current = RuntimeBackend.Network.TESTNET)

        compose.onNodeWithText("Cancel").performClick()

        assertNull("Cancel closes the dialog", dialogs.open)
        assertTrue(networkChoices.isEmpty())
    }
}
