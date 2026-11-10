package org.stohncoin.wallet.wallet

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupVerifierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val password = "correct horse battery staple"

    private fun walletData(size: Int = 256): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Builds a real encrypted container exactly the way a user's exported backup is built. */
    private fun buildBackup(
        name: String,
        wallet: ByteArray,
        manifestHash: String = sha256Hex(wallet),
        labelsJson: String? = null,
        password: String = this.password
    ): File {
        val archive = temporaryFolder.newFile("$name.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            zip.write(wallet)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            zip.write(manifestHash.toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
            if (labelsJson != null) {
                zip.putNextEntry(ZipEntry(BackupContainer.ENTRY_LABELS))
                zip.write(labelsJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        val output = File(temporaryFolder.root, "$name.stohnbackup")
        BackupCrypto.encrypt(archive, output, password.toCharArray())
        archive.delete()
        return output
    }

    private fun labelsFixture(): String {
        val dir = File(temporaryFolder.root, "labels-source")
        AddressBook.save(
            dir,
            listOf(
                AddressBookLabel("SaddrAlice", "Alice"),
                AddressBookLabel("SaddrBob", "Bob")
            )
        )
        return File(dir, "labels.json").readText()
    }

    private fun workspace(): File = temporaryFolder.newFolder("workspace-${System.nanoTime()}")

    @Test
    fun `a good backup verifies and reports its contents`() {
        val wallet = walletData()
        val backup = buildBackup("good", wallet, labelsJson = labelsFixture())
        val workspace = workspace()

        val report = runBlocking {
            BackupVerifier.verify(backup, password.toCharArray(), workspace) {
                BackupVerifier.WalletCheck("sqlite", encrypted = true)
            }
        }

        assertEquals(wallet.size.toLong(), report.walletBytes)
        assertEquals(sha256Hex(wallet), report.walletSha256)
        assertEquals(2, report.labelCount)
        assertEquals("sqlite", report.walletFormat)
        assertEquals(true, report.walletEncrypted)
        assertTrue("Verification must not leave plaintext behind", workspace.listFiles().isNullOrEmpty())
    }

    @Test
    fun `verification without a wallet check still proves the container`() {
        val wallet = walletData()
        val backup = buildBackup("structure-only", wallet)

        val report = runBlocking { BackupVerifier.verify(backup, password.toCharArray(), workspace()) }

        assertEquals(sha256Hex(wallet), report.walletSha256)
        assertEquals(0, report.labelCount)
        assertEquals(null, report.walletFormat)
        assertEquals(null, report.walletEncrypted)
    }

    @Test
    fun `a wrong password fails and leaves no plaintext`() {
        val backup = buildBackup("wrong-password", walletData())
        val workspace = workspace()

        runCatching {
            runBlocking { BackupVerifier.verify(backup, "not the password".toCharArray(), workspace) }
        }.onSuccess { error("A wrong backup password was unexpectedly accepted") }

        assertTrue("Failed verification must clean up its workspace", workspace.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a tampered wallet fails the checksum`() {
        val wallet = walletData()
        val backup = buildBackup("tampered", wallet, manifestHash = sha256Hex(walletData(64)))

        runCatching {
            runBlocking { BackupVerifier.verify(backup, password.toCharArray(), workspace()) }
        }.onSuccess { error("A backup with a mismatched checksum was unexpectedly accepted") }
    }

    @Test
    fun `a rejected wallet is reported to the caller`() {
        val backup = buildBackup("not-a-wallet", walletData())

        runCatching {
            runBlocking {
                BackupVerifier.verify(backup, password.toCharArray(), workspace()) {
                    error("Backup does not contain a Stohn Core wallet")
                }
            }
        }.onFailure { failure ->
            assertTrue(failure.message.orEmpty().contains("Stohn Core wallet"))
        }.onSuccess { error("A rejected wallet was unexpectedly accepted") }
    }

    @Test
    fun `verification never writes outside its workspace`() {
        val wallet = walletData()
        val backup = buildBackup("isolated", wallet)
        val workspace = workspace()
        val before = temporaryFolder.root.listFiles()?.map { it.name }?.toSet().orEmpty()

        runBlocking { BackupVerifier.verify(backup, password.toCharArray(), workspace) }

        val after = temporaryFolder.root.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals("Verification must not create files next to the backup", before, after)
        assertTrue("Verification must not leave files in its workspace", workspace.listFiles().isNullOrEmpty())
    }


    @Test
    fun `a current backup reports the wallet details recorded in its manifest`() {
        val wallet = walletData()
        val sha = sha256Hex(wallet)
        val backup = buildBackup(
            "with-manifest",
            wallet,
            manifestHash = BackupManifest.render("0.28.2", "stohn-wallet", 4242L, sha)
        )

        val report = runBlocking { BackupVerifier.verify(backup, password.toCharArray(), workspace()) }

        assertEquals("0.28.2", report.appVersion)
        assertEquals("stohn-wallet", report.walletName)
        assertEquals(4242L, report.creationHeight)
        assertEquals(sha, report.walletSha256)
    }

    @Test
    fun `a missing backup is refused before a workspace is made`() {
        val absent = File(temporaryFolder.root, "never-written.stohnbackup")
        val workspace = File(temporaryFolder.root, "workspace-${System.nanoTime()}")

        val failure = runCatching {
            runBlocking { BackupVerifier.verify(absent, password.toCharArray(), workspace) }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Backup file does not exist"))
        assertFalse("a refused backup left a workspace behind", workspace.exists())
    }

    @Test
    fun `an empty password is refused instead of producing an unreadable container`() {
        val backup = buildBackup("empty-password", walletData())
        val workspace = workspace()

        val failure = runCatching {
            runBlocking { BackupVerifier.verify(backup, CharArray(0), workspace) }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Backup password is required"))
        assertTrue("a refused backup left files behind", workspace.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a non-positive wallet limit is refused rather than treated as unlimited`() {
        val backup = buildBackup("zero-limit", walletData())
        val workspace = workspace()

        for (limit in listOf(0L, -1L)) {
            val failure = runCatching {
                runBlocking {
                    BackupVerifier.verify(backup, password.toCharArray(), workspace, maxWalletBytes = limit)
                }
            }.exceptionOrNull()

            assertTrue("$limit was accepted as a wallet limit", failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("Wallet size limit must be positive"))
        }
        assertTrue("a refused backup left files behind", workspace.listFiles().isNullOrEmpty())
    }

    @Test
    fun `an unusable workspace is refused before the container is opened`() {
        val backup = buildBackup("bad-workspace", walletData())
        // A regular file where the workspace should be: it is not a directory and cannot be created.
        val blocked = temporaryFolder.newFile("blocked-workspace.dat")

        val failure = runCatching {
            runBlocking { BackupVerifier.verify(backup, password.toCharArray(), blocked) }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Verification workspace is unavailable"))
        assertEquals("the container was opened anyway", 0L, blocked.length())
    }

    @Test
    fun `labels missing an address or a name are not counted`() {
        // The count is shown to the user as "N labels found in this backup"; an entry with nothing
        // to show is not a label, and counting it would overstate what the backup actually holds.
        val labels = """{"labels":[{"raw":"SaddrAlice","label":"Alice"},{"raw":"","label":"NoAddress"},{"raw":"SaddrBlank","label":""}]}"""
        val backup = buildBackup("blank-labels", walletData(), labelsJson = labels)

        val report = runBlocking {
            BackupVerifier.verify(backup, password.toCharArray(), workspace()) {
                BackupVerifier.WalletCheck("sqlite", encrypted = true)
            }
        }

        assertEquals(1, report.labelCount)
    }
}
