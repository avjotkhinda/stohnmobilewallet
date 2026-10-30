package org.stohncoin.wallet.wallet

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class AddressBookTest {
    private val root = File.createTempFile("addressbook-test", "").apply { delete() }

    @Before fun prepare() {
        root.deleteRecursively()
        root.mkdirs()
    }

    @After fun cleanup() {
        root.deleteRecursively()
        AddressBook.reset()
    }

    @Test fun readsEmptyOnNoFile() = runBlocking {
        val book = AddressBook.initialize(root)
        assertEquals(emptyMap<String, String>(), book.labels())
        assertEquals("", book.labelFor("Sabc123"))
    }

    @Test fun persistsAndReadsAcrossInstances() = runBlocking {
        AddressBook.initialize(root)
        AddressBook(root).setLabel("Sabc123", "rent")
        assertEquals("{\"Sabc123\":\"rent\"}", File(root, AddressBook.LABELS_FILE).readText(Charsets.UTF_8))
        val book2 = AddressBook.initialize(root)
        assertEquals("rent", book2.labelFor("Sabc123"))
        assertEquals("", book2.labelFor("not-there"))
    }

    @Test fun preservesAddressCase() = runBlocking {
        val book = AddressBook(root)
        book.setLabel("SAbC123xYz", "cold storage")
        assertEquals("cold storage", book.labelFor("SAbC123xYz"))
        // Addresses are case sensitive: a different case is a different address.
        assertFalse(book.labelFor("sabc123xyz").isNotEmpty())
        assertEquals("{\"SAbC123xYz\":\"cold storage\"}", File(root, AddressBook.LABELS_FILE).readText(Charsets.UTF_8))
    }

    @Test fun overwritesExisting() = runBlocking {
        AddressBook(root).setLabel("Sabc123", "old")
        AddressBook(root).setLabel("Sabc123", "new")
        assertEquals("{\"Sabc123\":\"new\"}", File(root, AddressBook.LABELS_FILE).readText(Charsets.UTF_8))
        assertEquals("new", AddressBook(root).labelFor("Sabc123"))
    }

    @Test fun clearsBlankLabelsOnSet() = runBlocking {
        val book = AddressBook(root)
        book.setLabel("Sabc123", "rent")
        book.setLabel("Sxyz999", "tip")
        book.setLabel("Sabc123", "   ")
        assertEquals("{\"Sxyz999\":\"tip\"}", File(root, AddressBook.LABELS_FILE).readText(Charsets.UTF_8))
        assertNull(book.labels()["Sabc123"])
        assertEquals("tip", book.labelFor("Sxyz999"))
    }

    @Test fun clearLabelRemovesOnlyOneEntry() = runBlocking {
        val book = AddressBook(root)
        book.setLabel("Sabc123", "rent")
        book.setLabel("Sxyz999", "tip")
        book.clearLabel("Sabc123")
        assertEquals("{\"Sxyz999\":\"tip\"}", File(root, AddressBook.LABELS_FILE).readText(Charsets.UTF_8))
        assertEquals("", book.labelFor("Sabc123"))
    }

    @Test fun survivesCorruptFile() = runBlocking {
        File(root, AddressBook.LABELS_FILE).outputStream().use { it.write("not-json".toByteArray(Charsets.UTF_8)) }
        val book = AddressBook(root)
        assertEquals("", book.labelFor("Sabc123"))
        book.setLabel("Sabc123", "rent")
        assertEquals("{\"Sabc123\":\"rent\"}", File(root, AddressBook.LABELS_FILE).readText(Charsets.UTF_8))
        assertEquals("rent", book.labelFor("Sabc123"))
    }

    @Test fun ignoresBlankLabelsOnLoad() = runBlocking {
        val raw = "{\"Sabc123\":\"\",\"Sxyz999\":\"tip\"}"
        File(root, AddressBook.LABELS_FILE).outputStream().use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        val book = AddressBook(root)
        assertEquals("tip", book.labelFor("Sxyz999"))
        assertNull(book.labels()["Sabc123"])
    }

    @Test fun writesSortedKeysWithEscaping() = runBlocking {
        val book = AddressBook(root)
        book.setLabel("Sbbb222", "quote \"q\" and \\ slash")
        book.setLabel("Saaa111", "first")
        assertEquals(
            "{\"Saaa111\":\"first\",\"Sbbb222\":\"quote \\\"q\\\" and \\\\ slash\"}",
            File(root, AddressBook.LABELS_FILE).readText(Charsets.UTF_8)
        )
        assertEquals("quote \"q\" and \\ slash", book.labelFor("Sbbb222"))
    }
}
