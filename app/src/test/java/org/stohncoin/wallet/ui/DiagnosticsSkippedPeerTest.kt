package org.stohncoin.wallet.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * Node Diagnostics is reached straight from Settings and from the log itself, so an ignored custom
 * peer has to be named here too - otherwise a 0-peer node is only explained after a detour through
 * Node Status, which is the screen this page is used to avoid. The warning follows the same gate as
 * Node Status's ignored peer: only a running Core is being started without the peer now.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsSkippedPeerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDiagnosticsPageNamesThePeerCoreSkipped() {
        compose.setContent {
            DiagnosticsPage(
                network = RuntimeBackend.Network.TESTNET,
                skippedPeer = "node_bad.example:47218",
                running = true
            )
        }

        compose.onNodeWithText("Saved peer node_bad.example:47218 is not a valid address, so Core started without it.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theDiagnosticsPageSaysNothingWhenNoPeerWasSkipped() {
        compose.setContent {
            DiagnosticsPage(network = RuntimeBackend.Network.TESTNET, running = true)
        }

        compose.onNodeWithText("Node diagnostics").assertExists()
        compose.onNodeWithText("is not a valid address", substring = true).assertDoesNotExist()
    }

    @Test
    fun aStoppedNodeDoesNotAccuseThePeerItWillSkip() {
        compose.setContent {
            DiagnosticsPage(
                network = RuntimeBackend.Network.TESTNET,
                skippedPeer = "node_bad.example:47218",
                running = false
            )
        }

        // The page still renders; only the leftover record from the previous run is withheld.
        compose.onNodeWithText("Node diagnostics").assertExists()
        compose.onNodeWithText("is not a valid address", substring = true).assertDoesNotExist()
        compose.onNodeWithText("node_bad.example:47218", substring = true).assertDoesNotExist()
    }
}
