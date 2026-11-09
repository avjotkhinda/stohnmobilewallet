package org.stohncoin.wallet.wallet

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * Covers the backup and restore paths end to end on the JVM: a real container is written by the
 * manager and read back by the manager, with only Core's wallet tool faked out.
 */
class WalletBackupManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val password = "correct horse battery staple"

    /** Answers wallet-tool calls the way Core does, writing the files the migration expects. */
    private class FakeRuntime(private val workspaceRoot: File) : RuntimeBackend {
        data class ToolCall(
            val walletName: String,
            val command: String,
            val dumpFile: File?,
            val walletFormat: String?
        )

        val toolCalls = mutableListOf<ToolCall>()
        private val snapshots = mutableListOf<Pair<String, String?>>()
        var snapshotBytes: ByteArray = ByteArray(256) { (it % 251).toByte() }
        var walletToolResult: (ToolCall) -> RuntimeBackend.ToolResult = { RuntimeBackend.ToolResult(0, "", "") }
        var snapshotFailure: Throwable? = null

        val requestedSnapshots: List<Pair<String, String?>> get() = snapshots

        override val dataDir: File = workspaceRoot
        override val rpcPort: Int = 0

        override fun corePath(file: File): String = file.absolutePath
        override suspend fun start() = Unit
        override suspend fun stop() = Unit

        override fun createWalletToolWorkspace(prefix: String): File =
            File(workspaceRoot, "tool-$prefix-${System.nanoTime()}").apply { assertTrue(mkdirs()) }

        override suspend fun backupWalletSnapshot(snapshotName: String, walletName: String?): File {
            snapshotFailure?.let { throw it }
            snapshots += snapshotName to walletName
            return File(workspaceRoot, "snapshot-$snapshotName.dat").apply {
                writeBytes(snapshotBytes)
            }
        }

        override fun runWalletTool(
            workspace: File,
            walletName: String,
            command: String,
            dumpFile: File?,
            walletFormat: String?
        ): RuntimeBackend.ToolResult {
            val call = ToolCall(walletName, command, dumpFile, walletFormat)
            toolCalls += call
            when (command) {
                "info" -> return RuntimeBackend.ToolResult(0, "Format: sqlite, encrypted with mkey", "")
                "dump" -> dumpFile?.writeText("dumped keys")
                "createfromdump" -> File(workspace, walletName).writeText("migrated wallet")
            }
            return walletToolResult(call)
        }
    }

    private fun manager(runtime: RuntimeBackend): WalletBackupManager =
        WalletBackupManager(cacheDir(), filesDir(), runtime)

    private fun cacheDir(): File = temporaryFolder.newFolder("cache-${System.nanoTime()}")

    private fun filesDir(): File = temporaryFolder.newFolder("files-${System.nanoTime()}")

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun snapshot(name: String = "wallet.dat"): File =
        temporaryFolder.newFile(name).apply { writeBytes(ByteArray(200) { (it % 97).toByte() }) }

    private fun saveLabels(filesDir: File, labels: List<AddressBookLabel>) {
        val dir = File(filesDir, "addressbook")
        dir.mkdirs()
        AddressBook.save(dir, labels)
    }

    private fun loadLabels(filesDir: File): List<AddressBookLabel> =
        AddressBook.load(File(filesDir, "addressbook")).labels

    /** Builds a container the way a backup does, so restore has something real to open. */
    private fun backupFile(name: String = "backup.stohnbackup"): File {
        val destination = File(temporaryFolder.root, name)
        manager(FakeRuntime(temporaryFolder.root))
            .packageMigrationSnapshot(snapshot(), destination, password.toCharArray(), "stohn-wallet", 1000L)
        return destination
    }

    // ---- packaging -----------------------------------------------------------------------

    @Test
    fun `packaging writes an encrypted container that reads back as the same wallet`() {
        val source = snapshot()
        val destination = File(temporaryFolder.root, "backup.stohnbackup")
        val filesDir = filesDir()
        saveLabels(filesDir, listOf(AddressBookLabel("SaddrAlice", "Alice")))

        val result = WalletBackupManager(cacheDir(), filesDir, FakeRuntime(temporaryFolder.root))
            .packageMigrationSnapshot(source, destination, password.toCharArray(), "stohn-wallet", 1000L)

        assertEquals(destination, result.file)
        assertEquals(sha256(destination), result.sha256)
        val plaintext = File(temporaryFolder.root, "roundtrip.snapshot")
        BackupCrypto.decrypt(destination, plaintext, password.toCharArray())
        val wallet = File(temporaryFolder.root, "roundtrip-wallet.dat")
        val contents = BackupContainer.restore(plaintext, wallet, 1024 * 1024)

        assertEquals("stohn-wallet", contents.manifest.walletName)
        assertEquals(1000L, contents.manifest.creationHeight)
        assertEquals("the manifest must pin the wallet it describes", sha256(source), contents.walletSha256)
        assertEquals(listOf(AddressBookLabel("SaddrAlice", "Alice")), contents.labels)
        assertEquals(source.readBytes().toList(), wallet.readBytes().toList())
    }

    @Test
    fun `packaging records the app version the backup was taken with`() {
        val destination = File(temporaryFolder.root, "versioned.stohnbackup")
        WalletBackupManager(cacheDir(), filesDir(), FakeRuntime(temporaryFolder.root))
            .packageMigrationSnapshot(snapshot(), destination, password.toCharArray(), "stohn-wallet", 7L)

        val plaintext = File(temporaryFolder.root, "versioned.snapshot")
        BackupCrypto.decrypt(destination, plaintext, password.toCharArray())
        val contents = BackupContainer.restore(plaintext, File(temporaryFolder.root, "v-wallet.dat"), 1024 * 1024)

        // The build suffix is deliberately dropped: a manifest records a plain version number.
        assertEquals(
            BackupManifest.normalizeVersion(org.stohncoin.wallet.runtime.RuntimeManifest.APP_VERSION),
            contents.manifest.appVersion
        )
        assertTrue("the manifest must not carry the -fullmode build suffix", contents.manifest.appVersion?.contains("-") != true)
    }

    @Test
    fun `packaging omits labels when the address book does not exist`() {
        val destination = File(temporaryFolder.root, "no-labels.stohnbackup")

        WalletBackupManager(cacheDir(), filesDir(), FakeRuntime(temporaryFolder.root))
            .packageMigrationSnapshot(snapshot(), destination, password.toCharArray(), "stohn-wallet", 1L)

        val plaintext = File(temporaryFolder.root, "no-labels.snapshot")
        BackupCrypto.decrypt(destination, plaintext, password.toCharArray())
        val contents = BackupContainer.restore(plaintext, File(temporaryFolder.root, "nl-wallet.dat"), 1024 * 1024)

        assertTrue(contents.labels.isEmpty())
    }

    @Test
    fun `packaging leaves no plaintext behind and wipes the password`() {
        val cacheDir = cacheDir()
        val captured = password.toCharArray()

        WalletBackupManager(cacheDir, filesDir(), FakeRuntime(temporaryFolder.root))
            .packageMigrationSnapshot(snapshot(), File(temporaryFolder.root, "clean.stohnbackup"), captured, "stohn-wallet", 1L)

        assertTrue("the plaintext archive must not survive packaging", cacheDir.listFiles().isNullOrEmpty())
        assertTrue("the password must be wiped", captured.all { it == '\u0000' })
    }

    @Test
    fun `packaging refuses a missing snapshot, an empty password or a destination that is the snapshot`() {
        val source = snapshot()
        val manager = WalletBackupManager(cacheDir(), filesDir(), FakeRuntime(temporaryFolder.root))

        expectFailure("Backup snapshot does not exist") {
            manager.packageMigrationSnapshot(
                File(temporaryFolder.root, "absent.dat"),
                File(temporaryFolder.root, "x.stohnbackup"),
                password.toCharArray(),
                "stohn-wallet",
                1L
            )
        }
        expectFailure("Backup password is required") {
            manager.packageMigrationSnapshot(source, File(temporaryFolder.root, "x.stohnbackup"), CharArray(0), "stohn-wallet", 1L)
        }
        expectFailure("Backup destination must differ from the source snapshot") {
            manager.packageMigrationSnapshot(source, source, password.toCharArray(), "stohn-wallet", 1L)
        }
        expectFailure("Backup destination directory does not exist") {
            manager.packageMigrationSnapshot(
                source,
                File(temporaryFolder.root, "missing-dir/deep/x.stohnbackup"),
                password.toCharArray(),
                "stohn-wallet",
                1L
            )
        }
    }

    @Test
    fun `the container cannot be opened without the password it was written with`() {
        val destination = backupFile("locked.stohnbackup")

        val failure = runCatching {
            BackupCrypto.decrypt(destination, File(temporaryFolder.root, "out.snapshot"), "not the password".toCharArray())
        }.exceptionOrNull()

        assertNotNull("a wrong password must not decrypt the container", failure)
    }

    // ---- createCoreBackup ----------------------------------------------------------------

    @Test
    fun `createCoreBackup asks for a fresh snapshot and deletes it afterwards`() = runBlocking {
        val runtime = FakeRuntime(temporaryFolder.root)
        val destination = File(temporaryFolder.root, "created.stohnbackup")
        val manager = WalletBackupManager(cacheDir(), filesDir(), runtime)

        val result = manager.createCoreBackup(destination, password.toCharArray(), "stohn-wallet", 4242L)

        assertEquals(destination, result.file)
        assertTrue(result.file.isFile)
        val (snapshotName, walletName) = runtime.requestedSnapshots.single()
        assertEquals("stohn-wallet", walletName)
        assertTrue("the snapshot name must be an opaque token", snapshotName.matches(Regex("[0-9a-f]{32}")))
        assertFalse(
            "the raw Core snapshot must not be left on disk",
            File(temporaryFolder.root, "snapshot-$snapshotName.dat").exists()
        )
    }

    @Test
    fun `createCoreBackup deletes the snapshot even when packaging fails`() = runBlocking {
        val runtime = FakeRuntime(temporaryFolder.root)
        val manager = WalletBackupManager(cacheDir(), filesDir(), runtime)
        val unwritable = File(temporaryFolder.root, "no-such-dir/backup.stohnbackup")

        runCatching { manager.createCoreBackup(unwritable, password.toCharArray(), "stohn-wallet", 1L) }
            .onSuccess { error("packaging into a missing directory was unexpectedly accepted") }

        val snapshotName = runtime.requestedSnapshots.single().first
        assertFalse(File(temporaryFolder.root, "snapshot-$snapshotName.dat").exists())
    }

    @Test
    fun `createCoreBackup validates its arguments before touching the runtime`() = runBlocking {
        val runtime = FakeRuntime(temporaryFolder.root)
        val manager = WalletBackupManager(cacheDir(), filesDir(), runtime)
        val destination = File(temporaryFolder.root, "never.stohnbackup")

        expectFailure("Backup password is required") {
            runBlocking { manager.createCoreBackup(destination, CharArray(0), "stohn-wallet", 1L) }
        }
        expectFailure("Wallet name is required") {
            runBlocking { manager.createCoreBackup(destination, password.toCharArray(), "  ", 1L) }
        }
        expectFailure("Creation height cannot be negative") {
            runBlocking { manager.createCoreBackup(destination, password.toCharArray(), "stohn-wallet", -1L) }
        }
        assertTrue("no snapshot should be requested for a rejected call", runtime.requestedSnapshots.isEmpty())
    }

    // ---- restoreCoreBackup ---------------------------------------------------------------

    @Test
    fun `restoreCoreBackup imports the wallet and reports the manifest it carried`() = runBlocking {
        val backup = backupFile("restore-me.stohnbackup")
        val runtime = FakeRuntime(temporaryFolder.root)
        val filesDir = filesDir()
        val destination = File(temporaryFolder.root, "restored-wallet.dat")

        val result = WalletBackupManager(cacheDir(), filesDir, runtime)
            .restoreCoreBackup(backup, password.toCharArray(), destination)

        assertEquals(destination, result.import.destination)
        assertTrue(result.import.destination.isFile)
        assertEquals(-1, result.import.addressesImported)
        assertEquals("stohn-wallet", result.manifest.walletName)
        assertEquals(1000L, result.manifest.creationHeight)
        assertEquals(listOf("info", "dump", "createfromdump"), runtime.toolCalls.map { it.command })
        assertEquals("Core must be asked for a modern wallet", "sqlite", runtime.toolCalls.last().walletFormat)
    }

    @Test
    fun `restoreCoreBackup brings the address book back and keeps labels saved since the backup`() {
        val filesDir = filesDir()
        saveLabels(
            filesDir,
            listOf(
                AddressBookLabel("SaddrOld", "Saved after the backup"),
                AddressBookLabel("SaddrAlice", "Stale name")
            )
        )
        val backup = File(temporaryFolder.root, "labels.stohnbackup")
        val packingFiles = filesDir()
        saveLabels(packingFiles, listOf(AddressBookLabel("SaddrAlice", "Alice"), AddressBookLabel("SaddrBob", "Bob")))
        WalletBackupManager(cacheDir(), packingFiles, FakeRuntime(temporaryFolder.root))
            .packageMigrationSnapshot(snapshot(), backup, password.toCharArray(), "stohn-wallet", 1L)

        runBlocking {
            WalletBackupManager(cacheDir(), filesDir, FakeRuntime(temporaryFolder.root))
                .restoreCoreBackup(backup, password.toCharArray(), File(temporaryFolder.root, "restored-labels.dat"))
        }

        val restored = loadLabels(filesDir).associate { it.raw to it.label }
        assertEquals("Saved after the backup", restored["SaddrOld"])
        assertEquals("the backup wins for an address it contains", "Alice", restored["SaddrAlice"])
        assertEquals("Bob", restored["SaddrBob"])
    }

    @Test
    fun `restoreCoreBackup leaves the address book alone when the backup has no labels`() {
        val filesDir = filesDir()
        saveLabels(filesDir, listOf(AddressBookLabel("SaddrOld", "Kept")))
        val backup = File(temporaryFolder.root, "plain.stohnbackup")
        WalletBackupManager(cacheDir(), filesDir(), FakeRuntime(temporaryFolder.root))
            .packageMigrationSnapshot(snapshot(), backup, password.toCharArray(), "stohn-wallet", 1L)

        runBlocking {
            WalletBackupManager(cacheDir(), filesDir(), FakeRuntime(temporaryFolder.root))
                .restoreCoreBackup(backup, password.toCharArray(), File(temporaryFolder.root, "restored-plain.dat"))
        }

        assertEquals(listOf(AddressBookLabel("SaddrOld", "Kept")), loadLabels(filesDir))
    }

    @Test
    fun `restoreCoreBackup cleans up its temporary files and wipes the password`() {
        val backup = backupFile("cleanup.stohnbackup")
        val cacheDir = cacheDir()
        val captured = password.toCharArray()

        runBlocking {
            WalletBackupManager(cacheDir, filesDir(), FakeRuntime(temporaryFolder.root))
                .restoreCoreBackup(backup, captured, File(temporaryFolder.root, "restored-clean.dat"))
        }

        assertTrue("decrypted plaintext must not survive the restore", cacheDir.listFiles().isNullOrEmpty())
        assertTrue("the password must be wiped", captured.all { it == '\u0000' })
    }

    @Test
    fun `restoreCoreBackup wipes the password and cleans up when Core rejects the wallet`() {
        val backup = backupFile("rejected.stohnbackup")
        val cacheDir = cacheDir()
        val captured = password.toCharArray()
        val runtime = FakeRuntime(temporaryFolder.root)
        runtime.walletToolResult = { RuntimeBackend.ToolResult(1, "", "wallet dump failed") }

        val failure = runCatching {
            runBlocking {
                WalletBackupManager(cacheDir, filesDir(), runtime)
                    .restoreCoreBackup(backup, captured, File(temporaryFolder.root, "restored-rejected.dat"))
            }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("wallet dump failed"))
        assertTrue(captured.all { it == '\u0000' })
        assertTrue(cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `restoreCoreBackup refuses a container whose wallet does not match its checksum`() {
        val archive = temporaryFolder.newFile("tampered.zip")
        val wallet = ByteArray(64) { it.toByte() }
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry(BackupContainer.ENTRY_WALLET))
            zip.write(wallet)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupContainer.ENTRY_MANIFEST))
            zip.write(BackupManifest.render("0.28.2", "stohn-wallet", 1L, "0".repeat(64)).toByteArray())
            zip.closeEntry()
        }
        val backup = File(temporaryFolder.root, "tampered.stohnbackup")
        BackupCrypto.encrypt(archive, backup, password.toCharArray())
        val destination = File(temporaryFolder.root, "tampered-restore.dat")

        expectFailure("Backup wallet checksum mismatch") {
            runBlocking {
                WalletBackupManager(cacheDir(), filesDir(), FakeRuntime(temporaryFolder.root))
                    .restoreCoreBackup(backup, password.toCharArray(), destination)
            }
        }
        assertFalse("a rejected backup must not leave a wallet behind", destination.exists())
    }

    @Test
    fun `restoreCoreBackup validates its arguments before reading the container`() {
        val backup = backupFile("validated.stohnbackup")
        val existing = temporaryFolder.newFile("already-there.dat")
        val manager = WalletBackupManager(cacheDir(), filesDir(), FakeRuntime(temporaryFolder.root))

        expectFailure("Backup file does not exist") {
            runBlocking { manager.restoreCoreBackup(File(temporaryFolder.root, "nope.stohnbackup"), password.toCharArray(), File(temporaryFolder.root, "a.dat")) }
        }
        expectFailure("Backup password is required") {
            runBlocking { manager.restoreCoreBackup(backup, CharArray(0), File(temporaryFolder.root, "b.dat")) }
        }
        expectFailure("Restore destination must not already exist") {
            runBlocking { manager.restoreCoreBackup(backup, password.toCharArray(), existing) }
        }
        // Restoring onto the backup itself trips the "already exists" guard first: an input that
        // is a readable file always exists, so the differ-from-backup check below it never fires.
        expectFailure("Restore destination must not already exist") {
            runBlocking { manager.restoreCoreBackup(backup, password.toCharArray(), backup) }
        }
    }

    @Test
    fun `restoreCoreBackup refuses a container larger than the safety limit`() {
        // A sparse file: the length is over the 600 MiB limit without occupying 600 MiB of disk.
        val huge = File(temporaryFolder.root, "huge.stohnbackup")
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(600L * 1024 * 1024 + 1) }
        val manager = WalletBackupManager(cacheDir(), filesDir(), FakeRuntime(temporaryFolder.root))

        expectFailure("Backup file exceeds safety limit") {
            runBlocking { manager.restoreCoreBackup(huge, password.toCharArray(), File(temporaryFolder.root, "huge.dat")) }
        }
        maybeDelete(huge)
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun maybeDelete(file: File) {
        if (file.exists() && !file.delete()) file.writeBytes(ByteArray(0))
    }

    private fun expectFailure(expected: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
            ?: error("expected a failure containing \"$expected\"")
        assertTrue(
            "expected \"$expected\" but got \"${failure.message}\"",
            failure.message.orEmpty().contains(expected)
        )
    }
}
