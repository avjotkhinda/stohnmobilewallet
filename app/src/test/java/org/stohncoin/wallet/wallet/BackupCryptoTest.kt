package org.stohncoin.wallet.wallet

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupCryptoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val password = "correct horse battery staple"

    /** Offsets into a v2 header: magic(14) version kdf logN r p saltLen salt(16) noncePrefix(8) chunkSize(4) length(8). */
    private val chunkSizeOffset = 14 + 1 + 1 + 1 + 1 + 1 + 1 + 16 + 8

    private fun snapshot(size: Int): File = temporaryFolder.newFile("wallet-${System.nanoTime()}.snapshot").apply {
        writeBytes(ByteArray(size) { (it * 31 % 251).toByte() })
    }

    private fun encrypt(plaintext: File): File {
        val output = File(temporaryFolder.root, "wallet-${System.nanoTime()}.backup")
        BackupCrypto.encrypt(plaintext, output, password.toCharArray())
        return output
    }

    private fun restored(): File = File(temporaryFolder.root, "restored-${System.nanoTime()}.snapshot")

    @Test
    fun encryptedBackupRoundTrips() {
        val plaintext = snapshot(4096)
        val encrypted = encrypt(plaintext)
        val target = restored()

        BackupCrypto.decrypt(encrypted, target, password.toCharArray())

        assertArrayEquals(plaintext.readBytes(), target.readBytes())
    }

    @Test
    fun `payloads larger than one chunk round trip`() {
        // One full chunk plus a short tail, so the last chunk is a partial one.
        val plaintext = snapshot((1 shl 20) + 4097)
        val encrypted = encrypt(plaintext)
        val target = restored()

        BackupCrypto.decrypt(encrypted, target, password.toCharArray())

        assertArrayEquals(plaintext.readBytes(), target.readBytes())
        assertEquals((1 shl 20) + 4097, target.length().toInt())
    }

    @Test
    fun `an empty payload still authenticates`() {
        val plaintext = snapshot(0)
        val encrypted = encrypt(plaintext)
        val target = restored()

        BackupCrypto.decrypt(encrypted, target, password.toCharArray())

        assertEquals(0L, target.length())
    }

    @Test
    fun authenticationFailureDoesNotInstallPlaintext() {
        val encrypted = encrypt(snapshot(4096))
        val target = restored()

        runCatching {
            BackupCrypto.decrypt(encrypted, target, "wrong password".toCharArray())
        }.onSuccess { error("Wrong backup password unexpectedly decrypted the wallet") }

        assertFalse("Failed decryption must not leave plaintext at the requested path", target.exists())
    }

    @Test
    fun `a flipped ciphertext byte is rejected`() {
        val encrypted = encrypt(snapshot(4096))
        val bytes = encrypted.readBytes()
        bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 0xFF).toByte()
        encrypted.writeBytes(bytes)
        val target = restored()

        runCatching { BackupCrypto.decrypt(encrypted, target, password.toCharArray()) }
            .onSuccess { error("A damaged backup was accepted") }

        assertFalse(target.exists())
    }

    @Test
    fun `a truncated container is rejected`() {
        val encrypted = encrypt(snapshot(4096))
        val bytes = encrypted.readBytes()
        encrypted.writeBytes(bytes.copyOf(bytes.size - 10))
        val target = restored()

        runCatching { BackupCrypto.decrypt(encrypted, target, password.toCharArray()) }
            .onSuccess { error("A truncated backup was accepted") }

        assertFalse(target.exists())
    }

    @Test
    fun `trailing data after the terminal chunk is rejected`() {
        val encrypted = encrypt(snapshot(4096))
        encrypted.writeBytes(encrypted.readBytes() + byteArrayOf(1, 2, 3))
        val target = restored()

        runCatching { BackupCrypto.decrypt(encrypted, target, password.toCharArray()) }
            .onSuccess { error("A backup with trailing data was accepted") }

        assertFalse(target.exists())
    }

    @Test
    fun `tampering with a header field is rejected`() {
        // Settle on a different, still legal chunk size: the header is in every chunk's AAD.
        val encrypted = encrypt(snapshot(4096))
        val bytes = encrypted.readBytes()
        bytes[chunkSizeOffset] = 0
        bytes[chunkSizeOffset + 1] = 1
        bytes[chunkSizeOffset + 2] = 0
        bytes[chunkSizeOffset + 3] = 0
        encrypted.writeBytes(bytes)
        val target = restored()

        runCatching { BackupCrypto.decrypt(encrypted, target, password.toCharArray()) }
            .onSuccess { error("A backup with a forged header was accepted") }

        assertFalse(target.exists())
    }

    @Test
    fun `a container from the previous release still restores`() {
        val plaintext = snapshot(2048)
        val legacy = File(temporaryFolder.root, "legacy.backup")
        writeContainerV1(plaintext.readBytes(), legacy)
        val target = restored()

        BackupCrypto.decrypt(legacy, target, password.toCharArray())

        assertArrayEquals(plaintext.readBytes(), target.readBytes())
    }

    @Test
    fun `decryption refuses output beyond the caller limit`() {
        val encrypted = encrypt(snapshot(4096))
        val target = restored()

        runCatching { BackupCrypto.decrypt(encrypted, target, password.toCharArray(), maxOutputBytes = 1024L) }
            .onSuccess { error("A backup larger than the limit was accepted") }

        assertFalse(target.exists())
    }

    /** Writes the pre-v2 format independently, so compatibility is proven against the old layout. */
    private fun writeContainerV1(plaintext: ByteArray, target: File) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            }
        } finally {
            key.fill(0)
        }
        DataOutputStream(FileOutputStream(target)).use { out ->
            out.write("STOHN-BACKUP-1".toByteArray(Charsets.US_ASCII))
            out.writeInt(600_000)
            out.writeByte(salt.size)
            out.write(salt)
            out.writeByte(iv.size)
            out.write(iv)
            CipherOutputStream(out, cipher).use { it.write(plaintext) }
        }
    }
}
