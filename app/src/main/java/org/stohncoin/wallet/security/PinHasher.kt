package org.stohncoin.wallet.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted, slow KDF for the app-lock PIN.
 *
 * Stored form: `pbkdf2-sha256$<iterations>$<saltBase64>$<hashBase64>`. The previous release stored
 * an unsalted SHA-256 digest of the PIN, which is trivially reversible for a 4–6 digit secret.
 * [verify] still accepts that legacy form so an existing PIN keeps working, and [isLegacy] tells the
 * caller to rewrite the record with [hash] as soon as the PIN is proven correct.
 *
 * Pure JVM on purpose: the PIN policy stays unit-testable without an Android device.
 */
object PinHasher {
    const val ITERATIONS = 210_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 1_000_000

    private const val PREFIX = "pbkdf2-sha256"
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun hash(pin: String, salt: ByteArray = randomSalt(), iterations: Int = ITERATIONS): String {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Unsupported PIN KDF iteration count" }
        require(salt.size >= SALT_BYTES) { "PIN salt is too short" }
        val derived = derive(pin, salt, iterations)
        try {
            return "$PREFIX\$$iterations\$${encode(salt)}\$${encode(derived)}"
        } finally {
            derived.fill(0)
        }
    }

    /** Constant-time verification. Returns false for malformed or unrecognised records. */
    fun verify(pin: String, stored: String?): Boolean {
        if (pin.isEmpty() || stored.isNullOrBlank()) return false
        val parsed = parse(stored)
        if (parsed != null) {
            val derived = derive(pin, parsed.salt, parsed.iterations)
            return try {
                MessageDigest.isEqual(derived, parsed.hash)
            } finally {
                derived.fill(0)
            }
        }
        if (!isLegacy(stored)) return false
        val expected = legacySha256Hex(pin).toByteArray(Charsets.US_ASCII)
        val actual = stored.trim().lowercase().toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, actual)
    }

    /** True when the record is the old unsalted SHA-256 digest and must be upgraded. */
    fun isLegacy(stored: String?): Boolean =
        !stored.isNullOrBlank() && !stored.startsWith("$PREFIX\$")

    internal fun legacySha256Hex(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private class Parsed(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(stored: String): Parsed? {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val iterations = parts[1].toIntOrNull()?.takeIf { it in MIN_ITERATIONS..MAX_ITERATIONS } ?: return null
        val salt = decode(parts[2])?.takeIf { it.size == SALT_BYTES } ?: return null
        val hash = decode(parts[3])?.takeIf { it.size == KEY_BITS / 8 } ?: return null
        return Parsed(iterations, salt, hash)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}
