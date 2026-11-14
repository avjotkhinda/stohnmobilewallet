package org.stohncoin.wallet.wallet

import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddressBookEncryptionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun key(): javax.crypto.SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256)
        return gen.generateKey()
    }

    private fun dir(): File = temporaryFolder.newFolder("enc-${System.nanoTime()}")

    @Test
    fun `an encrypted book round trips through the file`() {
        val labels = listOf(
            AddressBookLabel("SaddrAlice", "Alice"),
            AddressBookLabel("SaddrBob", "Bob's savings")
        )
        val folder = dir()
        val k = key()
        AddressBook.save(folder, labels, k)
        // On-disk bytes must not be readable plaintext.
        val raw = File(folder, "labels.json").readBytes()
        assertTrue("encrypted file must not contain plaintext JSON", !raw.toString(Charsets.UTF_8).contains("\"labels\""))
        val loaded = AddressBook.load(folder, k)
        assertEquals(labels, loaded.labels)
    }

    @Test
    fun `a tampered encrypted file is refused`() {
        val folder = dir()
        val k = key()
        AddressBook.save(folder, listOf(AddressBookLabel("SaddrA", "Plain")), k)
        val file = File(folder, "labels.json")
        val bytes = file.readBytes()
        // Flip a byte in the ciphertext region.
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
        assertTrue(AddressBook.load(folder, k).labels.isEmpty())
    }

    @Test
    fun `a wrong key yields an empty book rather than crashing`() {
        val folder = dir()
        val k1 = key()
        val k2 = key()
        AddressBook.save(folder, listOf(AddressBookLabel("SaddrA", "Plain")), k1)
        assertTrue(AddressBook.load(folder, k2).labels.isEmpty())
    }

    @Test
    fun `a legacy plaintext file is still readable and migrated`() {
        val folder = dir()
        // Write a legacy plaintext book by hand.
        File(folder, "labels.json").writeText("""{"labels":[{"raw":"SaddrA","label":"Plain"}]}""")
        val k = key()
        val loaded = AddressBook.load(folder, k)
        assertEquals(listOf(AddressBookLabel("SaddrA", "Plain")), loaded.labels)
        // Migration should have rewritten the file as ciphertext.
        val raw = File(folder, "labels.json").readBytes()
        assertTrue("migrated file must not be plaintext", !raw.toString(Charsets.UTF_8).contains("\"labels\""))
        // And it must still load.
        assertEquals(listOf(AddressBookLabel("SaddrA", "Plain")), AddressBook.load(folder, k).labels)
    }
}
