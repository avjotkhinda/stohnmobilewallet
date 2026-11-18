package org.stohncoin.wallet.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * The custom-peer dialog's content as a user drives it: the effective address it promises for the
 * active network, and the refusal of a host the current validation rejects.
 *
 * [CustomPeerDialogBody] and [CustomPeerDialogSaveButton] are rendered directly instead of through
 * [CustomPeerDialog], so these assertions never depend on Robolectric discovering an AlertDialog's
 * separate window.
 */
@RunWith(RobolectricTestRunner::class)
class CustomPeerDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var saves = 0
    private var saved: String? = null

    private fun render(current: String? = null) {
        compose.setContent {
            val state = remember { CustomPeerDialogState(current) }
            Column {
                CustomPeerDialogBody(network = RuntimeBackend.Network.TESTNET, state = state)
                CustomPeerDialogSaveButton(
                    network = RuntimeBackend.Network.TESTNET,
                    state = state,
                    onSave = { peer ->
                        saves++
                        saved = peer
                    }
                )
            }
        }
    }

    @Test
    fun explicitPortIsShownAsTheEffectiveAddress() {
        render(current = "1.2.3.4:37218")

        compose.onNodeWithText("Effective address: 1.2.3.4:37218").assertExists()
    }

    @Test
    fun defaultPortIsSpelledOutForTheActiveNetwork() {
        render(current = "node.example.org")

        compose.onNodeWithText("Effective address: node.example.org:47218 (default Testnet port)").assertExists()
    }

    @Test
    fun blankFieldExplainsThatSavingClearsThePeer() {
        render(current = null)

        compose.onNodeWithText("Effective address: none. Saving an empty field clears this peer.").assertExists()
    }

    @Test
    fun invalidHostShowsAnErrorAndDoesNotSave() {
        render(current = null)

        compose.onNode(hasSetTextAction()).performTextInput("node_bad.example")
        compose.onNodeWithText("Effective address: enter a valid host or host:port.").assertExists()

        compose.onNodeWithText("Save").performClick()

        compose.onNodeWithText("Peer host is invalid").assertExists()
        assertEquals(0, saves)
        assertNull(saved)
    }

    @Test
    fun theFieldStartsFromTheSavedValueSoTheUserCanFixIt() {
        render(current = "node_bad.example:47218")

        // Opening the dialog from the Node Status warning pre-fills the value that needs repair.
        compose.onNodeWithText("node_bad.example:47218").assertExists()
        compose.onNodeWithText("Effective address: enter a valid host or host:port.").assertExists()
    }

    @Test
    fun validHostIsNormalizedBeforeItIsSaved() {
        render(current = null)

        compose.onNode(hasSetTextAction()).performTextInput(" 1.2.3.4:37218 ")
        compose.onNodeWithText("Save").performClick()

        assertEquals(1, saves)
        assertEquals("1.2.3.4:37218", saved)
    }
}
