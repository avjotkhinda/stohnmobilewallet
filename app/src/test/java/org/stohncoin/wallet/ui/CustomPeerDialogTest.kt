package org.stohncoin.wallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * The custom-peer dialog as a user drives it: the effective address it promises for the active
 * network, and the refusal of a host the current validation rejects.
 *
 * The effective-address line is the only place the default port is visible before saving, and the
 * refusal path is the one that must never reach [onSave] — neither is observable from a unit test
 * of [org.stohncoin.wallet.runtime.TestnetPeer], which has no dialog state.
 */
@RunWith(RobolectricTestRunner::class)
class CustomPeerDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var saves = 0
    private var saved: String? = null

    private fun render(current: String?) {
        compose.setContent {
            CustomPeerDialog(
                network = RuntimeBackend.Network.TESTNET,
                current = current,
                onDismiss = {},
                onSave = { peer ->
                    saves++
                    saved = peer
                }
            )
        }
    }

    @Test
    fun explicitPortIsShownAsTheEffectiveAddress() {
        render(current = "1.2.3.4:37218")

        compose.onNodeWithText("Effective address: 1.2.3.4:37218").assertIsDisplayed()
    }

    @Test
    fun defaultPortIsSpelledOutForTheActiveNetwork() {
        render(current = "node.example.org")

        compose.onNodeWithText("Effective address: node.example.org:47218 (default Testnet port)").assertIsDisplayed()
    }

    @Test
    fun blankFieldExplainsThatSavingClearsThePeer() {
        render(current = null)

        compose.onNodeWithText("Effective address: none. Saving an empty field clears this peer.").assertIsDisplayed()
    }

    @Test
    fun invalidHostShowsAnErrorAndDoesNotSave() {
        render(current = null)

        compose.onNode(hasSetTextAction()).performTextInput("node_bad.example")
        compose.onNodeWithText("Effective address: enter a valid host or host:port.").assertIsDisplayed()

        compose.onNodeWithText("Save").performClick()

        compose.onNodeWithText("Peer host is invalid").assertIsDisplayed()
        assertEquals(0, saves)
        assertNull(saved)
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
