package org.stohncoin.wallet.wallet

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup container. Plain wallet snapshots never leave this boundary.
 *
 * Container v2 (what [encrypt] writes):
 * ```
 * magic "STOHN-BACKUP-2" | version | kdf | logN | r | p | saltLen | salt | noncePrefix | chunkSize | plaintextLength
 * chunk i        : AES-GCM(key, nonce = noncePrefix|i, plaintext = chunk, aad = header|i|0|len)
 * terminal chunk : AES-GCM(key, nonce = noncePrefix|n, plaintext = empty, aad = header|n|1|0)
 * ```
 * Chunked AEAD keeps memory bounded whatever the wallet size, and binding the header, the chunk
 * index and the chunk length into the AAD means a truncated, reordered or spliced file cannot
 * decrypt. The terminal chunk authenticates the header even for an empty payload and makes
 * "nothing follows the last chunk" a verified fact.
 *
 * The key comes from scrypt ([Scrypt]) instead of PBKDF2, so each guess also costs 32 MiB.
 *
 * Container v1 stays readable through [decrypt] so backups from earlier releases keep restoring:
 * PBKDF2-HMAC-SHA256 (600k iterations) and one AES-GCM stream.
 */
object BackupCrypto {
    private val MAGIC_V1 = "STOHN-BACKUP-1".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V2 = "STOHN-BACKUP-2".toByteArray(Charsets.US_ASCII)

    private const val VERSION_V2 = 2
    private const val KDF_SCRYPT = 1
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16

    /** scrypt N = 2^15, r = 8 costs 32 MiB per guess: hostile to attackers, affordable on a phone. */
    private const val LOG_N = 15
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1

    /** 1 MiB chunks: bounded memory, and damage is detected a chunk at a time. */
    private const val CHUNK_BYTES = 1 shl 20
    private const val MIN_CHUNK_BYTES = 64 * 1024
    private const val MAX_CHUNK_BYTES = 4 * 1024 * 1024

    /** magic + version + kdf + logN + r + p + saltLen + salt + noncePrefix + chunkSize + length. */
    private val HEADER_V2_BYTES = MAGIC_V2.size + 1 + 1 + 1 + 1 + 1 + 1 + SALT_BYTES +
        NONCE_PREFIX_BYTES + 4 + 8

