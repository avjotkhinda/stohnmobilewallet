package org.stohncoin.wallet.wallet

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun staged(name: String = "staged-${System.nanoTime()}.dat"): File = File(temporaryFolder.root, name)

    private fun refusedBy(message: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        org.junit.Assert.assertTrue(
            "expected a refusal containing '$message' but got $failure",
            failure != null && failure.message.orEmpty().contains(message)
        )
    }


    /**
     * Writes a stored-only archive by hand. `ZipOutputStream` refuses to write two entries with the
     * same name, but a real archive can contain them and `ZipInputStream` will happily read both -
     * so a duplicate can only be presented by writing the bytes out directly.
     */
    private fun rawArchive(name: String, entries: List<Pair<String, ByteArray>>): File {
        val file = temporaryFolder.newFile(name)
        val buffer = java.nio.ByteBuffer.allocate(30).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        file.outputStream().use { out ->
            for ((entryName, bytes) in entries) {
                val nameBytes = entryName.toByteArray(Charsets.US_ASCII)
                val crc = java.util.zip.CRC32().apply { update(bytes) }.value
                buffer.clear()
                buffer.putInt(0x04034b50)
                buffer.putShort(20)
                buffer.putShort(0)
                buffer.putShort(0)
                buffer.putShort(0)
                buffer.putShort(0)
                buffer.putInt(crc.toInt())
                buffer.putInt(bytes.size)
                buffer.putInt(bytes.size)
                buffer.putShort(nameBytes.size.toShort())
                buffer.putShort(0)
                out.write(buffer.array())
                out.write(nameBytes)
                out.write(bytes)
            }
            // End of central directory with no entries: the reader walks the local headers anyway.
            out.write(byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18))
        }
        return file
    }

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


    @Test
    fun `restore reads the wallet details a current manifest records`() {
        val data = walletData()
        val staged = File(temporaryFolder.root, "staged.dat")
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        val archive = newArchive("manifest.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(data)
            closeEntry()
            putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            write(BackupManifest.render("0.28.2", "stohn-wallet", 98765L, sha).toByteArray(Charsets.US_ASCII))
            closeEntry()
        }

        val contents = BackupContainer.restore(archive, staged, 1024L * 1024)

        assertEquals("0.28.2", contents.manifest.appVersion)
        assertEquals("stohn-wallet", contents.manifest.walletName)
        assertEquals(98765L, contents.manifest.creationHeight)
        assertEquals(sha, contents.manifest.walletSha256)
    }

    @Test
    fun `restore refuses a manifest whose checksum is not well formed`() {
        val staged = File(temporaryFolder.root, "staged.dat")
        val archive = newArchive("badsha.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(walletData())
            closeEntry()
            putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            write(
                BackupManifest.render("0.28.2", "stohn-wallet", 1L, "0123456789abcdef".repeat(4))
                    .replace("sha256=".plus("0123456789abcdef".repeat(4)), "sha256=zz")
                    .toByteArray(Charsets.US_ASCII)
            )
            closeEntry()
        }

        runCatching { BackupContainer.restore(archive, staged, 1024L * 1024) }
            .onSuccess { error("A manifest with a malformed checksum was unexpectedly accepted") }
    }

    @Test
    fun `packageSnapshot writes exactly what restore reads back`() {
        val wallet = walletData(300)
        val snapshot = temporaryFolder.newFile("snapshot-${System.nanoTime()}.dat").apply { writeBytes(wallet) }
        val labels = temporaryFolder.newFile("labels-${System.nanoTime()}.json").apply {
            writeText("""{"labels":[{"raw":"SaddrAlice","label":"Alice"},{"raw":"SaddrBob","label":"Bob"}]}""")
        }
        val archive = File(temporaryFolder.root, "packed-${System.nanoTime()}.zip")
        val manifest = BackupManifest.render("0.28.2", "stohn-wallet", 4242L, sha256Hex(wallet))
        val target = staged()

        BackupContainer.packageSnapshot(snapshot, archive, maxWalletBytes = 4096L, manifest = manifest, labels = labels)
        val contents = BackupContainer.restore(archive, target, maxWalletBytes = 4096L)

        assertArrayEquals(wallet, target.readBytes())
        assertEquals(sha256Hex(wallet), contents.walletSha256)
        assertEquals("stohn-wallet", contents.manifest.walletName)
        assertEquals(4242L, contents.manifest.creationHeight)
        assertEquals(2, contents.labels.size)
        assertEquals("Alice", contents.labels.first { it.raw == "SaddrAlice" }.label)
    }

    @Test
    fun `packageSnapshot without a manifest records the checksum of an older backup`() {
        val wallet = walletData(64)
        val snapshot = temporaryFolder.newFile("snapshot-${System.nanoTime()}.dat").apply { writeBytes(wallet) }
        val archive = File(temporaryFolder.root, "packed-${System.nanoTime()}.zip")
        val target = staged()

        BackupContainer.packageSnapshot(snapshot, archive, maxWalletBytes = 4096L)
        val contents = BackupContainer.restore(archive, target, maxWalletBytes = 4096L)

        assertEquals(sha256Hex(wallet), contents.walletSha256)
        org.junit.Assert.assertNull(contents.manifest.walletName)
        org.junit.Assert.assertTrue(contents.labels.isEmpty())
    }

    @Test
    fun `packageSnapshot refuses a missing snapshot and a non-positive limit`() {
        val archive = File(temporaryFolder.root, "packed-${System.nanoTime()}.zip")

        refusedBy("Backup snapshot does not exist") {
            BackupContainer.packageSnapshot(File(temporaryFolder.root, "absent.dat"), archive, 1024L)
        }
        refusedBy("Backup wallet size limit must be positive") {
            BackupContainer.packageSnapshot(
                temporaryFolder.newFile("snapshot-${System.nanoTime()}.dat").apply { writeBytes(walletData(8)) },
                archive,
                0L
            )
        }
        assertFalse("a refused package left an archive behind", archive.exists())
    }

    @Test
    fun `packageSnapshot refuses a negative labels limit`() {
        val snapshot = temporaryFolder.newFile("snapshot-${System.nanoTime()}.dat").apply { writeBytes(walletData(8)) }
        val labels = temporaryFolder.newFile("labels-${System.nanoTime()}.json").apply { writeText("{}") }

        refusedBy("Invalid copy limit") {
            BackupContainer.packageSnapshot(
                snapshot,
                File(temporaryFolder.root, "packed-${System.nanoTime()}.zip"),
                maxWalletBytes = 1024L,
                labels = labels,
                maxLabelsBytes = -1L
            )
        }
    }

    @Test
    fun `restore refuses a missing archive and a non-positive limit`() {
        refusedBy("Backup archive does not exist") {
            BackupContainer.restore(File(temporaryFolder.root, "absent.zip"), staged(), 1024L)
        }
        val archive = newArchive("limit-${System.nanoTime()}.zip") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(walletData(8))
            closeEntry()
            writeSha256Entry(this, walletData(8))
        }
        refusedBy("Restore wallet size limit must be positive") {
            BackupContainer.restore(archive, staged(), 0L)
        }
    }

    @Test
    fun `restore refuses entry names that only look like a wallet`() {
        // The whitelist is what actually stops traversal: nothing but the three exact names is
        // accepted, so a directory entry ("wallet.dat/") or a path that climbs out of the archive
        // never reaches the step that writes a file.
        for (name in listOf("${BackupContainer.ENTRY_WALLET}/", "../../wallet.dat", "wallet.dat.bak", "/wallet.dat")) {
            val archive = newArchive("names-${System.nanoTime()}.zip") {
                putNextEntry(ZipEntry(name))
                closeEntry()
                writeSha256Entry(this, walletData(8))
            }
            refusedBy("Unexpected backup entry") { BackupContainer.restore(archive, staged(), 1024L) }
        }
    }

    @Test
    fun `restore refuses labels or a manifest beyond their size limit`() {
        val hugeLabels = newArchive("huge-labels-${System.nanoTime()}.zip") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(walletData(8))
            closeEntry()
            writeSha256Entry(this, walletData(8))
            putNextEntry(ZipEntry(BackupContainer.ENTRY_LABELS))
            write(ByteArray(1024 * 1024 + 1))
            closeEntry()
        }
        refusedBy("labels.json is too large") { BackupContainer.restore(hugeLabels, staged(), 1024L) }

        val hugeManifest = newArchive("huge-manifest-${System.nanoTime()}.zip") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(walletData(8))
            closeEntry()
            putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            write(ByteArray(2048) { 'a'.code.toByte() })
            closeEntry()
        }
        refusedBy("Backup manifest is too large") { BackupContainer.restore(hugeManifest, staged(), 1024L) }
    }

    @Test
    fun `restore refuses a wallet past the limit but accepts one exactly at it`() {
        val wallet = walletData(64)
        val archive = newArchive("boundary-${System.nanoTime()}.zip") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(wallet)
            closeEntry()
            writeSha256Entry(this, wallet)
        }
        val target = staged()

        refusedBy("Restored wallet exceeds safety limit") {
            BackupContainer.restore(archive, target, maxWalletBytes = wallet.size.toLong() - 1L)
        }
        // Exactly at the limit is legal: the guard must refuse one byte over, not one byte under.
        val exact = staged()
        BackupContainer.restore(archive, exact, maxWalletBytes = wallet.size.toLong())
        assertArrayEquals(wallet, exact.readBytes())
    }

    @Test
    fun `restore refuses an archive with an empty wallet or no manifest`() {
        val emptyWallet = newArchive("empty-wallet-${System.nanoTime()}.zip") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            closeEntry()
            writeSha256Entry(this, ByteArray(0))
        }
        refusedBy("Backup contains no wallet.dat") { BackupContainer.restore(emptyWallet, staged(), 1024L) }

        val noManifest = newArchive("no-manifest-${System.nanoTime()}.zip") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(walletData(8))
            closeEntry()
        }
        refusedBy("Backup manifest is missing") { BackupContainer.restore(noManifest, staged(), 1024L) }
    }

    @Test
    fun `restore refuses duplicate labels and duplicate manifests`() {
        // Two entries with the same name make the archive ambiguous: one reader takes the first, the
        // next takes the last, so which checksum or label set wins would depend on the reader.
        val wallet = walletData(64)
        val manifest = sha256Hex(wallet).toByteArray(Charsets.US_ASCII)
        val labels = """{"labels":[]}""".toByteArray(Charsets.UTF_8)

        refusedBy("duplicate labels.json entries") {
            BackupContainer.restore(
                rawArchive(
                    "duplicate-labels-${System.nanoTime()}.zip",
                    listOf(
                        BackupContainer.ENTRY_WALLET to wallet,
                        BackupContainer.ENTRY_MANIFEST to manifest,
                        BackupContainer.ENTRY_LABELS to labels,
                        BackupContainer.ENTRY_LABELS to labels
                    )
                ),
                staged(),
                4096L
            )
        }

        refusedBy("duplicate manifest entries") {
            BackupContainer.restore(
                rawArchive(
                    "duplicate-manifest-${System.nanoTime()}.zip",
                    listOf(
                        BackupContainer.ENTRY_WALLET to wallet,
                        BackupContainer.ENTRY_MANIFEST to manifest,
                        BackupContainer.ENTRY_MANIFEST to manifest
                    )
                ),
                staged(),
                4096L
            )
        }
    }
}
