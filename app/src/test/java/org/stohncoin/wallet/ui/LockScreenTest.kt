package org.stohncoin.wallet.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lock gate as rendered on the JVM.
 *
 * Only [LockScreenContent] is exercised: [LockScreen] reads the app-lock store, which needs the
 * Android keystore that Robolectric does not implement. What is left is the whole of what the user
 * sees and can press, driven by state, which is where the rules that matter actually live.
 */
@RunWith(RobolectricTestRunner::class)
class LockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var unlocked = false
    private var biometricRequested = false
    private var retried = false

    private fun render(
        hasPin: Boolean = true,
        biometricEnabled: Boolean = false,
        pin: String = "",
        error: String? = null,
        blockedMillis: Long = 0L,
        storeAvailable: Boolean = true
    ) {
        compose.setContent {
            LockScreenContent(
                hasPin = hasPin,
                biometricEnabled = biometricEnabled,
                pin = pin,
                onPinChange = {},
                error = error,
                blockedMillis = blockedMillis,
                onUnlock = { unlocked = true },
                onBiometric = { biometricRequested = true },
                storeAvailable = storeAvailable,
                onRetryStore = { retried = true }
            )
        }
    }

    @Test
    fun `without a PIN there is nothing to enter and the gate offers no way past it`() {
        render(hasPin = false)

        compose.onNodeWithText("Stohn Wallet Locked").assertIsDisplayed()
        compose.onNodeWithText("Enter PIN").assertDoesNotExist()
        compose.onNodeWithText("Unlock").assertDoesNotExist()
    }

    @Test
    fun `a PIN renders the entry field`() {
        render(hasPin = true)

        compose.onNodeWithText("Enter PIN").assertIsDisplayed()
    }

    @Test
    fun `Unlock stays disabled while the PIN is shorter than one`() {
        render(pin = "123")

        compose.onNodeWithText("Unlock").assertIsNotEnabled()
    }

    @Test
    fun `Unlock is enabled once the PIN is long enough`() {
        render(pin = "1234")

        compose.onNodeWithText("Unlock").assertIsEnabled()
    }

    @Test
    fun `pressing Unlock reports it`() {
        render(pin = "1234")

        compose.onNodeWithText("Unlock").performClick()

        assertTrue(unlocked)
    }

    @Test
    fun `a rejected PIN is shown beside the field`() {
        render(pin = "9999", error = "Incorrect PIN")

        compose.onNodeWithText("Incorrect PIN").assertIsDisplayed()
    }

    @Test
    fun `a block disables the entry and counts down in whole seconds`() {
        render(pin = "1234", error = "Too many attempts", blockedMillis = 2_500L)

        compose.onNodeWithText("Too many attempts").assertIsDisplayed()
        compose.onNodeWithText("Try again in 3s").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertIsNotEnabled()
        compose.onNodeWithText("Enter PIN").assertIsNotEnabled()
    }

    @Test
    fun `the biometric button is absent unless it was enabled`() {
        render(biometricEnabled = false)

        compose.onNodeWithText("Use Biometric/Device Unlock").assertDoesNotExist()
        assertFalse(biometricRequested)
    }

    @Test
    fun `an unreadable store explains itself and offers no way in`() {
        // Locked, but nothing here can be satisfied: no PIN field, no Unlock, no biometric bypass.
        render(hasPin = true, storeAvailable = false)

        compose.onNodeWithText("Stohn Wallet Locked").assertIsDisplayed()
        compose.onNodeWithText(
            "This device cannot open the app lock's encrypted storage, so the wallet stays locked."
        ).assertIsDisplayed()
        compose.onNodeWithText("Enter PIN").assertDoesNotExist()
        compose.onNodeWithText("Unlock").assertDoesNotExist()
        compose.onNodeWithText("Use Biometric/Device Unlock").assertDoesNotExist()
        assertFalse(unlocked)
    }

    @Test
    fun `retrying asks the store again rather than unlocking`() {
        render(storeAvailable = false)

        compose.onNodeWithText("Try again").performClick()

        assertTrue(retried)
        assertFalse(unlocked)
    }

    @Test
    fun `the biometric button asks for an unlock when pressed`() {
        render(biometricEnabled = true)

        compose.onNodeWithText("Use Biometric/Device Unlock").performClick()

        assertTrue(biometricRequested)
    }
}
