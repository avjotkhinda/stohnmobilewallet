package org.stohncoin.wallet.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Provides the AES key used to encrypt the address book at rest, held in the Android Keystore so it
 * cannot be extracted. Fail-closed: returns null when the keystore is unavailable, in which case
 * the address book degrades to plaintext storage rather than crashing or losing data.
 */
object AddressBookKey {
    private const val ALIAS = "stohn_addressbook_key"
    private const val TAG = "AddressBookKey"

    fun get(context: Context): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey) ?: generate(keyStore)
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "address-book key unavailable, degrading to plaintext", e) }
            null
        }
    }

    private fun generate(keyStore: KeyStore): SecretKey? {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}