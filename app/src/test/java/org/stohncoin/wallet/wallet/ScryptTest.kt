package org.stohncoin.wallet.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class ScryptTest {
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * RFC 7914 section 12. These vectors pin the Salsa20/8 core, BlockMix, ROMix and the final
     * PBKDF2 pass together, which is the only way to trust a hand-written KDF.
     */
    @Test
    fun `matches RFC 7914 vector with p greater than one`() {
        val derived = Scrypt.derive(
            password = "password".toCharArray(),
            salt = "NaCl".toByteArray(Charsets.US_ASCII),
            n = 1024, r = 8, p = 16, length = 64
        )

        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
                "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            hex(derived)
        )
    }

    @Test
    fun `matches RFC 7914 vector for the production cost parameters`() {
        val derived = Scrypt.derive(
            password = "pleaseletmein".toCharArray(),
            salt = "SodiumChloride".toByteArray(Charsets.US_ASCII),
            n = 16384, r = 8, p = 1, length = 64
        )

        assertEquals(
            "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2" +
                "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
            hex(derived)
        )
    }

    @Test
    fun `the same inputs always derive the same key`() {
        val first = Scrypt.derive("passphrase".toCharArray(), byteArrayOf(1, 2, 3), 16, 1, 1, 32)
        val second = Scrypt.derive("passphrase".toCharArray(), byteArrayOf(1, 2, 3), 16, 1, 1, 32)
        val differentSalt = Scrypt.derive("passphrase".toCharArray(), byteArrayOf(1, 2, 4), 16, 1, 1, 32)

        assertEquals(hex(first), hex(second))
        assertEquals(32, first.size)
        assertEquals(false, hex(first) == hex(differentSalt))
    }

    @Test
    fun `unsafe parameters are refused instead of allocating unbounded memory`() {
        runCatching { Scrypt.derive("passphrase".toCharArray(), ByteArray(8), n = 1000, r = 8, p = 1, length = 32) }
            .onSuccess { error("A non power-of-two N was accepted") }
        runCatching { Scrypt.derive("passphrase".toCharArray(), ByteArray(8), n = 16, r = 64, p = 1, length = 32) }
            .onSuccess { error("An oversized r was accepted") }
        runCatching { Scrypt.derive(CharArray(0), ByteArray(8), n = 16, r = 1, p = 1, length = 32) }
            .onSuccess { error("An empty password was accepted") }
    }
}
