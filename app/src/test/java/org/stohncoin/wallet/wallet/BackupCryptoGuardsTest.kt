package org.stohncoin.wallet.wallet

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The refusals in [BackupCrypto] that the happy-path tests never reach.
 *
 * A backup file arrives from wherever the user kept it, so every field in its header is hostile
 * input until proven otherwise. These are the guards that make an attacker-supplied container fail
 * on its own terms - an absurd KDF cost that would hang the app, a chunk size that would allocate
 * gigabytes, a length field that would fill the disk - instead of being handed to scrypt or to an
 * allocation. The atomic-install guards are here too: a backup that cannot be renamed into place
 * must fail loudly and leave no `.part` file behind.
 */
class BackupCryptoGuardsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val password = "correct horse battery staple"

    // v2 header: magic(14) version kdf logN r p saltLen salt(16) nonce(8) chunkSize(4) length(8).
    private val versionOffset = 14
    private val kdfOffset = 15
    private val logNOffset = 16
    private val rOffset = 17
    private val pOffset = 18
    private val saltLengthOffset = 19
    private val chunkSizeOffset = 44
    private val lengthOffset = 48

    private fun encryptedBytes(size: Int = 4096): ByteArray {
        val plaintext = temporaryFolder.newFile("plain-${System.nanoTime()}.dat").apply {
            writeBytes(ByteArray(size) { i -> (i * 31 % 251).toByte() })
        }
        val output = File(temporaryFolder.root, "container-${System.nanoTime()}.backup")
        BackupCrypto.encrypt(plaintext, output, password.toCharArray())
        return output.readBytes()
    }

    private fun patchedByte(bytes: ByteArray, offset: Int, value: Int): ByteArray =
        bytes.copyOf().also { it[offset] = value.toByte() }

    private fun patchedInt(bytes: ByteArray, offset: Int, value: Int): ByteArray =
        bytes.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }

    private fun patchedLong(bytes: ByteArray, offset: Int, value: Long): ByteArray =
        bytes.copyOf().also { ByteBuffer.wrap(it).putLong(offset, value) }

    private fun container(bytes: ByteArray): File =
        temporaryFolder.newFile("input-${System.nanoTime()}.backup").apply { writeBytes(bytes) }

    private fun destination(): File = File(temporaryFolder.root, "restored-${System.nanoTime()}.dat")

    /** Decrypts [bytes] and requires the refusal to mention [message], with nothing installed. */
    private fun expectRefused(bytes: ByteArray, message: String, maxOutputBytes: Long? = null) {
        val target = destination()
        val failure = runCatching {
            val password = password.toCharArray()
            if (maxOutputBytes == null) {
                BackupCrypto.decrypt(container(bytes), target, password)
            } else {
                BackupCrypto.decrypt(container(bytes), target, password, maxOutputBytes)
            }
        }.exceptionOrNull()

        assertTrue(
            "expected a refusal containing '$message' but got $failure",
            failure != null && failure.message.orEmpty().contains(message)
        )
        assertFalse("a refused container installed plaintext", target.exists())
    }

    @Test
    fun `a container with the wrong magic is not a backup`() {
        val bytes = encryptedBytes()
        val forged = "NOPE-BACKUP-2".toByteArray(Charsets.US_ASCII).copyOf(14) + bytes.copyOfRange(14, bytes.size)

        expectRefused(forged, "Invalid Stohn backup")
    }

    @Test
    fun `a future container version is refused rather than guessed at`() {
        expectRefused(patchedByte(encryptedBytes(), versionOffset, 3), "Unsupported Stohn backup version 3")
    }

    @Test
    fun `an unknown key derivation is refused`() {
        expectRefused(patchedByte(encryptedBytes(), kdfOffset, 2), "Unsupported Stohn backup key derivation")
    }

    @Test
    fun `a KDF cost outside the accepted range is refused before scrypt runs`() {
        // Below the floor weakens every future guess; above it turns one wrong password into a
        // multi-second, multi-gigabyte allocation the attacker chose.
        for (logN in listOf(13, 19)) {
            expectRefused(patchedByte(encryptedBytes(), logNOffset, logN), "Invalid backup KDF parameters")
        }
    }

    @Test
    fun `scrypt block size and parallelism are bounded`() {
        for (r in listOf(0, 17)) {
            expectRefused(patchedByte(encryptedBytes(), rOffset, r), "Invalid backup KDF parameters")
        }
        for (p in listOf(0, 5)) {
            expectRefused(patchedByte(encryptedBytes(), pOffset, p), "Invalid backup KDF parameters")
        }
    }

    @Test
    fun `a salt that is not the expected size is refused`() {
        expectRefused(patchedByte(encryptedBytes(), saltLengthOffset, 8), "Invalid backup salt length")
    }

    @Test
    fun `a chunk size that would allocate unreasonable memory is refused`() {
        val bytes = encryptedBytes()
        expectRefused(patchedInt(bytes, chunkSizeOffset, 64 * 1024 - 1), "Invalid backup chunk size")
        expectRefused(patchedInt(bytes, chunkSizeOffset, 4 * 1024 * 1024 + 1), "Invalid backup chunk size")
    }

    @Test
    fun `a declared length beyond the caller limit is refused before decryption`() {
        val bytes = patchedLong(encryptedBytes(), lengthOffset, 4096L + 1L)

        expectRefused(bytes, "Decrypted backup exceeds safety limit", maxOutputBytes = 4096L)
    }

    @Test
    fun `encryption refuses an empty password, a missing input and writing over its own input`() {
        val input = temporaryFolder.newFile("input-${System.nanoTime()}.dat").apply { writeBytes(ByteArray(16)) }
        val output = File(temporaryFolder.root, "out-${System.nanoTime()}.backup")

        fun refused(block: () -> Unit): String? = runCatching(block).exceptionOrNull()?.message

        assertTrue(refused { BackupCrypto.encrypt(input, output, CharArray(0)) }!!.contains("password must not be empty"))
        assertTrue(
            refused {
                BackupCrypto.encrypt(File(temporaryFolder.root, "absent.dat"), output, password.toCharArray())
            }!!.contains("input does not exist")
        )
        assertTrue(
            refused { BackupCrypto.encrypt(input, input, password.toCharArray()) }!!.contains("must differ")
        )
        assertFalse("a refused export left a backup behind", output.exists())
    }

    @Test
    fun `decryption refuses an empty password, a missing input, its own input and a zero limit`() {
        val input = container(encryptedBytes())

        fun refused(block: () -> Unit): String? = runCatching(block).exceptionOrNull()?.message

        assertTrue(
            refused { BackupCrypto.decrypt(input, destination(), CharArray(0)) }!!.contains("password must not be empty")
        )
        assertTrue(
            refused {
                BackupCrypto.decrypt(File(temporaryFolder.root, "absent.backup"), destination(), password.toCharArray())
            }!!.contains("input does not exist")
        )
        assertTrue(
            refused { BackupCrypto.decrypt(input, input, password.toCharArray()) }!!.contains("must differ")
        )
        assertTrue(
            refused {
                BackupCrypto.decrypt(input, destination(), password.toCharArray(), maxOutputBytes = 0L)
            }!!.contains("output limit must be positive")
        )
    }

    @Test
    fun `an export that cannot be renamed into place fails and leaves no partial file`() {
        val input = temporaryFolder.newFile("input-${System.nanoTime()}.dat").apply { writeBytes(ByteArray(64)) }
        // A directory at the output path makes the final rename impossible.
        val blocked = temporaryFolder.newFolder("blocked-${System.nanoTime()}")

        val failure = runCatching { BackupCrypto.encrypt(input, blocked, password.toCharArray()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Unable to atomically install encrypted backup"))
        assertNoPartFiles(temporaryFolder.root)
    }

    @Test
    fun `a restore that cannot be renamed into place fails and leaves no partial file`() {
        val input = container(encryptedBytes())
        val blocked = temporaryFolder.newFolder("blocked-${System.nanoTime()}")

        val failure = runCatching { BackupCrypto.decrypt(input, blocked, password.toCharArray()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Unable to atomically install restored backup"))
        assertNoPartFiles(temporaryFolder.root)
    }

    @Test
    fun `a legacy container with an impossible KDF cost is refused`() {
        for (iterations in listOf(99_999, 1_500_001)) {
            val legacy = File(temporaryFolder.root, "legacy-${System.nanoTime()}.backup")
            writeLegacyV1(legacy, iterations = iterations)
            val target = destination()

            val failure = runCatching { BackupCrypto.decrypt(legacy, target, password.toCharArray()) }.exceptionOrNull()

            assertTrue(
                "$iterations was accepted as a legacy iteration count",
                failure != null && failure.message.orEmpty().contains("Invalid backup KDF parameters")
            )
            assertFalse(target.exists())
        }
    }

    @Test
    fun `a legacy container with the wrong salt or IV length is refused`() {
        val shortSalt = File(temporaryFolder.root, "legacy-salt-${System.nanoTime()}.backup")
        writeLegacyV1(shortSalt, saltLength = 15)
        val saltFailure = runCatching { BackupCrypto.decrypt(shortSalt, destination(), password.toCharArray()) }
            .exceptionOrNull()
        assertTrue(saltFailure!!.message!!.contains("Invalid backup salt length"))

        val shortIv = File(temporaryFolder.root, "legacy-iv-${System.nanoTime()}.backup")
        writeLegacyV1(shortIv, ivLength = 11)
        val ivFailure = runCatching { BackupCrypto.decrypt(shortIv, destination(), password.toCharArray()) }
            .exceptionOrNull()
        assertTrue(ivFailure!!.message!!.contains("Invalid backup IV length"))
    }

    @Test
    fun `a legacy container is held to the caller output limit as it is read`() {
        // The legacy format has no length field, so the only defence against a decompression bomb
        // is the running total checked while the stream is written out.
        val legacy = File(temporaryFolder.root, "legacy-big-${System.nanoTime()}.backup")
        writeLegacyV1(legacy, plaintext = ByteArray(4096))
        val target = destination()

        val failure = runCatching {
            BackupCrypto.decrypt(legacy, target, password.toCharArray(), maxOutputBytes = 1024L)
        }.exceptionOrNull()

        assertTrue(failure != null && failure.message.orEmpty().contains("Decrypted backup exceeds safety limit"))
        assertFalse(target.exists())
    }

    private fun assertNoPartFiles(dir: File) {
        val leftovers = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertEquals("a failed operation left a partial file: $leftovers", emptyList<File>(), leftovers)
    }

    /** Writes the pre-v2 format with chosen header fields, so a bad header can be presented. */
    private fun writeLegacyV1(
        target: File,
        iterations: Int = 600_000,
        saltLength: Int = 16,
        ivLength: Int = 12,
        plaintext: ByteArray = ByteArray(64)
    ) {
        val salt = ByteArray(saltLength).also(SecureRandom()::nextBytes)
        val iv = ByteArray(ivLength).also(SecureRandom()::nextBytes)
        val spec = PBEKeySpec(password.toCharArray(), salt.copyOf(16), 600_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv.copyOf(12)))
            }
        } finally {
            key.fill(0)
        }
        DataOutputStream(FileOutputStream(target)).use { out ->
            out.write("STOHN-BACKUP-1".toByteArray(Charsets.US_ASCII))
            out.writeInt(iterations)
            out.writeByte(saltLength)
            out.write(salt)
            out.writeByte(ivLength)
            out.write(iv)
            CipherOutputStream(out, cipher).use { it.write(plaintext) }
        }
    }

    @Test
    fun `a bare file name still round trips instead of failing on a missing parent`() {
        // File.parentFile is null for a bare name. The app always passes app-private paths, but the
        // container must fall back to the working directory rather than throw where it writes its
        // temporary file, because that would leave a backup that can never be opened.
        val plaintext = temporaryFolder.newFile("relative-plain.dat").apply { writeText("wallet bytes") }
        val encrypted = File("relative-${System.nanoTime()}.backup")
        val restored = File("relative-restored-${System.nanoTime()}.dat")
        try {
            BackupCrypto.encrypt(plaintext, encrypted, password.toCharArray())
            assertTrue("the container was not written", encrypted.isFile)

            BackupCrypto.decrypt(encrypted, restored, password.toCharArray())
            assertEquals("wallet bytes", restored.readText())
        } finally {
            encrypted.delete()
            restored.delete()
        }
    }
}
