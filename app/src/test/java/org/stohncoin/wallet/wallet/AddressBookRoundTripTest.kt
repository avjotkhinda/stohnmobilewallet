package org.stohncoin.wallet.wallet

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class AddressBookRoundTripTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTrip() {
        val dir = File(temporaryFolder.root, "addr")
        val labels = listOf(
            AddressBookLabel("Sabc123", "Alice"),
            AddressBookLabel("Sdef456", "Bob")
        )
        // Save
        AddressBook.save(dir, labels)
        // Verify on-disk JSON
        val written = File(dir, "labels.json").readText()
        assertTrue("should contain labels key", written.contains("\"labels\":["))
        // Load back
        val loaded = AddressBook.load(dir)
        assertEquals("loaded should have 2 labels", 2, loaded.labels.size)
        assertEquals(labels, loaded.labels)
        // labelFor
        assertEquals("Alice", loaded.labelFor("Sabc123"))
        assertEquals("Bob", loaded.labelFor("Sdef456"))
        assertEquals(null, loaded.labelFor("Szzz999"))
        // addressSet
        assertTrue("addressSet should contain both", loaded.addressSet.contains("Sabc123"))
        assertTrue("addressSet should contain both", loaded.addressSet.contains("Sdef456"))
    }

    @Test
    fun roundTripEscapesSpecialCharacters() {
        val dir = File(temporaryFolder.root, "escaped")
        val labels = listOf(
            AddressBookLabel("Sabc123", "Caf\u00e9 \"quoted\" \\ back\\slash"),
            AddressBookLabel("Sdef456", "line1\nline2\ttabbed")
        )
        AddressBook.save(dir, labels)
        val loaded = AddressBook.load(dir)
        assertEquals(labels, loaded.labels)
    }

    @Test
    fun loadMissingFileReturnsEmpty() {
        val dir = File(temporaryFolder.root, "empty")
        val loaded = AddressBook.load(dir)
        assertEquals(0, loaded.labels.size)
    }

    @Test
    fun loadBlankFileReturnsEmpty() {
        val dir = File(temporaryFolder.root, "blank")
        dir.mkdirs()
        File(dir, "labels.json").writeText("   \n  ")
        val loaded = AddressBook.load(dir)
        assertEquals(0, loaded.labels.size)
    }

    @Test
    fun saveRejectsBlankRaw() {
        val dir = File(temporaryFolder.root, "bad")
        val labels = listOf(AddressBookLabel("", "Alice"))
        var failed = false
        try {
            AddressBook.save(dir, labels)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue("should reject blank raw", failed)
    }

    @Test
    fun saveRejectsBlankLabel() {
        val dir = File(temporaryFolder.root, "bad2")
        val labels = listOf(AddressBookLabel("raw", ""))
        var failed = false
        try {
            AddressBook.save(dir, labels)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue("should reject blank label", failed)
    }
}
