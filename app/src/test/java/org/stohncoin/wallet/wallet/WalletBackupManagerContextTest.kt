package org.stohncoin.wallet.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * The constructor the app actually uses.
 *
 * Every other test builds the manager from two directories, so the one line that turns a Context
 * into those directories had never run. It decides where a backup is staged and where the restored
 * labels are written, and getting either wrong puts a plaintext wallet somewhere it outlives the
 * operation. This drives a real export and restore through that path and then looks at the two
 * directories it claims to use.
 */
@RunWith(RobolectricTestRunner::class)
class WalletBackupManagerContextTest {

    private lateinit var context: Context
    private val password = "correct horse battery staple"

    /** Answers Core's wallet tool the way it does, writing the files the migration expects. */
    private class FakeRuntime(private val root: File) : RuntimeBackend {
        override val dataDir: File = root
        override val rpcPort: Int = 0

        override fun corePath(file: File): String = file.absolutePath
        override suspend fun start() = Unit
        override suspend fun stop() = Unit

        override fun createWalletToolWorkspace(prefix: String): File =
            File(root, "tool-$prefix-${System.nanoTime()}").apply { mkdirs() }

        override suspend fun backupWalletSnapshot(snapshotName: String, walletName: String?): File =
            File(root, "snapshot-$snapshotName.dat").apply { writeBytes(ByteArray(256) { (it % 251).toByte() }) }

        override fun runWalletTool(
            workspace: File,
            walletName: String,
            command: String,
            dumpFile: File?,
            walletFormat: String?
        ): RuntimeBackend.ToolResult {
            when (command) {
                "info" -> return RuntimeBackend.ToolResult(0, "Format: sqlite, encrypted with mkey", "")
                "dump" -> dumpFile?.writeText("dumped keys")
                "createfromdump" -> File(workspace, walletName).writeText("migrated wallet")
            }
            return RuntimeBackend.ToolResult(0, "", "")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "addressbook").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun runtime(): FakeRuntime = FakeRuntime(File(context.filesDir, "runtime").apply { mkdirs() })

    /** The manager refuses to invent a destination directory; the app creates it first. */
    private fun backupPath(): File {
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        return File(dir, "wallet.stohnbackup")
    }

    @Test
    fun `a Context-built manager exports to and restores from the app's own directories`() {
        // A label saved before the backup must survive; the one inside the backup must be applied.
        AddressBook.save(File(context.filesDir, "addressbook"), listOf(AddressBookLabel("SaddrOld", "SavedLater")))
        val manager = WalletBackupManager(context, runtime())
        val backup = backupPath()

        val result = runBlocking {
            manager.createCoreBackup(backup, password.toCharArray(), "stohn-wallet", 4_242L)
        }
        assertEquals(backup, result.file)
        assertTrue(backup.isFile)

        val destination = File(context.filesDir, "restored/wallet.dat")
        val restored = runBlocking {
            manager.restoreCoreBackup(backup, password.toCharArray(), destination)
        }

        assertTrue("the restored wallet is not where it was asked to go", destination.isFile)
        assertEquals("stohn-wallet", restored.manifest.walletName)
        assertEquals(4_242L, restored.manifest.creationHeight)
        assertEquals("0.28.2", restored.manifest.appVersion)
    }

    @Test
    fun `staging happens in the app cache and nothing plaintext is left there`() {
        val manager = WalletBackupManager(context, runtime())
        val backup = backupPath()
        runBlocking { manager.createCoreBackup(backup, password.toCharArray(), "stohn-wallet", 1L) }
        runBlocking { manager.restoreCoreBackup(backup, password.toCharArray(), File(context.filesDir, "out.dat")) }

        val leftovers = context.cacheDir.listFiles().orEmpty().map { it.name }
        assertTrue("a plaintext snapshot was left in the app cache: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `an export refuses a destination directory that does not exist`() {
        // Silently creating the directory would let a mistyped path scatter backups through the
        // filesystem; the caller has to mean it.
        val manager = WalletBackupManager(context, runtime())
        val absent = File(File(context.filesDir, "never-created"), "wallet.stohnbackup")

        val failure = runCatching {
            runBlocking { manager.createCoreBackup(absent, password.toCharArray(), "stohn-wallet", 1L) }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Backup destination directory does not exist"))
        assertFalse(absent.exists())
    }

    @Test
    fun `restored labels are merged into the app's address book`() {
        AddressBook.save(File(context.filesDir, "addressbook"), listOf(AddressBookLabel("SaddrOld", "Kept")))
        val manager = WalletBackupManager(context, runtime())
        val backup = backupPath()
        runBlocking { manager.createCoreBackup(backup, password.toCharArray(), "stohn-wallet", 1L) }

        // Labels added after the backup was taken are not in the container, so the merge has both.
        AddressBook.save(
            File(context.filesDir, "addressbook"),
            listOf(AddressBookLabel("SaddrOld", "Kept"))
        )
        runBlocking { manager.restoreCoreBackup(backup, password.toCharArray(), File(context.filesDir, "out.dat")) }

        val book = AddressBook.load(File(context.filesDir, "addressbook"))
        assertEquals("Kept", book.labelFor("SaddrOld"))
        assertFalse("the restore wrote nowhere near the address book", book.labels.isEmpty())
    }
}
