package org.stohncoin.wallet.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AppAuthManager {
    private const val PREFS_NAME = "stohn_auth_prefs"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    private const val KEY_UNLOCK_PIN = "unlock_pin_hash"

    fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isAppLockEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_APP_LOCK_ENABLED, false)
    }

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
    }

    fun isBiometricEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun setPin(context: Context, pin: String) {
        val hash = pin.toByteArray().let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
            .joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_UNLOCK_PIN, hash).apply()
        setAppLockEnabled(context, true)
    }

    fun hasPin(context: Context): Boolean {
        return !prefs(context).getString(KEY_UNLOCK_PIN, null).isNullOrBlank()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_UNLOCK_PIN, null) ?: return false
        val hash = pin.toByteArray().let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
            .joinToString("") { "%02x".format(it) }
        return stored == hash
    }

    fun clearPin(context: Context) {
        prefs(context).edit().remove(KEY_UNLOCK_PIN).apply()
        setAppLockEnabled(context, false)
        setBiometricEnabled(context, false)
    }
}
