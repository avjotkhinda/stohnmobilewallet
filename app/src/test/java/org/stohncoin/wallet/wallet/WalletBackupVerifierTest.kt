package org.stohncoin.wallet.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * The Context-backed test-restore: the part [BackupVerifier] deliberately leaves to its caller.
 *
 * [BackupVerifier] is already covered with a workspace chosen by the test; what is untested here is
 * the choice the app actually makes - scratch space in its own private cache, a size guard before
 * anything is decrypted, Core asked when the runtime is up, and that scratch space is gone whether
 * the backup was good or not. A leftover plaintext wallet in cache is the failure this pins down.
 */
@RunWith(RobolectricTestRunner::class)
class WalletBackupVerifierTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private val password = "correct horse battery staple"

    private val workspace: File get() = File(context.cacheDir, "backup-verify")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workspace.deleteRecursively()
    }

    private fun walletData(size: Int = 256): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Builds a container the way the export path does, with the current format manifest. */
    private fun buildBackup(
        name: String,
        wallet: ByteArray,
        manifest: String = BackupManifest.render("0.28.2-fullmode", "stohn-wallet", 123_456L, sha256Hex(wallet)),
        labelsJson: String? = null,
        password: String = this.password
    ): File {
        val archive = temporaryFolder.newFile("$name.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            zip.write(wallet)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            zip.write(manifest.toByteArray(Charsets.US_ASCII))
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
        val dir = temporaryFolder.newFolder("labels-source-${System.nanoTime()}")
        AddressBook.save(dir, listOf(AddressBookLabel("SaddrAlice", "Alice"), AddressBookLabel("SaddrBob", "Bob")))
        return File(dir, "labels.json").readText()
    }

    /** Answers Core's `info` the way the real wallet tool does. */
    private class FakeRuntime(
        private val root: File,
        var infoOutput: String = "Format: sqlite, encrypted with mkey"
    ) : RuntimeBackend {
        var infoCalls = 0
        override val dataDir: File = root
        override val rpcPort: Int = 0

        override fun corePath(file: File): String = file.absolutePath
        override suspend fun start() = Unit
        override suspend fun stop() = Unit

        override fun createWalletToolWorkspace(prefix: String): File =
            File(root, "tool-$prefix-${System.nanoTime()}").apply { mkdirs() }

        override fun runWalletTool(
            workspace: File,
            walletName: String,
            command: String,
            dumpFile: File?,
            walletFormat: String?
        ): RuntimeBackend.ToolResult {
            infoCalls++
            return RuntimeBackend.ToolResult(0, infoOutput, "")
        }
    }

    private fun fakeRuntime(root: File = temporaryFolder.newFolder("runtime-${System.nanoTime()}")) =
        FakeRuntime(root)

    @Test
    fun `a good backup verifies, and Core has the last word on what is inside`() {
        val wallet = walletData()
        val backup = buildBackup("good", wallet, labelsJson = labelsFixture())

        val result = runBlocking { WalletBackupVerifier(context, fakeRuntime()).verify(backup, password.toCharArray()) }

        assertEquals(wallet.size.toLong(), result.walletBytes)
        assertEquals(sha256Hex(wallet), result.walletSha256)
        assertEquals(2, result.labelCount)
        assertEquals("stohn-wallet", result.walletName)
        assertEquals(123_456L, result.creationHeight)
        assertEquals("0.28.2", result.appVersion)
        assertEquals("sqlite", result.walletFormat)
        assertEquals(true, result.walletEncrypted)
        assertTrue(result.coreChecked)
    }

    @Test
    fun `with no runtime the container is proven but nothing pretends Core opened it`() {
        val wallet = walletData()
        val backup = buildBackup("no-runtime", wallet)

        val result = runBlocking { WalletBackupVerifier(context, null).verify(backup, password.toCharArray()) }

        assertEquals(wallet.size.toLong(), result.walletBytes)
        assertNull(result.walletFormat)
        assertNull(result.walletEncrypted)
        assertFalse(result.coreChecked)
    }

    @Test
    fun `a container that is not a Core wallet is refused even though it decrypted`() {
        val backup = buildBackup("not-a-wallet", walletData())
        val runtime = fakeRuntime()
        runtime.infoOutput = "Format: unknown, plain"

        val failure = runCatching {
            runBlocking { WalletBackupVerifier(context, runtime).verify(backup, password.toCharArray()) }
        }.exceptionOrNull()

        assertTrue("expected the wallet check to refuse it but got $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Stohn Core wallet"))
        assertEquals("Core was not asked", 1, runtime.infoCalls)
    }

    @Test
    fun `the scratch directory is removed after a successful verification`() {
        val backup = buildBackup("cleanup", walletData())

        runBlocking { WalletBackupVerifier(context, null).verify(backup, password.toCharArray()) }

        assertFalse("a plaintext wallet was left in the app cache", workspace.exists())
        assertTrue("nothing else should be written next to it", context.cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `the scratch directory is removed when verification fails`() {
        val backup = buildBackup("wrong-password", walletData(), password = "another passphrase entirely")

        val failure = runCatching {
            runBlocking { WalletBackupVerifier(context, null).verify(backup, password.toCharArray()) }
        }.exceptionOrNull()

        assertTrue("a wrong passphrase must not verify", failure != null)
        assertFalse("a failed attempt left its workspace behind", workspace.exists())
    }

    @Test
    fun `a stale workspace from an earlier run is wiped before use`() {
        workspace.mkdirs()
        val stale = File(workspace, "leftover-snapshot.dat").apply { writeText("plaintext from last time") }
        assertTrue(stale.isFile)
        val backup = buildBackup("stale", walletData())

        runBlocking { WalletBackupVerifier(context, null).verify(backup, password.toCharArray()) }

        assertFalse("an old plaintext wallet survived into the next verification", workspace.exists())
    }

    @Test
    fun `a missing backup is refused`() {
        val failure = runCatching {
            runBlocking {
                WalletBackupVerifier(context, null)
                    .verify(File(temporaryFolder.root, "absent.stohnbackup"), password.toCharArray())
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Backup file does not exist"))
    }

    @Test
    fun `an absurdly large file is refused before it is decrypted`() {
        // Sparse, so the limit is exercised without writing 600 MiB to disk.
        val huge = File(temporaryFolder.root, "huge.stohnbackup")
        RandomAccessFile(huge, "rw").use { it.setLength(600L * 1024 * 1024 + 1L) }
        try {
            val runtime = fakeRuntime()
            val failure = runCatching {
                runBlocking { WalletBackupVerifier(context, runtime).verify(huge, password.toCharArray()) }
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("safety limit"))
            assertEquals("the file was read anyway", 0, runtime.infoCalls)
            assertFalse(workspace.exists())
        } finally {
            huge.delete()
        }
    }
}
