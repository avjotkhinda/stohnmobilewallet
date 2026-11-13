package org.stohncoin.wallet.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * App-lock state: PIN verifier, biometric preference and attempt throttling.
 *
 * Invariants enforced here:
 *  - the PIN is only ever stored as a salted PBKDF2 record (see [PinHasher]);
 *  - biometric unlock cannot be enabled while no PIN exists, so a fallback verifier always exists;
 *  - clearing the PIN disables both the lock and biometric unlock;
 *  - failures and the block expiry are persisted, so killing the app does not reset throttling.
 *
 * The public functions take a [Context] because the real store is [prefs], an
 * [EncryptedSharedPreferences] built on a key the Android keystore holds. Every decision is
 * actually made by the `store` overloads below, against a plain [SharedPreferences]: the keystore
 * cannot exist off-device, so this split is what lets the lock policy run in JVM tests instead of
 * being verifiable only by hand on a phone.
 *
 * When the keystore itself cannot produce a store, the `Context` overloads below fail closed: the
 * app counts as locked, nothing can be verified, and no write is invented. See [prefs].
 */
object AppAuthManager {
    // Internal, not private, so a test can pin them: these names are an on-disk contract, and
    // renaming one does not fail a build - it silently forgets that a user ever set a PIN.
    internal const val PREFS_NAME = "stohn_auth_prefs"
    internal const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    internal const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    internal const val KEY_UNLOCK_PIN = "unlock_pin_hash"
    internal const val KEY_FAILURES = "unlock_failure_count"
    internal const val KEY_BLOCKED_UNTIL = "unlock_blocked_until"
    internal const val KEY_LOCK_GRACE = "lock_grace_millis"

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 6

    /**
     * How long the app may stay in the background before a return requires unlocking again.
     * Non-zero so that system dialogs (biometric prompt, camera scan, document picker) do not
     * bounce the user straight back to the lock screen, while still re-locking a pocketed phone.
     */
    const val DEFAULT_LOCK_GRACE_MILLIS = 30_000L
    private const val MAX_LOCK_GRACE_MILLIS = 30L * 60_000L
    private const val TAG = "AppAuthManager"

    @Volatile private var cached: SharedPreferences? = null