    /** Writes a v2 container atomically; a failed export never leaves a partial file in place. */
    fun encrypt(input: File, output: File, password: CharArray) {
        require(password.isNotEmpty()) { "Backup password must not be empty" }
        require(input.isFile) { "Backup input does not exist" }
        require(input.canonicalFile != output.canonicalFile) { "Backup input and output must differ" }
        val length = input.length()
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(SecureRandom()::nextBytes)
        val header = headerV2(salt, noncePrefix, CHUNK_BYTES, length)
        val key = Scrypt.derive(password, salt, 1 shl LOG_N, SCRYPT_R, SCRYPT_P, KEY_BYTES)
        val temp = File(output.parentFile ?: File("."), ".${output.name}.${java.util.UUID.randomUUID()}.part")
        try {
            temp.parentFile?.mkdirs()
            DataOutputStream(FileOutputStream(temp)).use { out ->
                out.write(header)
                DataInputStream(FileInputStream(input)).use { plain ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var index = 0
                    var remaining = length
                    while (remaining > 0L) {
                        val take = minOf(remaining, CHUNK_BYTES.toLong()).toInt()
                        plain.readFully(buffer, 0, take)
                        out.write(seal(key, noncePrefix, index, header, buffer, take, isLast = false))
                        remaining -= take
                        index++
                    }
                    out.write(seal(key, noncePrefix, index, header, ByteArray(0), 0, isLast = true))
                }
            }
            if (!temp.renameTo(output)) error("Unable to atomically install encrypted backup")
        } finally {
            key.fill(0)
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
                val magic = ByteArray(MAGIC_V1.size).also(raw::readFully)
                when {
                    magic.contentEquals(MAGIC_V2) -> decryptV2(raw, password, temp, maxOutputBytes)
                    magic.contentEquals(MAGIC_V1) -> decryptV1(raw, password, temp, maxOutputBytes)
                    else -> error("Invalid Stohn backup")
                }
            }
            if (!temp.renameTo(output)) error("Unable to atomically install restored backup")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun decryptV2(raw: DataInputStream, password: CharArray, target: File, maxOutputBytes: Long) {
        // The AAD has to be byte-identical to what was written, so the header is re-read verbatim
        // and only then parsed.
        val rest = ByteArray(HEADER_V2_BYTES - MAGIC_V2.size).also(raw::readFully)
        val header = MAGIC_V2 + rest
        val fields = ByteBuffer.wrap(rest)
        val version = fields.get().toInt() and 0xFF
        require(version == VERSION_V2) { "Unsupported Stohn backup version $version" }
        val kdf = fields.get().toInt() and 0xFF
        require(kdf == KDF_SCRYPT) { "Unsupported Stohn backup key derivation" }
        val logN = fields.get().toInt() and 0xFF
        require(logN in 14..18) { "Invalid backup KDF parameters" }
        val r = fields.get().toInt() and 0xFF
        require(r in 1..16) { "Invalid backup KDF parameters" }
        val p = fields.get().toInt() and 0xFF
        require(p in 1..4) { "Invalid backup KDF parameters" }
        val saltLength = fields.get().toInt() and 0xFF
        require(saltLength == SALT_BYTES) { "Invalid backup salt length" }
        val salt = ByteArray(saltLength).also { fields.get(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also { fields.get(it) }
        val chunkBytes = fields.int
        require(chunkBytes in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) { "Invalid backup chunk size" }
        val length = fields.long
        require(length in 0L..maxOutputBytes) { "Decrypted backup exceeds safety limit" }

        val key = Scrypt.derive(password, salt, 1 shl logN, r, p, KEY_BYTES)
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(chunkBytes + TAG_BYTES)
                var index = 0
                var remaining = length
                while (remaining > 0L) {
                    val take = minOf(remaining, chunkBytes.toLong()).toInt()
                    raw.readFully(buffer, 0, take + TAG_BYTES)
                    out.write(open(key, noncePrefix, index, header, buffer, take + TAG_BYTES, take, isLast = false))
                    remaining -= take
                    index++
                }
                val terminal = ByteArray(TAG_BYTES).also(raw::readFully)
                open(key, noncePrefix, index, header, terminal, TAG_BYTES, 0, isLast = true)
                require(raw.read() == -1) { "Backup contains unexpected trailing data" }
                out.fd.sync()
            }
        } finally {
            key.fill(0)
        }
    }

    /** Reads a backup written by an earlier release; the single-stream format is kept intact. */
    private fun decryptV1(raw: DataInputStream, password: CharArray, target: File, maxOutputBytes: Long) {
        val iterations = raw.readInt()
        require(iterations in 100_000..1_500_000) { "Invalid backup KDF parameters" }
        val saltLength = raw.readUnsignedByte()
        require(saltLength == SALT_BYTES) { "Invalid backup salt length" }
        val salt = ByteArray(saltLength).also(raw::readFully)
        val ivLength = raw.readUnsignedByte()
        require(ivLength == IV_BYTES) { "Invalid backup IV length" }
        val iv = ByteArray(ivLength).also(raw::readFully)
        val cipher = legacyCipher(Cipher.DECRYPT_MODE, password, salt, iv, iterations)
        target.parentFile?.mkdirs()
        CipherInputStream(raw, cipher).use { decrypted ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = decrypted.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= maxOutputBytes) { "Decrypted backup exceeds safety limit" }
                    out.write(buffer, 0, n)
                }
                out.fd.sync()
            }
        }
    }

    private fun headerV2(salt: ByteArray, noncePrefix: ByteArray, chunkBytes: Int, length: Long): ByteArray =
        ByteBuffer.allocate(HEADER_V2_BYTES)
            .put(MAGIC_V2)
            .put(VERSION_V2.toByte())
            .put(KDF_SCRYPT.toByte())
            .put(LOG_N.toByte())
            .put(SCRYPT_R.toByte())
            .put(SCRYPT_P.toByte())
            .put(SALT_BYTES.toByte())
            .put(salt)
            .put(noncePrefix)
            .putInt(chunkBytes)
            .putLong(length)
            .array()

    private fun seal(
        key: ByteArray,
        noncePrefix: ByteArray,
        index: Int,
        header: ByteArray,
        data: ByteArray,
        length: Int,
        isLast: Boolean
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(noncePrefix, index)))
        cipher.updateAAD(aad(header, index, isLast, length))
        return cipher.doFinal(data, 0, length)
    }

    private fun open(
        key: ByteArray,
        noncePrefix: ByteArray,
        index: Int,
        header: ByteArray,
        cipherText: ByteArray,
        cipherLength: Int,
        plainLength: Int,
        isLast: Boolean
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(noncePrefix, index)))
        cipher.updateAAD(aad(header, index, isLast, plainLength))
        return try {
            cipher.doFinal(cipherText, 0, cipherLength)
        } catch (t: GeneralSecurityException) {
            throw SecurityException("Backup could not be authenticated: wrong password or damaged file", t)
        }
    }

    private fun aad(header: ByteArray, index: Int, isLast: Boolean, length: Int): ByteArray =
        ByteBuffer.allocate(header.size + 9)
            .put(header)
            .putInt(index)
            .put(if (isLast) 1 else 0)
            .putInt(length)
            .array()

    private fun nonce(noncePrefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(IV_BYTES).put(noncePrefix).putInt(index).array()

    private fun legacyCipher(
        mode: Int,
        password: CharArray,
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int
    ): Cipher {
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
