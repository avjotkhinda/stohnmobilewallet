package org.stohncoin.wallet.wallet

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-encrypted backup container. Plain wallet snapshots never leave this boundary. */
object BackupCrypto {
    private val MAGIC = "STOHN-BACKUP-1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    fun encrypt(input: File, output: File, password: CharArray) {
        require(password.isNotEmpty()) { "Backup password must not be empty" }
        require(input.isFile) { "Backup input does not exist" }
        require(input.canonicalFile != output.canonicalFile) { "Backup input and output must differ" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        val temp = File(output.parentFile ?: File("."), ".${output.name}.${java.util.UUID.randomUUID()}.part")
        try {
            temp.parentFile?.mkdirs()
            DataOutputStream(FileOutputStream(temp)).use { out ->
                out.write(MAGIC)
                out.writeInt(ITERATIONS)
                out.writeByte(salt.size)
                out.write(salt)
                out.writeByte(iv.size)
                out.write(iv)
                CipherOutputStream(out, cipher).use { encrypted ->
                    FileInputStream(input).use { it.copyTo(encrypted, 64 * 1024) }
                }
            }
            if (!temp.renameTo(output)) error("Unable to atomically install encrypted backup")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Decrypts atomically: authentication failure can never leave plaintext at the requested path. */
    fun decrypt(input: File, output: File, password: CharArray, maxOutputBytes: Long = 600L * 1024 * 1024) {
        require(password.isNotEmpty()) { "Backup password must not be empty" }
        require(input.isFile) { "Backup input does not exist" }
        require(input.canonicalFile != output.canonicalFile) { "Backup input and output must differ" }
        require(maxOutputBytes > 0L) { "Backup output limit must be positive" }
        val temp = File(output.parentFile ?: File("."), ".${output.name}.${java.util.UUID.randomUUID()}.part")
        try {
            DataInputStream(FileInputStream(input)).use { raw ->
                val magic = ByteArray(MAGIC.size)
                raw.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Invalid Stohn backup" }
                val iterations = raw.readInt()
                require(iterations in 100_000..1_500_000) { "Invalid backup KDF parameters" }
                val saltLength = raw.readUnsignedByte()
                require(saltLength == SALT_BYTES) { "Invalid backup salt length" }
                val salt = ByteArray(saltLength).also(raw::readFully)
                val ivLength = raw.readUnsignedByte()
                require(ivLength == IV_BYTES) { "Invalid backup IV length" }
                val iv = ByteArray(ivLength).also(raw::readFully)
                val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iv, iterations)
                temp.parentFile?.mkdirs()
                CipherInputStream(raw, cipher).use { decrypted ->
                    FileOutputStream(temp).use { outputStream ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = decrypted.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= maxOutputBytes) { "Decrypted backup exceeds safety limit" }
                            outputStream.write(buffer, 0, n)
                        }
                        outputStream.fd.sync()
                    }
                }
            }
            if (!temp.renameTo(output)) error("Unable to atomically install restored backup")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray, iterations: Int = ITERATIONS): Cipher {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
        } finally {
            key.fill(0)
        }
    }
}
