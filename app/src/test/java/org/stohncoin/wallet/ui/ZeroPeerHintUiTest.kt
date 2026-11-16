package org.stohncoin.wallet.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.core.NodeController
import org.stohncoin.wallet.core.NodeStorage
import org.stohncoin.wallet.runtime.RuntimeBackend

@RunWith(RobolectricTestRunner::class)
class ZeroPeerHintUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(peers: Int) = NodeController.State(
        status = NodeController.Status.SYNCING,
        peers = peers,
        message = "Synchronizing Stohn blockchain…"
    )

    private fun render(peers: () -> Int, runningForMillis: Long? = null) {
        compose.setContent {
            NodeStatusPage(
                state = state(peers()),
                runningForMillis = runningForMillis,
                chainUsage = null,
                freeBytes = null,
                pruneMode = NodeStorage.Mode.FULL,
                network = RuntimeBackend.Network.MAINNET,
                onStart = {},
                onStop = {},
                onPruneMode = {}
            )
        }
    }

    @Test
    fun hintAppearsOnlyAfterTheDelay() {
        compose.mainClock.autoAdvance = false
        render(peers = { 0 })

        compose.mainClock.advanceTimeBy(ZeroPeerHint.DELAY_MILLIS - 1)
        compose.onNodeWithText("Still at 0 peers", substring = true).assertDoesNotExist()

        compose.mainClock.advanceTimeBy(2)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Still at 0 peers", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun hintHidesWhenPeersConnect() {
        var peers by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        render(peers = { peers })

        compose.mainClock.advanceTimeBy(ZeroPeerHint.DELAY_MILLIS + 1)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Still at 0 peers", substring = true).performScrollTo().assertIsDisplayed()

        peers = 1
        compose.waitForIdle()

        compose.onNodeWithText("Still at 0 peers", substring = true).assertDoesNotExist()
    }
}
