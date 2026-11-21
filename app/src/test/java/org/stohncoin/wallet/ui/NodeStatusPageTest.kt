package org.stohncoin.wallet.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
                    // The stored peer belongs to the network on screen, as it would in the wallet: on
                    // mainnet a testnet value would leave the dialog with nothing to fix.
                    testnetPeer = if (network == RuntimeBackend.Network.TESTNET) savedPeer else null,
                    mainnetPeer = if (network == RuntimeBackend.Network.MAINNET) savedPeer else null,
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
        // The note is also the only place that points at the dialog's reachability check; a copy
        // edit dropping that pointer would leave the user nothing to test a replacement with.
        compose.onNodeWithText("Test reachability", substring = true).assertExists()
        // The Custom peer line carries the same verdict as the warning below it.
        compose.onNodeWithText("node_bad.example:47218 (ignored)").assertExists()
        // One way to replace the value — the row's own button, renamed while it is skipped — and
        // one distinct way out, dropping it. No duplicate button in the warning.
        compose.onAllNodesWithText("Fix custom peer").assertCountEquals(1)
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
    fun aStoppedNodeDoesNotAccuseThePeerItWillSkip() {
        // Core is down, so nothing is being started without the peer right now: a record left from
        // the last run must not keep the warning on a node that is not skipping anything.
        render(
            peers = { 0 },
            savedPeer = "node_bad.example:47218",
            skippedPeer = "node_bad.example:47218",
            running = false
        )

        compose.onNodeWithText("node_bad.example:47218").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("(ignored)", substring = true).assertCountEquals(0)
        compose.onNodeWithText("is not a valid address", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("Clear saved peer").assertCountEquals(0)
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
        compose.onAllNodesWithText("Fix custom peer").assertCountEquals(0)
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
    fun fixingTheSkippedPeerOpensTheDialogHoldingWhatIsSavedNow() {
        val dialogs = NodeStatusDialogs()
        // The saved value differs from the one this run started without, so the assertions below
        // can tell which of the two seeded the dialog.
        render(
            peers = { 0 },
            savedPeer = "node_bad.example:47218",
            skippedPeer = "old_bad.example:47218",
            dialogs = dialogs
        )
        assertNull("no dialog is open before the tap", dialogs.open)
        // One control changes the peer; the warning must not add a second button that opens this
        // same dialog from the recorded value instead.
        compose.onAllNodesWithText("Fix custom peer").assertCountEquals(1)

        compose.onNodeWithText("Fix custom peer").performScrollTo().performClick()

        // CustomPeerDialogTest renders the body from this seed, so the field arrives pre-filled.
        assertEquals(
            "Fix custom peer must open the custom peer dialog",
            NodeStatusDialogs.Open.PEER,
            dialogs.open
        )
        assertEquals(
            "the dialog must start from the stored peer it can fix, not the run's record",
            "node_bad.example:47218",
            dialogs.peerSeed
        )
    }

    @Test
    fun theSkippedPeerFixOpensTheDialogHoldingThePeerCoreSkipped() {
        // The ordinary case, and the one the button is named for: what is stored and what the run
        // skipped are the same value. The test above uses differing values to prove which of the
        // two seeds the field; this one pins the value a user actually meets, next to the notice.
        val dialogs = NodeStatusDialogs()
        render(
            peers = { 0 },
            savedPeer = "node_bad.example:47218",
            skippedPeer = "node_bad.example:47218",
            dialogs = dialogs
        )

        // The notice is up, so the tap below happens in the state the button exists for.
        compose.onNodeWithText("is not a valid address", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Fix custom peer").assertCountEquals(1)

        // Certified through the dialogs holder rather than a rendered dialog window: the field's
        // pre-fill comes from this seed (see CustomPeerDialogTest), not from anything the window does.
        compose.onNodeWithText("Fix custom peer").performScrollTo().performClick()

        assertEquals(
            "Fix custom peer must open the custom peer dialog",
            NodeStatusDialogs.Open.PEER,
            dialogs.open
        )
        assertEquals(
            "the dialog must hold the peer Core is skipping, ready to be fixed",
            "node_bad.example:47218",
            dialogs.peerSeed
        )
    }

    @Test
    fun savingTheRepairedPeerFromTheFixDialogWritesItBackToTheTestnetPeer() {
        val testnet = mutableListOf<String?>()
        val mainnet = mutableListOf<String?>()
        val dialogs = NodeStatusDialogs()
        render(
            peers = { 0 },
            network = RuntimeBackend.Network.TESTNET,
            savedPeer = "node_bad.example:47218",
            skippedPeer = "node_bad.example:47218",
            onTestnetPeer = { testnet += it },
            onMainnetPeer = { mainnet += it },
            dialogs = dialogs
        )

        // The whole repair a user performs: open the dialog from the warning's button, replace the
        // unusable address, save.
        compose.onNodeWithText("Fix custom peer").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(" node.example.org:47218 ")
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            "the repaired address must be saved as the testnet peer, trimmed as the dialog normalizes it",
            listOf<String?>("node.example.org:47218"),
            testnet
        )
        assertEquals("a testnet save must not touch the mainnet peer", emptyList<String?>(), mainnet)
        assertNull("saving closes the dialog it was opened from", dialogs.open)
    }

    @Test
    fun savingTheRepairedPeerFromTheFixDialogWritesItBackToTheMainnetPeer() {
        val testnet = mutableListOf<String?>()
        val mainnet = mutableListOf<String?>()
        val dialogs = NodeStatusDialogs()
        render(
            peers = { 0 },
            network = RuntimeBackend.Network.MAINNET,
            savedPeer = "node_bad.example:37218",
            skippedPeer = "node_bad.example:37218",
            onTestnetPeer = { testnet += it },
            onMainnetPeer = { mainnet += it },
            dialogs = dialogs
        )

        compose.onNodeWithText("Fix custom peer").performScrollTo().performClick()
        // No port: the mainnet default applies, and what is stored is the bare host.
        compose.onNode(hasSetTextAction()).performTextReplacement("node.example.org")
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            "the repaired address must be saved as the mainnet peer, without the default port spelled out",
            listOf<String?>("node.example.org"),
            mainnet
        )
        assertEquals("a mainnet save must not touch the testnet peer", emptyList<String?>(), testnet)
        assertNull("saving closes the dialog it was opened from", dialogs.open)
    }

    @Test
    fun onlyOneControlOpensTheCustomPeerDialogWhenAPeerIsSkipped() {
        // Guard, not a description: the warning is the tempting place to add a second button that
        // opens the peer dialog, which would split the user's choice and could seed it differently.
        // Exactly one label-any-state opens it, and the neighbouring clear action is not a second way in.
        val dialogs = NodeStatusDialogs()
        val cleared = mutableListOf<String?>()
        render(
            peers = { 0 },
            savedPeer = "node_bad.example:47218",
            skippedPeer = "node_bad.example:47218",
            onTestnetPeer = { cleared += it },
            dialogs = dialogs
        )

        val setters = compose.onAllNodesWithText("Set custom peer", substring = false)
            .fetchSemanticsNodes().size
        val fixers = compose.onAllNodesWithText("Fix custom peer", substring = false)
            .fetchSemanticsNodes().size
        assertEquals("exactly one control may open the custom peer dialog", 1, setters + fixers)

        // The one other peer control clears the value; if it ever started opening the dialog, this
        // would see an open dialog and the count above would no longer be the whole story.
        compose.onNodeWithText("Clear saved peer").performScrollTo().performClick()
        assertNull("only the one control may open the custom peer dialog", dialogs.open)
        assertEquals("clearing must still just clear", listOf<String?>(null), cleared)
    }

    @Test
    fun settingAHealthyPeerOpensTheDialogHoldingThatValue() {
        // The same button under its ordinary label: nothing is skipped, so this is an edit, not a
        // repair, but the dialog must still start from what is stored instead of an empty field.
        val dialogs = NodeStatusDialogs()
        render(peers = { 1 }, savedPeer = "node.example.org:47218", dialogs = dialogs)

        compose.onAllNodesWithText("Fix custom peer").assertCountEquals(0)
        compose.onNodeWithText("Set custom peer").performScrollTo().performClick()

        assertEquals(
            "Set custom peer must open the custom peer dialog",
            NodeStatusDialogs.Open.PEER,
            dialogs.open
        )
        assertEquals(
            "the dialog must hold the peer Core is using, ready to be edited",
            "node.example.org:47218",
            dialogs.peerSeed
        )
    }

    @Test
    fun clearingTheFieldInTheFixDialogClearsTheTestnetPeer() {
        val testnet = mutableListOf<String?>()
        val mainnet = mutableListOf<String?>()
        val dialogs = NodeStatusDialogs()
        render(
            peers = { 0 },
            network = RuntimeBackend.Network.TESTNET,
            savedPeer = "node_bad.example:47218",
            skippedPeer = "node_bad.example:47218",
            onTestnetPeer = { testnet += it },
            onMainnetPeer = { mainnet += it },
            dialogs = dialogs
        )

        // Replacing the value and emptying it are the same confirmation: a blank field saves none.
        compose.onNodeWithText("Fix custom peer").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("Effective address: none", substring = true).assertExists()
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            "an empty field must save no testnet peer, clearing the stored one",
            listOf<String?>(null),
            testnet
        )
        assertEquals("a testnet clear must not touch the mainnet peer", emptyList<String?>(), mainnet)
        assertNull("saving closes the dialog it was opened from", dialogs.open)
    }

    @Test
    fun clearingTheFieldInTheFixDialogClearsTheMainnetPeer() {
        val testnet = mutableListOf<String?>()
        val mainnet = mutableListOf<String?>()
        val dialogs = NodeStatusDialogs()
        render(
            peers = { 0 },
            network = RuntimeBackend.Network.MAINNET,
            savedPeer = "node_bad.example:37218",
            skippedPeer = "node_bad.example:37218",
            onTestnetPeer = { testnet += it },
            onMainnetPeer = { mainnet += it },
            dialogs = dialogs
        )

        // Whitespace only is still an empty field: the dialog normalizes before it decides.
        compose.onNodeWithText("Fix custom peer").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("   ")
        compose.onNodeWithText("Effective address: none", substring = true).assertExists()
        compose.onNodeWithText("Save").performClick()

        assertEquals(
            "an empty field must save no mainnet peer, clearing the stored one",
            listOf<String?>(null),
            mainnet
        )
        assertEquals("a mainnet clear must not touch the testnet peer", emptyList<String?>(), testnet)
        assertNull("saving closes the dialog it was opened from", dialogs.open)
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
