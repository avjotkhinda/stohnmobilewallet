package org.stohncoin.wallet.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app-lock rules, run against a plain [SharedPreferences].
 *
 * `AppAuthManager.prefs` cannot appear here: it is an EncryptedSharedPreferences over a key held by
 * the Android keystore, and Robolectric has no keystore, so building one throws
 * `KeyStoreException: AndroidKeyStore not found`. The `store` overloads take the same decisions
 * against whatever store they are handed, which is what makes the lock, the biometric gate and the
 * throttle verifiable on the JVM instead of only by hand on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class AppAuthManagerTest {

    private lateinit var context: Context
    private lateinit var store: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = context.getSharedPreferences("stohn_auth_test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
    }

    @Test
    fun `a fresh install has no PIN, no lock and no biometric`() {
        assertFalse(AppAuthManager.hasPin(store))
        assertFalse(AppAuthManager.isAppLockEnabled(store))
        assertFalse(AppAuthManager.isBiometricEnabled(store))
    }

    @Test
    fun `the stored record is a salted verifier, not the PIN`() {
        AppAuthManager.setPin(store, "1234")

        val stored = store.getString(AppAuthManager.KEY_UNLOCK_PIN, null)
        assertNotEquals("1234", stored)
        assertTrue("expected a PBKDF2 record but got $stored", stored!!.startsWith("pbkdf2-sha256$"))
        assertFalse("the PIN itself is stored somewhere in the record", stored.contains("1234"))
        assertTrue(PinHasher.verify("1234", stored))
    }

    @Test
    fun `the same PIN hashed twice produces different records`() {
        AppAuthManager.setPin(store, "1234")
        val first = store.getString(AppAuthManager.KEY_UNLOCK_PIN, null)
        AppAuthManager.setPin(store, "1234")
        val second = store.getString(AppAuthManager.KEY_UNLOCK_PIN, null)

        assertNotEquals("the salt is not random", first, second)
        assertTrue(PinHasher.verify("1234", second))
    }

    @Test
    fun `setting a PIN enables the lock`() {
        AppAuthManager.setPin(store, "1234")

        assertTrue(AppAuthManager.hasPin(store))
        assertTrue(AppAuthManager.isAppLockEnabled(store))
    }

    @Test
    fun `a PIN outside four to six digits is refused`() {
        for (bad in listOf("", "123", "1234567", "12a4", "12 34")) {
            try {
                AppAuthManager.setPin(store, bad)
                throw AssertionError("'$bad' was accepted as a PIN")
            } catch (expected: IllegalArgumentException) {
                assertFalse("'$bad' left a record behind", AppAuthManager.hasPin(store))
            }
        }
    }

    @Test
    fun `biometric cannot be switched on while no PIN exists`() {
        assertFalse(AppAuthManager.setBiometricEnabled(store, true))
        assertFalse(AppAuthManager.isBiometricEnabled(store))
        assertFalse(store.getBoolean(AppAuthManager.KEY_BIOMETRIC_ENABLED, false))
    }

    @Test
    fun `biometric can be switched on once a PIN backs it up`() {
        AppAuthManager.setPin(store, "1234")

        assertTrue(AppAuthManager.setBiometricEnabled(store, true))
        assertTrue(AppAuthManager.isBiometricEnabled(store))

        assertTrue(AppAuthManager.setBiometricEnabled(store, false))
        assertFalse(AppAuthManager.isBiometricEnabled(store))
    }

    @Test
    fun `a lock flag without a PIN leaves the app unlocked`() {
        // The lock is only meaningful with a verifier behind it; a leftover flag must not gate the
        // app, which would strand a user who can never satisfy it.
        store.edit()
            .putBoolean(AppAuthManager.KEY_APP_LOCK_ENABLED, true)
            .putBoolean(AppAuthManager.KEY_BIOMETRIC_ENABLED, true)
            .commit()

        assertFalse(AppAuthManager.isAppLockEnabled(store))
        assertFalse(AppAuthManager.isBiometricEnabled(store))
    }

    @Test
    fun `clearing the PIN drops the verifier, the lock and the biometric preference`() {
        AppAuthManager.setPin(store, "1234")
        AppAuthManager.setBiometricEnabled(store, true)

        AppAuthManager.clearPin(store)

        assertFalse(AppAuthManager.hasPin(store))
        assertFalse(AppAuthManager.isAppLockEnabled(store))
        assertFalse(AppAuthManager.isBiometricEnabled(store))
        assertNull(store.getString(AppAuthManager.KEY_UNLOCK_PIN, null))
    }

    @Test
    fun `a correct PIN is accepted and clears the failures behind it`() {
        AppAuthManager.setPin(store, "1234")
        repeat(3) { assertEquals(AppAuthManager.PinVerdict.Incorrect, AppAuthManager.submitPin(store, "9999", NOW)) }

        assertEquals(AppAuthManager.PinVerdict.Success, AppAuthManager.submitPin(store, "1234", NOW))

        assertEquals(0, store.getInt(AppAuthManager.KEY_FAILURES, -1))
        assertEquals(0L, store.getLong(AppAuthManager.KEY_BLOCKED_UNTIL, -1L))
        // The counter really was reset: one failure is free again rather than a block.
        assertEquals(AppAuthManager.PinVerdict.Incorrect, AppAuthManager.submitPin(store, "9999", NOW))
    }

    @Test
    fun `a wrong PIN is merely incorrect while attempts are free`() {
        AppAuthManager.setPin(store, "1234")

        repeat(PinThrottle.FREE_ATTEMPTS - 1) {
            assertEquals(AppAuthManager.PinVerdict.Incorrect, AppAuthManager.submitPin(store, "9999", NOW))
        }
        assertEquals(PinThrottle.FREE_ATTEMPTS - 1, store.getInt(AppAuthManager.KEY_FAILURES, -1))
    }

    @Test
    fun `crossing the free attempts blocks further tries and records the deadline`() {
        AppAuthManager.setPin(store, "1234")
        repeat(PinThrottle.FREE_ATTEMPTS - 1) { AppAuthManager.submitPin(store, "9999", NOW) }

        val verdict = AppAuthManager.submitPin(store, "9999", NOW)

        assertTrue("expected a block but got $verdict", verdict is AppAuthManager.PinVerdict.Blocked)
        val retryAfter = (verdict as AppAuthManager.PinVerdict.Blocked).retryAfterMillis
        assertEquals(PinThrottle.BASE_BACKOFF_MILLIS, retryAfter)
        assertEquals(NOW + PinThrottle.BASE_BACKOFF_MILLIS, store.getLong(AppAuthManager.KEY_BLOCKED_UNTIL, -1L))
    }

    @Test
    fun `a block outlasts the process because it is persisted, and refuses even the right PIN`() {
        AppAuthManager.setPin(store, "1234")
        repeat(PinThrottle.FREE_ATTEMPTS) { AppAuthManager.submitPin(store, "9999", NOW) }

        // A fresh read of the same store stands in for a restart: killing the app must not reset
        // the throttle, and knowing the PIN must not shortcut it.
        val restarted = context.getSharedPreferences("stohn_auth_test", Context.MODE_PRIVATE)
        val verdict = AppAuthManager.submitPin(restarted, "1234", NOW + 1_000L)

        assertTrue("the throttle was reset by a restart", verdict is AppAuthManager.PinVerdict.Blocked)
        assertEquals(
            PinThrottle.BASE_BACKOFF_MILLIS - 1_000L,
            (verdict as AppAuthManager.PinVerdict.Blocked).retryAfterMillis
        )
    }

    @Test
    fun `the block lifts once its deadline passes`() {
        AppAuthManager.setPin(store, "1234")
        repeat(PinThrottle.FREE_ATTEMPTS) { AppAuthManager.submitPin(store, "9999", NOW) }
        val deadline = store.getLong(AppAuthManager.KEY_BLOCKED_UNTIL, 0L)

        assertEquals(AppAuthManager.PinVerdict.Success, AppAuthManager.submitPin(store, "1234", deadline))
    }

    @Test
    fun `submitting a PIN that was never set is refused as incorrect`() {
        assertEquals(AppAuthManager.PinVerdict.Incorrect, AppAuthManager.submitPin(store, "1234", NOW))
    }

    @Test
    fun `a legacy unsalted digest opens the lock once and is replaced immediately`() {
        // Users upgrading from the release that stored a bare SHA-256 keep their PIN, and the next
        // successful unlock rewrites the record in the salted format.
        store.edit()
            .putString(AppAuthManager.KEY_UNLOCK_PIN, PinHasher.legacySha256Hex("1234"))
            .putBoolean(AppAuthManager.KEY_APP_LOCK_ENABLED, true)
            .commit()

        assertEquals(AppAuthManager.PinVerdict.Success, AppAuthManager.submitPin(store, "1234", NOW))

        val upgraded = store.getString(AppAuthManager.KEY_UNLOCK_PIN, null)
        assertFalse("the weak digest survived a successful unlock", PinHasher.isLegacy(upgraded))
        assertTrue(PinHasher.verify("1234", upgraded))
    }

    @Test
    fun `a failed attempt against a legacy digest leaves it alone`() {
        store.edit().putString(AppAuthManager.KEY_UNLOCK_PIN, PinHasher.legacySha256Hex("1234")).commit()

        assertEquals(AppAuthManager.PinVerdict.Incorrect, AppAuthManager.submitPin(store, "9999", NOW))

        assertTrue(PinHasher.isLegacy(store.getString(AppAuthManager.KEY_UNLOCK_PIN, null)))
    }

    @Test
    fun `the re-lock grace defaults, is stored, and is clamped at both ends`() {
        assertEquals(AppAuthManager.DEFAULT_LOCK_GRACE_MILLIS, AppAuthManager.lockGraceMillis(store))

        AppAuthManager.setLockGraceMillis(store, 0L)
        assertEquals(0L, AppAuthManager.lockGraceMillis(store))

        AppAuthManager.setLockGraceMillis(store, 90_000L)
        assertEquals(90_000L, AppAuthManager.lockGraceMillis(store))

        // A negative grace would re-lock the app behind a system dialog; a day-long one would let a
        // stolen phone stay open for the rest of the day.
        AppAuthManager.setLockGraceMillis(store, -5_000L)
        assertEquals(0L, AppAuthManager.lockGraceMillis(store))

        AppAuthManager.setLockGraceMillis(store, 24L * 60L * 60_000L)
        assertEquals(30L * 60_000L, AppAuthManager.lockGraceMillis(store))
    }

    @Test
    fun `the on-disk key names are unchanged`() {
        // These names are a persisted contract: a rename does not fail a build, it makes every
        // existing user's PIN invisible and locks them out of a wallet they can no longer open.
        assertEquals("stohn_auth_prefs", AppAuthManager.PREFS_NAME)
        assertEquals("unlock_pin_hash", AppAuthManager.KEY_UNLOCK_PIN)
        assertEquals("app_lock_enabled", AppAuthManager.KEY_APP_LOCK_ENABLED)
        assertEquals("biometric_enabled", AppAuthManager.KEY_BIOMETRIC_ENABLED)
        assertEquals("unlock_failure_count", AppAuthManager.KEY_FAILURES)
        assertEquals("unlock_blocked_until", AppAuthManager.KEY_BLOCKED_UNTIL)
        assertEquals("lock_grace_millis", AppAuthManager.KEY_LOCK_GRACE)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
