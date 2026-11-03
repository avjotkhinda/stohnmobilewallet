package org.stohncoin.wallet.wallet

import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Property tests for [BackupContainer.restore] entry handling: the archive may name only the
 * three entries the writer produces, and anything else — traversal, absolute paths, directory
 * entries, random bytes as a name — must be refused before a single byte reaches the staged
 * wallet. Cases are generated from a fixed seed so a failure can be replayed.
 */
class BackupContainerFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val seed = 20261103L
    private val limit = 1024L * 1024

    private val allowlist = setOf(
        BackupContainer.ENTRY_WALLET,
        BackupContainer.ENTRY_MANIFEST,
        BackupContainer.ENTRY_LABELS
    )

    private fun newArchive(name: String, build: ZipOutputStream.() -> Unit): File {
        val archive = temporaryFolder.newFile(name)
        ZipOutputStream(FileOutputStream(archive)).use { it.build() }
        return archive
    }

    private fun walletData(size: Int = 128): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun manifestFor(data: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
    }

    /** A name the writer would never produce, generated from traversal-shaped fragments. */
    private fun randomEntryName(rng: Random): String {
        val fragments = listOf(
            "..", ".", "wallet.dat", "labels.json", "MANIFEST.sha256", "x", "sub",
            "..", "/etc/passwd", "C:", " ", "%2e%2e"
        )
        val separators = listOf("/", "//", "\\", "", "/")
        val count = 1 + rng.nextInt(4)
        val out = StringBuilder()
        for (i in 0 until count) {
            out.append(fragments[rng.nextInt(fragments.size)])
            if (i != count - 1) out.append(separators[rng.nextInt(separators.size)])
        }
        val name = out.toString()
        return if (name.replace('\\', '/') in allowlist || name in allowlist) randomEntryName(rng) else name
    }

    @Test
    fun `no randomly generated entry name is ever accepted`() {
        val rng = Random(seed)
        var refused = 0
        repeat(250) { i ->
            val name = randomEntryName(rng)
            val staged = File(temporaryFolder.root, "staged-$i.dat")
            val archive = newArchive("fuzz-$i.backup") {
                putNextEntry(ZipEntry(name))
                write(byteArrayOf(1, 2, 3, 4))
                closeEntry()
                putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
                write(manifestFor(walletData()))
                closeEntry()
            }
            val failure = runCatching { BackupContainer.restore(archive, staged, limit) }.exceptionOrNull()
            assertNotNull("seed=$seed i=$i name='$name' was accepted", failure)
            assertTrue(
                "seed=$seed i=$i name='$name' threw ${failure?.javaClass?.simpleName}: ${failure?.message}",
                failure is IllegalArgumentException
            )
            assertTrue(
                "seed=$seed i=$i name='$name' wrote to the staged wallet",
                !staged.exists() || staged.length() == 0L
            )
            refused++
        }
        assertTrue("nothing was exercised", refused == 250)
    }

    @Test
    fun `spelled-out variants of a real entry name are refused`() {
        val variants = listOf(
            "./wallet.dat", "wallet.dat/", "wallet.dat ", " wallet.dat", "WALLET.DAT",
            "/wallet.dat", "sub/wallet.dat", "\\wallet.dat", "..\\wallet.dat",
            "wallet.dat\\", "dir/", "../MANIFEST.sha256", "labels.json.bak"
        )
        for ((i, name) in variants.withIndex()) {
            val staged = File(temporaryFolder.root, "variant-$i.dat")
            val archive = newArchive("variant-$i.backup") {
                putNextEntry(ZipEntry(name))
                write(walletData())
                closeEntry()
            }
            val failure = runCatching { BackupContainer.restore(archive, staged, limit) }.exceptionOrNull()
            assertNotNull("entry '$name' was accepted", failure)
            assertTrue(
                "entry '$name' threw ${failure?.javaClass?.simpleName}: ${failure?.message}",
                failure is IllegalArgumentException
            )
            assertTrue(
                "entry '$name' wrote to the staged wallet",
                !staged.exists() || staged.length() == 0L
            )
        }
    }

    @Test
    fun `a valid backup with one junk entry inserted anywhere is refused`() {
        val data = walletData()
        val junk = listOf("../escape.dat", "/etc/passwd", "notes.txt", "dir/", "a\\b")
        for (position in 0..3) {
            for ((j, bad) in junk.withIndex()) {
                val staged = File(temporaryFolder.root, "pos-$position-$j.dat")
                val archive = newArchive("pos-$position-$j.backup") {
                    val entries = mutableListOf(
                        ZipEntry(BackupContainer.ENTRY_WALLET) to data,
                        ZipEntry(BackupContainer.ENTRY_MANIFEST) to manifestFor(data),
                        ZipEntry(BackupContainer.ENTRY_LABELS) to "{\"labels\":[]}".toByteArray()
                    )
                    entries.add(position, ZipEntry(bad) to byteArrayOf(9))
                    for ((entry, payload) in entries) {
                        putNextEntry(entry)
                        write(payload)
                        closeEntry()
                    }
                }
                val failure = runCatching { BackupContainer.restore(archive, staged, limit) }.exceptionOrNull()
                assertNotNull("position=$position junk='$bad' was accepted", failure)
                assertTrue(
                    "position=$position junk='$bad' threw ${failure?.javaClass?.simpleName}: ${failure?.message}",
                    failure is IllegalArgumentException
                )
                if (position == 0) {
                    assertTrue(
                        "position=$position junk='$bad' wrote to the staged wallet",
                        !staged.exists() || staged.length() == 0L
                    )
                }
            }
        }
    }

    @Test
    fun `the untouched well-formed backup is still accepted after the refusals`() {
        val data = walletData()
        val staged = File(temporaryFolder.root, "staged-good.dat")
        val archive = newArchive("good.backup") {
            putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            write(data)
            closeEntry()
            putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            write(manifestFor(data))
            closeEntry()
            putNextEntry(ZipEntry(BackupContainer.ENTRY_LABELS))
            write("{\"labels\":[]}".toByteArray())
            closeEntry()
        }
        val contents = BackupContainer.restore(archive, staged, limit)
        assertTrue(staged.length() == data.size.toLong())
        assertTrue(contents.labels.isEmpty())
    }
}