    /**
     * The encrypted store, or null when this device cannot provide one.
     *
     * The keystore is not under this app's control: it can be missing, invalidated by a lock-screen
     * change, or briefly unusable before the device is first unlocked.
     * [EncryptedSharedPreferences.create] reports all of that by throwing, and letting it escape
     * meant a keystore fault surfaced as a crash from every call site - including the launch path,
     * where it reads like a broken wallet. It is handled here instead, because an app lock whose
     * state cannot be read must fail closed, not fail loudly.
     *
     * A failure is deliberately not cached. These faults can be transient - a keystore that becomes
     * usable once the user unlocks the device is the ordinary case - and remembering the failure
     * would lock the user out for the lifetime of the process. Only a store that was built is
     * cached, which is also what keeps the successful path free of repeated keystore work.
     */
    fun prefs(context: Context): SharedPreferences? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            val created = try {
                EncryptedSharedPreferences.create(
                    app,
                    PREFS_NAME,
                    MasterKey.Builder(app)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Diagnostics never decide the outcome: a logging failure still fails closed.
                runCatching { Log.e(TAG, "app-lock store unavailable, failing closed", e) }
                return null
            }
            cached = created
            return created
        }
    }

    /** True when the encrypted store could be opened, so the lock can be evaluated at all. */
    fun isStoreAvailable(context: Context): Boolean = prefs(context) != null

    sealed interface PinVerdict {
        data object Success : PinVerdict
        data object Incorrect : PinVerdict
        data class Blocked(val retryAfterMillis: Long) : PinVerdict

        /**
         * The store could not be opened, so no PIN can be checked. Distinct from [Incorrect]: a
         * correct PIN would not help, the attempt is not counted against the user, and nothing is
         * granted.
         */
        data object Unavailable : PinVerdict
    }

    /** The lock is only meaningful while a PIN verifier exists. */
    /** Fail closed: an unreadable store counts as locked, never as unprotected. */
    fun isAppLockEnabled(context: Context): Boolean = prefs(context)?.let { isAppLockEnabled(it) } ?: true

    internal fun isAppLockEnabled(store: SharedPreferences): Boolean =
        hasPin(store) && store.getBoolean(KEY_APP_LOCK_ENABLED, false)

    fun isBiometricEnabled(context: Context): Boolean = prefs(context)?.let { isBiometricEnabled(it) } ?: false

    internal fun isBiometricEnabled(store: SharedPreferences): Boolean =
        hasPin(store) && store.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    /** Returns false when enabling was refused because no PIN is set. */
    fun setBiometricEnabled(context: Context, enabled: Boolean): Boolean =
        prefs(context)?.let { setBiometricEnabled(it, enabled) } ?: false

    internal fun setBiometricEnabled(store: SharedPreferences, enabled: Boolean): Boolean {
        if (enabled && !hasPin(store)) {
            store.edit { putBoolean(KEY_BIOMETRIC_ENABLED, false) }
            return false
        }
        store.edit { putBoolean(KEY_BIOMETRIC_ENABLED, enabled) }
        return true
    }

    fun setPin(context: Context, pin: String) {
        prefs(context)?.let { setPin(it, pin) }
    }

    internal fun setPin(store: SharedPreferences, pin: String) {
        require(pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits"
        }
        require(pin.all { it.isDigit() }) { "PIN must contain digits only" }
        store.edit {
            putString(KEY_UNLOCK_PIN, PinHasher.hash(pin))
            putBoolean(KEY_APP_LOCK_ENABLED, true)
            putInt(KEY_FAILURES, 0)
            putLong(KEY_BLOCKED_UNTIL, 0L)
        }
    }

    /** Fail closed: reported as present so nothing reads a broken store as "no lock configured". */
    fun hasPin(context: Context): Boolean = prefs(context)?.let { hasPin(it) } ?: true

    internal fun hasPin(store: SharedPreferences): Boolean =
        !store.getString(KEY_UNLOCK_PIN, null).isNullOrBlank()

    /** Verifies a PIN under the throttle policy and records the outcome. */
    fun submitPin(context: Context, pin: String, now: Long = System.currentTimeMillis()): PinVerdict =
        prefs(context)?.let { submitPin(it, pin, now) } ?: PinVerdict.Unavailable

    internal fun submitPin(store: SharedPreferences, pin: String, now: Long): PinVerdict {
        val state = PinThrottle.State(
            failures = store.getInt(KEY_FAILURES, 0),
            blockedUntil = store.getLong(KEY_BLOCKED_UNTIL, 0L)
        )
        if (PinThrottle.isBlocked(state, now)) {
            return PinVerdict.Blocked(PinThrottle.remainingMillis(state, now))
        }
        val stored = store.getString(KEY_UNLOCK_PIN, null) ?: return PinVerdict.Incorrect
        if (PinHasher.verify(pin, stored)) {
            val success = PinThrottle.afterSuccess()
            store.edit {
                putInt(KEY_FAILURES, success.failures)
                putLong(KEY_BLOCKED_UNTIL, success.blockedUntil)
                // A correct PIN is the moment a legacy unsalted digest can be replaced safely.
                if (PinHasher.isLegacy(stored)) putString(KEY_UNLOCK_PIN, PinHasher.hash(pin))
            }
            return PinVerdict.Success
        }
        val next = PinThrottle.afterFailure(state, now)
        store.edit {
            putInt(KEY_FAILURES, next.failures)
            putLong(KEY_BLOCKED_UNTIL, next.blockedUntil)
        }
        return if (PinThrottle.isBlocked(next, now)) {
            PinVerdict.Blocked(PinThrottle.remainingMillis(next, now))
        } else {
            PinVerdict.Incorrect
        }
    }

    fun clearPin(context: Context) {
        prefs(context)?.let { clearPin(it) }
    }

    internal fun clearPin(store: SharedPreferences) {
        store.edit {
            remove(KEY_UNLOCK_PIN)
            putBoolean(KEY_APP_LOCK_ENABLED, false)
            putBoolean(KEY_BIOMETRIC_ENABLED, false)
            putInt(KEY_FAILURES, 0)
            putLong(KEY_BLOCKED_UNTIL, 0L)
        }
    }

    /** Fail closed: an unreadable store re-locks immediately rather than granting a grace period. */
    fun lockGraceMillis(context: Context): Long = prefs(context)?.let { lockGraceMillis(it) } ?: 0L

    internal fun lockGraceMillis(store: SharedPreferences): Long =
        store.getLong(KEY_LOCK_GRACE, DEFAULT_LOCK_GRACE_MILLIS).coerceIn(0L, MAX_LOCK_GRACE_MILLIS)

    fun setLockGraceMillis(context: Context, millis: Long) {
        prefs(context)?.let { setLockGraceMillis(it, millis) }
    }

    internal fun setLockGraceMillis(store: SharedPreferences, millis: Long) {
        store.edit { putLong(KEY_LOCK_GRACE, millis.coerceIn(0L, MAX_LOCK_GRACE_MILLIS)) }
    }
}
