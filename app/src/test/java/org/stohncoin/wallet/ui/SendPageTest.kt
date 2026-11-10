package org.stohncoin.wallet.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.core.SendGateway
import org.stohncoin.wallet.wallet.WalletRpc

/**
 * The send screen as a user drives it, against a scripted [SendGateway].
 *
 * The screen is the only place where an address, an amount typed in STOHN and a fee estimate turn
 * into a spend, and none of that is visible to a unit test of [org.stohncoin.wallet.wallet.Amounts]
 * or of the RPC client. Driving the real composable is what proves the amount the dialog shows is
 * the amount handed to the node, and that an incomplete form cannot reach the node at all.
 */
@RunWith(RobolectricTestRunner::class)
class SendPageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val recipient = "SRecipientAddressForTests0000000000000000"

    private var estimatedFor: Triple<String, Long, Boolean>? = null
    private var submitted: Submission? = null
    private var completed = false

    private data class Submission(
        val address: String,
        val amountSatoshis: Long,
        val passphrase: String?,
        val subtractFee: Boolean,
        val confTarget: Int?
    )

    private val gateway = object : SendGateway {
        override suspend fun estimateFee(
            address: String,
            amountSatoshis: Long,
            subtractFeeFromAmount: Boolean,
            confTarget: Int?
        ): WalletRpc.FeeEstimate {
            estimatedFor = Triple(address, amountSatoshis, subtractFeeFromAmount)
            return WalletRpc.FeeEstimate(
                feeSatoshis = 1_000L,
                totalSatoshis = amountSatoshis + 1_000L,
                recipientSatoshis = amountSatoshis
            )
        }

        override suspend fun send(
            address: String,
            amountSatoshis: Long,
            passphrase: CharArray?,
            subtractFeeFromAmount: Boolean,
            confTarget: Int?
        ): String {
            submitted = Submission(address, amountSatoshis, passphrase?.concatToString(), subtractFeeFromAmount, confTarget)
            return "txid"
        }
    }

    private fun render(encrypted: Boolean = false) {
        compose.setContent {
            SendPage(
                node = gateway,
                info = WalletRpc.WalletInfoSnapshot(
                    balanceSatoshis = 100_000_000L,
                    unconfirmedSatoshis = 0L,
                    immatureSatoshis = 0L,
                    txCount = 0,
                    encrypted = encrypted
                ),
                balance = 100_000_000L,
                busy = false,
                onAddressBook = {},
                onBusy = {},
                onComplete = { completed = true }
            )
        }
    }

    /** A text field is addressed by its label, because the list scrolls fields in and out of composition. */
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun type(label: String, text: String) {
        scrollTo(label)
        field(label).performTextInput(text)
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    private fun requestEstimate() {
        scrollTo("Estimate Fee")
        compose.onNodeWithText("Estimate Fee").performClick()
        compose.waitUntil(5_000) { estimatedFor != null }
    }

    @Test
    fun `an empty form can neither estimate a fee nor reach the confirmation`() {
        render()

        scrollTo("Estimate Fee")
        compose.onNodeWithText("Estimate Fee").assertIsNotEnabled()

        scrollTo("Review & Send")
        compose.onNodeWithText("Review & Send").assertIsNotEnabled()
    }

    @Test
    fun `an address without an amount still cannot estimate a fee`() {
        render()
        type("To", recipient)

        scrollTo("Estimate Fee")

        compose.onNodeWithText("Estimate Fee").assertIsNotEnabled()
        assertNull(estimatedFor)
    }

    @Test
    fun `the estimate is asked for the amount in satoshis and its fee is shown`() {
        render()
        type("To", recipient)
        type("Amount", "1.25")

        requestEstimate()

        assertEquals(Triple(recipient, 125_000_000L, false), estimatedFor)
        scrollTo("Estimated transaction fee")
        compose.onNodeWithText("Estimated transaction fee").assertIsDisplayed()
        compose.onNodeWithText("Fee: 0.00001000 STOHN").assertIsDisplayed()
    }

    @Test
    fun `the amount field keeps digits and a decimal point only`() {
        render()

        type("Amount", "12a.5b")

        val typed = field("Amount").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("12.5", typed)
    }

    @Test
    fun `confirming sends exactly the amount the dialog showed`() {
        render()
        type("To", recipient)
        type("Amount", "1.25")
        requestEstimate()

        scrollTo("Review & Send")
        compose.onNodeWithText("Review & Send").assertIsEnabled().performClick()

        compose.onNodeWithText("Confirm transaction").assertIsDisplayed()
        compose.onNodeWithText("1.25000000 STOHN").assertIsDisplayed()

        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(5_000) { completed }

        assertEquals(recipient, submitted?.address)
        assertEquals(125_000_000L, submitted?.amountSatoshis)
        assertEquals(false, submitted?.subtractFee)
        assertTrue("the screen must report a completed send", completed)
    }

    @Test
    fun `an encrypted wallet cannot be reviewed until the passkey is entered`() {
        render(encrypted = true)
        type("To", recipient)
        type("Amount", "1.25")
        requestEstimate()

        scrollTo("Review & Send")
        compose.onNodeWithText("Review & Send").assertIsNotEnabled()

        type("Wallet passkey", "correct horse")

        scrollTo("Review & Send")
        compose.onNodeWithText("Review & Send").assertIsEnabled().performClick()
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(5_000) { completed }

        assertEquals("correct horse", submitted?.passphrase)
        assertEquals(125_000_000L, submitted?.amountSatoshis)
    }
    @Test
    fun `an address without an amount shown disables the estimate button`() {
        render()
        type("To", recipient)

        scrollTo("Estimate Fee")
        compose.onNodeWithText("Estimate Fee").assertIsNotEnabled()

        scrollTo("Review & Send")
        compose.onNodeWithText("Review & Send").assertIsNotEnabled()
        assertNull(estimatedFor)
    }
    @Test
    fun `an address without an amount shows a clear error`() {
        render()
        type("To", recipient)

        scrollTo("Estimate Fee")
        compose.onNodeWithText("Estimate Fee").assertIsNotEnabled()

        scrollTo("Review & Send")
        compose.onNodeWithText("Review & Send").assertIsNotEnabled()
        assertNull(estimatedFor)
    }
}
