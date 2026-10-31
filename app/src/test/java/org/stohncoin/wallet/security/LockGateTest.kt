package org.stohncoin.wallet.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockGateTest {
    private val now = 5_000_000L
    private val grace = AppAuthManager.DEFAULT_LOCK_GRACE_MILLIS

    @Test
    fun coldStartLocksWhenAppLockIsEnabled() {
        assertTrue(
            "an enabled lock must gate the first screen the process draws",
            LockGate.mustRelockOnStart(
                appLockEnabled = true,
                backgroundedAtMillis = LockGate.NEVER_BACKGROUNDED,
                now = now,
                graceMillis = grace
            )
        )
    }

    @Test
    fun noPinMeansNothingToEnforce() {
        assertFalse(
            "without a configured lock the gate must stay open",
            LockGate.mustRelockOnStart(
                appLockEnabled = false,
                backgroundedAtMillis = LockGate.NEVER_BACKGROUNDED,
                now = now,
                graceMillis = grace
            )
        )
        assertFalse(
            "clearing the PIN must stop re-locking on resume",
            LockGate.mustRelockOnStart(
                appLockEnabled = false,
                backgroundedAtMillis = now - grace * 4,
                now = now,
                graceMillis = grace
            )
        )
    }

    @Test
    fun shortTripIntoASystemDialogStaysUnlocked() {
        assertFalse(
            "the biometric prompt, scanner and file picker must not throw the user back to the lock",
            LockGate.mustRelockOnStart(
                appLockEnabled = true,
                backgroundedAtMillis = now - (grace - 1_000L),
                now = now,
                graceMillis = grace
            )
        )
    }

    @Test
    fun returnAfterTheGraceRelocks() {
        assertTrue(
            "a pocketed phone must require the PIN again",
            LockGate.mustRelockOnStart(
                appLockEnabled = true,
                backgroundedAtMillis = now - grace,
                now = now,
                graceMillis = grace
            )
        )
        assertTrue(
            "longer absences must also re-lock",
            LockGate.mustRelockOnStart(
                appLockEnabled = true,
                backgroundedAtMillis = now - (grace * 10),
                now = now,
                graceMillis = grace
            )
        )
    }

    @Test
    fun zeroGraceMeansLockTheMomentItGoesAway() {
        assertTrue(
            "an immediate re-lock setting must never leave the wallet open on return",
            LockGate.mustRelockOnStart(
                appLockEnabled = true,
                backgroundedAtMillis = now - 1L,
                now = now,
                graceMillis = 0L
            )
        )
    }

    @Test
    fun backwardClockSkewDoesNotUnlock() {
        // System clock moved backwards while backgrounded: treat it as "just left", never as expired.
        assertFalse(
            "clock skew must not open the gate",
            LockGate.mustRelockOnStart(
                appLockEnabled = true,
                backgroundedAtMillis = now + 60_000L,
                now = now,
                graceMillis = grace
            )
        )
    }
}
