package org.stohncoin.wallet.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the lock does when the encrypted store cannot be built at all.
 *
 * Robolectric has no AndroidKeyStore, so `EncryptedSharedPreferences.create` throws exactly the
 * `KeyStoreException` that a device with a missing or invalidated keystore produces. That makes
 * this the one part of the keystore path testable off-device, and it is the part that used to
 * escape: the exception propagated out of every call site instead of being answered. These tests
 * therefore assert a refusal - locked, nothing verifiable, nothing written - not a crash.
 */
@RunWith(RobolectricTestRunner::class)
class AppAuthManagerStoreUnavailableTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `an unavailable store yields no prefs and no exception`() {
        assertNull(AppAuthManager.prefs(context))
        assertFalse(AppAuthManager.isStoreAvailable(context))
    }

    @Test
    fun `the app counts as locked when its store cannot be read`() {
        // Locked, and reported as having a verifier: the one reading that can never be mistaken
        // for "this app has no lock configured".
        assertTrue(AppAuthManager.isAppLockEnabled(context))
        assertTrue(AppAuthManager.hasPin(context))
    }

    @Test
    fun `no grace period and no biometric bypass are granted`() {
        assertEquals(0L, AppAuthManager.lockGraceMillis(context))
        assertFalse(AppAuthManager.isBiometricEnabled(context))
        assertFalse(AppAuthManager.setBiometricEnabled(context, true))
    }

    @Test
    fun `a PIN cannot be accepted, refused or throttled`() {
        assertEquals(AppAuthManager.PinVerdict.Unavailable, AppAuthManager.submitPin(context, "1234"))
        // Repeated attempts stay unavailable: nothing is counted, so nothing can be blocked either.
        repeat(3) {
            assertEquals(AppAuthManager.PinVerdict.Unavailable, AppAuthManager.submitPin(context, "9999"))
        }
    }

    @Test
    fun `mutations are dropped instead of invented`() {
        AppAuthManager.setPin(context, "1234")
        AppAuthManager.setLockGraceMillis(context, 60_000L)
        AppAuthManager.clearPin(context)

        // Still locked with no grace: a write that could not be stored must not change the verdict.
        assertTrue(AppAuthManager.isAppLockEnabled(context))
        assertEquals(0L, AppAuthManager.lockGraceMillis(context))
    }
}
