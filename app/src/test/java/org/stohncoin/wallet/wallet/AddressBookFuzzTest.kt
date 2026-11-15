package org.stohncoin.wallet.wallet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AddressBookFuzzTest {
    @Test
    fun parseNeverThrowsOnRandomInput() {
        val rnd = Random(0xC0FFEE)
        repeat(2000) {
            val len = rnd.nextInt(120)
            val sb = StringBuilder(len)
            repeat(len) { sb.append((rnd.nextInt(95) + 32).toChar()) }
            try {
                AddressBook.parse(sb.toString())
            } catch (e: Exception) {
                // Only documented corruption errors are acceptable; no crash/oom.
                assertTrue(e is IllegalArgumentException || e is IllegalStateException)
            }
        }
    }

    @Test
    fun loadNeverThrowsOnRandomFile() {
        val rnd = Random(0xBEEF)
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "fuzz-${System.nanoTime()}")
        dir.mkdirs()
        repeat(500) {
            val len = rnd.nextInt(256)
            val bytes = ByteArray(len) { (rnd.nextInt(256)).toByte() }
            java.io.File(dir, "labels.json").writeBytes(bytes)
            // Must not throw.
            AddressBook.load(dir)
        }
        dir.deleteRecursively()
    }
}
