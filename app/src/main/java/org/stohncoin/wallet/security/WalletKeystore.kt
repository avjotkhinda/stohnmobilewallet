package org.stohncoin.wallet.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Android Keystore-backed AES-GCM envelope for wallet metadata. The Keystore key is non-exportable; wallet ciphertext remains app-private. */
object WalletKeystore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "stohn-wallet-master-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    data class Envelope(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(plaintext: ByteArray): Envelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return Envelope(cipher.iv.copyOf(), cipher.doFinal(plaintext))
    }

    fun decrypt(envelope: Envelope): ByteArray {
        require(envelope.iv.size == IV_BYTES) { "Invalid wallet encryption IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(TAG_BITS, envelope.iv))
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
