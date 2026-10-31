package org.stohncoin.wallet.security

/**
 * Pure decision for when the app must show the lock screen again.
 *
 * [MainActivity] supplies the timestamps and the configured grace; this object only decides, so the
 * cold-start and resume transitions can be unit-tested on the JVM next to [PinThrottle] instead of
 * being asserted through an Activity lifecycle.
 */
object LockGate {
    /** Stored when the app has never left the foreground in this process (cold start). */
    const val NEVER_BACKGROUNDED = 0L

    /**
     * True when the UI must be gated on the authenticator at [onStart].
     *
     *  - cold start: locks whenever an app lock is configured;
     *  - a return within [graceMillis] keeps the wallet open, so a short trip into a system dialog
     *    (biometric prompt, QR scanner, document picker) does not bounce the user back to the lock;
     *  - a return after [graceMillis] re-locks, which is what makes a pocketed phone safe;
     *  - with no PIN configured there is nothing to enforce, so the gate stays open.
     */
    fun mustRelockOnStart(
        appLockEnabled: Boolean,
        backgroundedAtMillis: Long,
        now: Long,
        graceMillis: Long
    ): Boolean {
        if (!appLockEnabled) return false
        if (backgroundedAtMillis <= NEVER_BACKGROUNDED) return true
        return now - backgroundedAtMillis >= graceMillis
    }
}
