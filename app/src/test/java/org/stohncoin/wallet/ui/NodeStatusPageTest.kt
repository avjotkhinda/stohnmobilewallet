package org.stohncoin.wallet.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.core.NodeStorage
import org.stohncoin.wallet.runtime.RuntimeBackend
import org.stohncoin.wallet.wallet.WalletRpc

/**
 * The Node Status surfaces added for diagnosing 0-peer nodes: the connected-peers card fed by the
 * `loadPeers` seam, and the inline connection-test result.
 *
 * The card is the only place a stalled sync turns into a named connection, and the test summary
 * only describes the zero-peer episode it was run in — neither is visible from a unit test of
 * [org.stohncoin.wallet.core.SeedConnectionSelfTest] or of [WalletRpc.peerInfo].
 */
@RunWith(RobolectricTestRunner::class)
class NodeStatusPageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(peers: Int, status: NodeController.Status = NodeController.Status.SYNCING) = NodeController.State(
        status = status,
        peers = peers,
        message = "Synchronizing Stohn blockchain…"
    )

    /**
     * Renders the screen as it looks once Core has been up past the hint delay, so the zero-peer
     * block appears on the first frame and the tests need no clock control.
     */
    private fun render(
        peers: () -> Int,
        loadPeers: suspend () -> List<WalletRpc.PeerInfo> = { emptyList() },
        network: RuntimeBackend.Network = RuntimeBackend.Network.TESTNET,
        savedPeer: String? = null,
        skippedPeer: String? = null,
        running: Boolean = true,
        onTestnetPeer: (String?) -> Unit = {},
        onMainnetPeer: (String?) -> Unit = {},
        dialogs: NodeStatusDialogs = NodeStatusDialogs()
    ) {
        compose.setContent {
            NodeStatusPage(
                state = state(peers(), if (running) NodeController.Status.SYNCING else NodeController.Status.STOPPED),
                runningForMillis = ZeroPeerHint.DELAY_MILLIS,
                target = NodeStatusTarget(
                    network = network,
                    pruneMode = NodeStorage.Mode.FULL,
                    testnetPeer = savedPeer,
                    skippedPeer = skippedPeer
                ),
                storage = NodeStatusStorage(),
                dialogs = dialogs,
                actions = NodeStatusActions(
                    onStart = {},
                    onStop = {},
                    onTestnetPeer = onTestnetPeer,
                    onMainnetPeer = onMainnetPeer,
                    loadPeers = loadPeers
                )
            )
        }
    }

    @Test
    fun peersCardExplainsAnEmptyPeerList() {
        render(peers = { 0 })

        compose.onNodeWithText("No peers connected yet", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("is not a valid address", substring = true).assertDoesNotExist()
    }

    @Test
    fun aSavedPeerThatNoLongerValidatesIsExplainedOnTheCustomPeerLine() {
        render(peers = { 0 }, savedPeer = "node_bad.example:47218", skippedPeer = "node_bad.example:47218")

        compose.onNodeWithText("Saved peer node_bad.example:47218 is not a valid address", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("it is ignored and Stohn Core starts without it", substring = true).assertExists()
        // The Custom peer line carries the same verdict as the warning below it.
        compose.onNodeWithText("node_bad.example:47218 (ignored)").assertExists()
        // One way to replace the value — the row's own button, renamed while it is skipped — and
        // one distinct way out, dropping it. No duplicate button in the warning.
        compose.onAllNodesWithText("Fix saved peer").assertCountEquals(1)
        compose.onAllNodesWithText("Set custom peer").assertCountEquals(0)
        compose.onNodeWithText("Clear saved peer").assertExists()
    }

    @Test
    fun aSavedPeerWithNoRunRecordIsNotCalledIgnored() {
        // A stopped node has run nothing, so the controller recorded no skip: the stored config on
        // its own must not turn into a verdict about what Core started without.
        render(peers = { 0 }, savedPeer = "node_bad.example:47218", running = false)

        compose.onNodeWithText("node_bad.example:47218").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("(ignored)", substring = true).assertCountEquals(0)
        compose.onNodeWithText("is not a valid address", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Set custom peer").assertExists()
    }

    @Test
    fun theZeroPeerHintDoesNotBlameAPeerCoreSkipped() {
        render(peers = { 0 }, savedPeer = "node_bad.example:47218", skippedPeer = "node_bad.example:47218")

        compose.onNodeWithText("the saved peer above is not in use", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithText("The custom peer may be unreachable", substring = true).assertCountEquals(0)
    }

    @Test
    fun theZeroPeerHintBlamesACustomPeerCoreActuallyUses() {
        render(peers = { 0 }, savedPeer = "node.example.org")

        // Nothing is skipped, so the row keeps its ordinary label.
        compose.onNodeWithText("Set custom peer").assertExists()
        compose.onAllNodesWithText("Fix saved peer").assertCountEquals(0)
        compose.onNodeWithText("The custom peer may be unreachable", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithText("the saved peer above is not in use", substring = true).assertCountEquals(0)
    }

    @Test
    fun theSkippedPeerWarningOffersToClearTheSavedPeer() {
        val cleared = mutableListOf<String?>()
        render(
            peers = { 0 },
            savedPeer = "node_bad.example:47218",
            skippedPeer = "node_bad.example:47218",
            onTestnetPeer = { cleared += it }
        )

        compose.onNodeWithText("Clear saved peer").performScrollTo().performClick()

        assertEquals(listOf<String?>(null), cleared)
    }

    @Test
    fun fixingTheSkippedPeerOpensTheDialogHoldingThatValue() {
        val dialogs = NodeStatusDialogs()
        render(
            peers = { 0 },
            savedPeer = "node_bad.example:47218",
            skippedPeer = "node_bad.example:47218",
            dialogs = dialogs
        )
        assertNull("no dialog is mounted before the tap", dialogs.open)

        compose.onNodeWithText("Fix saved peer").performScrollTo().performClick()

        // CustomPeerDialogTest renders the body from this seed, so the field arrives pre-filled.
        assertEquals(
            "Fix saved peer must open the custom peer dialog",
            NodeStatusDialogs.Open.PEER,
            dialogs.open
        )
        assertEquals("node_bad.example:47218", dialogs.peerSeed)
    }

    @Test
    fun changingTheNetworkOpensTheNetworkDialog() {
        val dialogs = NodeStatusDialogs()
        render(peers = { 1 }, dialogs = dialogs)

        compose.onNodeWithText("Change network").performScrollTo().performClick()

        assertEquals(NodeStatusDialogs.Open.NETWORK, dialogs.open)
    }

    @Test
    fun changingTheStorageModeOpensTheStorageDialog() {
        val dialogs = NodeStatusDialogs()
        render(peers = { 1 }, dialogs = dialogs)

        compose.onNodeWithText("Change storage mode").performScrollTo().performClick()

        assertEquals(NodeStatusDialogs.Open.STORAGE, dialogs.open)
    }

    @Test
    fun fixingTheSkippedPeerOpensTheDialogPreFilledWithIt() {
        render(peers = { 0 }, savedPeer = "node_bad.example:47218", skippedPeer = "node_bad.example:47218")
        // The warning's own copy does not put the raw address in a text field, so any match after
        // the tap is the dialog's field.
        compose.onAllNodesWithText("node_bad.example:47218").assertCountEquals(0)

        compose.onNodeWithText("Fix saved peer").performScrollTo().performClick()

        // The whole route the button promises: mounted dialog, seeded field, same peer being fixed.
        compose.onNodeWithText("node_bad.example:47218").assertExists()
        compose.onNodeWithText("Effective address: enter a valid host or host:port.").assertExists()
    }

    @Test
    fun theSkippedPeerWarningClearsTheSavedMainnetPeer() {
        val cleared = mutableListOf<String?>()
        render(
            peers = { 0 },
            network = RuntimeBackend.Network.MAINNET,
            savedPeer = "node_bad.example:47218",
            skippedPeer = "node_bad.example:47218",
            onMainnetPeer = { cleared += it }
        )

        compose.onNodeWithText("Clear saved peer").performScrollTo().performClick()

        assertEquals(listOf<String?>(null), cleared)
    }

    @Test
    fun peersCardRendersOneConnectionWithItsDirectionAndDetails() {
        val peer = WalletRpc.PeerInfo(
            address = "203.0.113.9:37218",
            inbound = false,
            pingMillis = 42,
            startingHeight = 880_000,
            syncedHeaders = 900_000,
            syncedBlocks = 899_000
        )
        render(peers = { 1 }, loadPeers = { listOf(peer) })

        compose.onNodeWithText("203.0.113.9:37218").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OUT").assertExists()
        compose.onNodeWithText("42 ms · headers 900000 · started at 880000").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun connectionTestSummaryIsDroppedWhenThePeerCountChanges() {
        var peers by mutableIntStateOf(0)
        render(peers = { peers })

        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        // Testnet has no bundled seeds, so the probe reports that without touching the network.
        val summary = "no bundled seeds to test"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(summary, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(summary, substring = true).performScrollTo().assertIsDisplayed()

        peers = 1
        compose.waitForIdle()
        compose.onAllNodesWithText(summary, substring = true).assertCountEquals(0)

        peers = 0
        compose.waitForIdle()
        // The zero-peer block is back, but the result from the previous episode must not be.
        compose.onNodeWithText("Still at 0 peers", substring = true).assertExists()
        compose.onAllNodesWithText(summary, substring = true).assertCountEquals(0)
    }
}
