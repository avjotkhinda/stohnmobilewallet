package org.stohncoin.wallet.security

import android.content.Context
import android.content.SharedPreferences
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
 */
object AppAuthManager {
    private const val PREFS_NAME = "stohn_auth_prefs"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    private const val KEY_UNLOCK_PIN = "unlock_pin_hash"
    private const val KEY_FAILURES = "unlock_failure_count"
    private const val KEY_BLOCKED_UNTIL = "unlock_blocked_until"
    private const val KEY_LOCK_GRACE = "lock_grace_millis"

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 6

    /**
     * How long the app may stay in the background before a return requires unlocking again.
     * Non-zero so that system dialogs (biometric prompt, camera scan, document picker) do not
     * bounce the user straight back to the lock screen, while still re-locking a pocketed phone.
     */
    const val DEFAULT_LOCK_GRACE_MILLIS = 30_000L
    private const val MAX_LOCK_GRACE_MILLIS = 30L * 60_000L

    @Volatile private var cached: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            val created = EncryptedSharedPreferences.create(
                app,
                PREFS_NAME,
                MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cached = created
            return created
        }
    }

    sealed interface PinVerdict {
        data object Success : PinVerdict
        data object Incorrect : PinVerdict
        data class Blocked(val retryAfterMillis: Long) : PinVerdict
    }

    /** The lock is only meaningful while a PIN verifier exists. */
    fun isAppLockEnabled(context: Context): Boolean =
        hasPin(context) && prefs(context).getBoolean(KEY_APP_LOCK_ENABLED, false)

    fun isBiometricEnabled(context: Context): Boolean =
        hasPin(context) && prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)

    /** Returns false when enabling was refused because no PIN is set. */
    fun setBiometricEnabled(context: Context, enabled: Boolean): Boolean {
        val store = prefs(context)
        if (enabled && !hasPin(context)) {
            store.edit().putBoolean(KEY_BIOMETRIC_ENABLED, false).apply()
            return false
        }
        store.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
        return true
    }

    fun setPin(context: Context, pin: String) {
        require(pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits"
        }
        require(pin.all { it.isDigit() }) { "PIN must contain digits only" }
        prefs(context).edit()
            .putString(KEY_UNLOCK_PIN, PinHasher.hash(pin))
            .putBoolean(KEY_APP_LOCK_ENABLED, true)
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_BLOCKED_UNTIL, 0L)
            .apply()
    }

    fun hasPin(context: Context): Boolean =
        !prefs(context).getString(KEY_UNLOCK_PIN, null).isNullOrBlank()

    /** Verifies a PIN under the throttle policy and records the outcome. */
    fun submitPin(context: Context, pin: String, now: Long = System.currentTimeMillis()): PinVerdict {
        val store = prefs(context)
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
            val editor = store.edit()
                .putInt(KEY_FAILURES, success.failures)
                .putLong(KEY_BLOCKED_UNTIL, success.blockedUntil)
            // A correct PIN is the moment a legacy unsalted digest can be replaced safely.
            if (PinHasher.isLegacy(stored)) editor.putString(KEY_UNLOCK_PIN, PinHasher.hash(pin))
            editor.apply()
            return PinVerdict.Success
        }
        val next = PinThrottle.afterFailure(state, now)
        store.edit()
            .putInt(KEY_FAILURES, next.failures)
            .putLong(KEY_BLOCKED_UNTIL, next.blockedUntil)
            .apply()
        return if (PinThrottle.isBlocked(next, now)) {
            PinVerdict.Blocked(PinThrottle.remainingMillis(next, now))
        } else {
            PinVerdict.Incorrect
        }
    }

    fun clearPin(context: Context) {
        prefs(context).edit()
            .remove(KEY_UNLOCK_PIN)
            .putBoolean(KEY_APP_LOCK_ENABLED, false)
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_BLOCKED_UNTIL, 0L)
            .apply()
    }

    fun lockGraceMillis(context: Context): Long =
        prefs(context).getLong(KEY_LOCK_GRACE, DEFAULT_LOCK_GRACE_MILLIS)
            .coerceIn(0L, MAX_LOCK_GRACE_MILLIS)

    fun setLockGraceMillis(context: Context, millis: Long) {
        prefs(context).edit()
            .putLong(KEY_LOCK_GRACE, millis.coerceIn(0L, MAX_LOCK_GRACE_MILLIS))
            .apply()
    }
}
