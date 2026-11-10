package org.stohncoin.wallet.wallet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The address book's own JSON reader and writer.
 *
 * `labels.json` is parsed character by character rather than with a JSON library, which is why this
 * is worth pinning: the file is user data that survives upgrades and backups, and the two things it
 * must do are round-trip any label a user can type and refuse anything malformed instead of
 * silently reading a partial book. A label is a name the user chose - quotes, backslashes and tabs
 * in it are their business, not a corruption.
 */
class AddressBookParseTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun dir(): File = temporaryFolder.newFolder("book-${System.nanoTime()}")

    @Test
    fun `labels survive a round trip through the file`() {
        val labels = listOf(
            AddressBookLabel("SaddrAlice", "Alice"),
            AddressBookLabel("SaddrBob", "Bob's savings"),
            AddressBookLabel("SaddrEsc", "quote \" backslash \\ newline \n return \r tab \t end")
        )

        val folder = dir()
        AddressBook.save(folder, labels)
        val loaded = AddressBook.load(folder)

        assertEquals(labels, loaded.labels)
        assertEquals("quote \" backslash \\ newline \n return \r tab \t end", loaded.labelFor("SaddrEsc"))
    }

    @Test
    fun `every escape the writer emits is read back`() {
        // Each escape letter on its own, so a mistake in one branch cannot hide behind another.
        val labels = listOf(
            AddressBookLabel("SaddrQuote", "a\"b"),
            AddressBookLabel("SaddrSlash", "a\\b"),
            AddressBookLabel("SaddrNewline", "a\nb"),
            AddressBookLabel("SaddrReturn", "a\rb"),
            AddressBookLabel("SaddrTab", "a\tb")
        )

        val folder = dir()
        AddressBook.save(folder, labels)

        assertEquals(labels, AddressBook.load(folder).labels)
    }

    @Test
    fun `a missing file and a blank file are an empty book`() {
        assertTrue(AddressBook.load(dir()).labels.isEmpty())

        val blank = dir().apply { File(this, "labels.json").writeText("   ") }
        assertTrue(AddressBook.load(blank).labels.isEmpty())
    }

    @Test
    fun `values without escapes parse as written`() {
        val book = AddressBook.parse("""{"labels":[{"raw":"SaddrA","label":"Plain"},{"raw":"SaddrB","label":"Other"}]}""")

        assertEquals(listOf(AddressBookLabel("SaddrA", "Plain"), AddressBookLabel("SaddrB", "Other")), book.labels)
    }

    @Test
    fun `the addresses and their labels are exposed together`() {
        val book = AddressBook.parse("""{"labels":[{"raw":"SaddrA","label":"Plain"}]}""")

        assertEquals("Plain", book.labelFor("SaddrA"))
        assertEquals(null, book.labelFor("SaddrMissing"))
        assertEquals(setOf("SaddrA"), book.addressSet)
    }

    @Test
    fun `a file that is not the expected shape is refused`() {
        val corrupted = listOf(
            """{"labels":x""",                                     // head is not {"labels":[
            """{"labels":[x]}""",                                  // an item that is not an object
            """{"labels":[{"raw":123}]}""",                        // raw key present but no value quote
            """{"labels":[{"raw":"abc""",                          // raw value never terminates
            """{"labels":[{"raw":"abc\""",                         // escape with nothing after it
            """{"labels":[{"raw":"abc\x","label":"y"}]}""",        // unknown escape letter
            """{"labels":[{"raw":"a","labl":"b"}]}""",             // separator is not ,"label":"
            """{"labels":[{"raw":"a","label":"b"}]""",             // item never closes
            """{"labels":[{"raw":"a","label":"b"}]x"""             // nothing after the closing ]}
        )

        for (text in corrupted) {
            try {
                AddressBook.parse(text)
                throw AssertionError("a corrupted address book was accepted: $text")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("corrupted"))
            }
        }
    }

    @Test
    fun `a label value that never terminates is refused`() {
        val failure = runCatching { AddressBook.parse("""{"labels":[{"raw":"a","label":"b""") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `saving refuses an entry with nothing in it`() {
        // A label with no address has nowhere to be shown; writing it would corrupt the book on the
        // next read rather than fail here.
        val failure = runCatching {
            AddressBook.save(dir(), listOf(AddressBookLabel("", "Alice")))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Invalid address-book labels"))
    }

    @Test
    fun `raw values keep every escape the writer can emit`() {
        // Labels were covered already; the address itself is written by the same escaper, so a raw
        // value with a quote, a backslash, a newline, a carriage return and a tab must survive too.
        val text = "{\"labels\":[{\"raw\":\"a\\\"b\\\\c\\nd\\re\\tf\",\"label\":\"x\"}]}"
        val entry = AddressBook.parse(text).labels.single()

        assertEquals("a\"b\\c\nd\re\tf", entry.raw)
        assertEquals("x", entry.label)
    }

    @Test
    fun `a raw value cut off mid-escape is refused`() {
        // The backslash is the last character in the file: reading the escaped letter needs one more
        // byte than the file has.
        val failure = runCatching {
            AddressBook.parse("{\"labels\":[{\"raw\":\"ab\\")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("corrupted"))
    }

    @Test
    fun `an item whose raw key is misspelled is refused`() {
        val failure = runCatching {
            AddressBook.parse("{\"labels\":[{\"rawx\":\"a\",\"label\":\"b\"}]}")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("corrupted"))
    }

    @Test
    fun `an item that stops before its closing brace is refused`() {
        val failure = runCatching {
            AddressBook.parse("{\"labels\":[{\"raw\":\"a\",\"label\":\"b\"}")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("corrupted"))
    }

    @Test
    fun `a label with an unknown escape is refused`() {
        val failure = runCatching {
            AddressBook.parse("{\"labels\":[{\"raw\":\"a\",\"label\":\"b\\x\"}]}")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("corrupted"))
    }
}
