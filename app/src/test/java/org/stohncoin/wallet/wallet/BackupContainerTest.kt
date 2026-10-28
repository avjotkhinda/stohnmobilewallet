package org.stohncoin.wallet.wallet

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupContainerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newArchive(name: String, build: ZipOutputStream.() -> Unit): File {
        val archive = temporaryFolder.newFile(name)
        ZipOutputStream(FileOutputStream(archive)).use { it.build() }
        return archive
    }

    private fun walletData(size: Int = 128): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun writeSha256Entry(zip: ZipOutputStream, data: ByteArray) {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
        zip.write(md.digest(data).joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII))
        zip.closeEntry()
    }

    @Test
    fun `restore accepts a well-formed backup`() {
        val data = walletData()
        val staged = File(temporaryFolder.root, "staged.dat")
        val archive = newArchive("good.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(data)
            closeEntry()
            writeSha256Entry(this, data)
        }
        val contents = BackupContainer.restore(archive, staged, 1024L * 1024)
        assertTrue(staged.length() > 0L)
        assertEquals(data.size.toLong(), staged.length())
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }.let { assertEquals(it, contents.walletSha256) }
    }

    @Test
    fun `restore rejects traversal entry names`() {
        val staged = File(temporaryFolder.root, "staged.dat")
        val archive = newArchive("traversal.backup") {
            putNextEntry(ZipEntry("../escape.dat"))
            write(walletData())
            closeEntry()
        }
        runCatching { BackupContainer.restore(archive, staged, 1024L * 1024) }
            .onSuccess { error("Traversal entry was unexpectedly accepted") }
    }

    @Test
    fun `restore rejects unexpected entry names`() {
        val staged = File(temporaryFolder.root, "staged.dat")
        val archive = newArchive("wrong-name.backup") {
            putNextEntry(ZipEntry("not-a-wallet.dat"))
            write(walletData())
            closeEntry()
        }
        runCatching { BackupContainer.restore(archive, staged, 1024L * 1024) }
            .onSuccess { error("Unexpected entry was unexpectedly accepted") }
    }

    @Test
    fun `restore rejects duplicate wallet entries`() {
        val staged = File(temporaryFolder.root, "staged.dat")
        val data = walletData()
        val base = newArchive("dup-base.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(data)
            closeEntry()
            putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            write("0123456789abcdef".toByteArray())
            closeEntry()
        }
        // ZipOutputStream refuses a second entry with the same name, so splice the wallet
        // entry's raw local-header block in front of the manifest entry to build a stream
        // with duplicate sequential entries (what a crafted restore input would look like).
        val bytes = base.readBytes()
        val sig = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val first = indexOf(bytes, sig, 0)
        val second = indexOf(bytes, sig, first + 4)

        val spliced = temporaryFolder.newFile("dupe.backup")
        FileOutputStream(spliced).use {
            it.write(bytes, first, second - first) // wallet.dat local entry block
            it.write(bytes)
        }
        runCatching { BackupContainer.restore(spliced, staged, 1024L * 1024) }
            .onSuccess { error("Duplicate wallet entry was unexpectedly accepted") }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        for (i in from..haystack.size - needle.size) {
            if (needle.indices.all { haystack[i + it] == needle[it] }) return i
        }
        error("local header signature not found")
    }

    @Test
    fun `restore rejects an archive with no wallet`() {
        val staged = File(temporaryFolder.root, "staged.dat")
        val archive = newArchive("empty.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            write("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".toByteArray())
            closeEntry()
        }
        runCatching { BackupContainer.restore(archive, staged, 1024L * 1024) }
            .onSuccess { error("Manifest-only archive was unexpectedly accepted") }
    }

    @Test
    fun `restore rejects an oversized wallet entry`() {
        val staged = File(temporaryFolder.root, "staged.dat")
        val archive = newArchive("oversized.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(ByteArray(2048))
            closeEntry()
        }
        runCatching { BackupContainer.restore(archive, staged, 1024L) }
            .onSuccess { error("Oversized entry was unexpectedly accepted") }
    }

    @Test
    fun `restore rejects a checksum mismatch`() {
        val staged = File(temporaryFolder.root, "staged.dat")
        val archive = newArchive("mismatch.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(walletData())
            closeEntry()
            putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            write("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".toByteArray())
            closeEntry()
        }
        runCatching { BackupContainer.restore(archive, staged, 1024L * 1024) }
            .onSuccess { error("Checksum mismatch was unexpectedly accepted") }
    }

    @Test
    fun `restore rejects an invalid manifest format`() {
        val staged = File(temporaryFolder.root, "staged.dat")
        val archive = newArchive("badmanifest.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(walletData())
            closeEntry()
            putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            write("not-a-hash".toByteArray())
            closeEntry()
        }
        runCatching { BackupContainer.restore(archive, staged, 1024L * 1024) }
            .onSuccess { error("Invalid manifest was unexpectedly accepted") }
    }
}
