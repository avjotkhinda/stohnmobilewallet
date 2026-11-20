package org.stohncoin.wallet.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.core.NodeController

/**
 * The Settings row is one of two ways into Node Status, and the only place the node's state is
 * visible without opening the screen. An ignored custom peer has to be named here too, or the
 * explanation stays behind the drill-in the warning exists to save.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsNodeStatusWarningTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state() = NodeController.State(
        status = NodeController.Status.SYNCING,
        peers = 0,
        message = "Synchronizing Stohn blockchain…"
    )

    private fun render(skippedPeer: String?, onNode: () -> Unit = {}) {
        compose.setContent {
            SettingsPage(
                state = state(),
                info = null,
                onSecurity = {},
                onImport = {},
                onLock = {},
                onNode = onNode,
                onWallets = {},
                onAddressBook = {},
                skippedPeer = skippedPeer
            )
        }
    }

    @Test
    fun theSettingsEntryNamesTheIgnoredPeerBeforeTheUserOpensNodeStatus() {
        render(skippedPeer = "node_bad.example:47218")

        compose.onNodeWithText("Saved peer node_bad.example:47218 is not a valid address, so Core started without it.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theWarnedSettingsEntryStillOpensNodeStatus() {
        // The warning is added text inside the same card, not a second target: it must not swallow the
        // tap or split the row into two destinations.
        var opened = 0
        render(skippedPeer = "node_bad.example:47218", onNode = { opened++ })

        compose.onNodeWithText("Saved peer node_bad.example:47218 is not a valid address", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Node Status", substring = true).performScrollTo().performClick()

        assertEquals("the warned row must still be the way into Node Status", 1, opened)
    }

    @Test
    fun theSettingsEntrySaysNothingWhenNoPeerWasSkipped() {
        render(skippedPeer = null)

        compose.onNodeWithText("Node Status", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("is not a valid address", substring = true).assertDoesNotExist()
    }
}
