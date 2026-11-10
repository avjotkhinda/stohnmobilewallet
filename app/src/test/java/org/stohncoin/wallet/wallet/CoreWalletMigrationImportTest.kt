package org.stohncoin.wallet.wallet

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * Importing a wallet through Core, and every way it must refuse instead.
 *
 * The happy path already runs through the restore tests; what is untested is the decision not to
 * move a wallet: an unrecognised format, a destination that already exists, a dump that failed or
 * came out empty, a createfromdump that reported success without producing a wallet, and a
 * destination that appeared while the tool was running. In each case the promise is that the source
 * wallet is untouched and no half-migrated file is left at the destination.
 */
class CoreWalletMigrationImportTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Stands in for Core's wallet tool: answers `info` and reports whatever the test asks for. */
    private class FakeRuntime(private val root: File) : RuntimeBackend {
        var info = RuntimeBackend.ToolResult(0, "Format: sqlite, encrypted with mkey", "")
        var dump = RuntimeBackend.ToolResult(0, "", "")
        var create = RuntimeBackend.ToolResult(0, "", "")
        var dumpBytes: ByteArray? = "dumped keys".toByteArray()
        var createWallet = true
        /** Runs at createfromdump time, for the case where something appears at the destination. */
        var whileCreating: (File) -> Unit = {}

        val commands = mutableListOf<String>()
        var destinationAtCreation: File? = null

        override val dataDir: File = root
        override val rpcPort: Int = 0

        override fun corePath(file: File): String = file.absolutePath
        override suspend fun start() = Unit
        override suspend fun stop() = Unit

        override fun createWalletToolWorkspace(prefix: String): File =
            File(root, "tool-$prefix-${System.nanoTime()}").apply { assertTrue(mkdirs()) }

        override fun runWalletTool(
            workspace: File,
            walletName: String,
            command: String,
            dumpFile: File?,
            walletFormat: String?
        ): RuntimeBackend.ToolResult {
            commands += command
            return when (command) {
                "info" -> info
                "dump" -> {
                    dumpBytes?.let { bytes -> dumpFile?.writeBytes(bytes) }
                    dump
                }
                "createfromdump" -> {
                    if (createWallet) File(workspace, walletName).writeText("migrated wallet")
                    destinationAtCreation?.let { whileCreating(it) }
                    create
                }
                else -> RuntimeBackend.ToolResult(0, "", "")
            }
        }
    }

    private fun runtime(): FakeRuntime = FakeRuntime(temporaryFolder.newFolder("runtime-${System.nanoTime()}"))

    private fun source(name: String = "wallet.dat"): File =
        File(temporaryFolder.root, name).apply { writeBytes(ByteArray(256) { (it % 251).toByte() }) }

    private fun destination(name: String = "restored-${System.nanoTime()}.dat"): File =
        File(temporaryFolder.root, name)

    private fun import(runtime: FakeRuntime, from: File, to: File): ImportResult =
        runBlocking { CoreWalletMigration(runtime).import(from, to) }

    private fun failure(runtime: FakeRuntime, from: File, to: File): Throwable? =
        runCatching { import(runtime, from, to) }.exceptionOrNull()

    private fun expectRefused(message: String, runtime: FakeRuntime, from: File, to: File) {
        val failure = failure(runtime, from, to)
        assertTrue(
            "expected a refusal containing '$message' but got $failure",
            failure != null && failure.message.orEmpty().contains(message)
        )
        assertFalse("a refused import left a wallet at the destination", to.exists())
    }

    @Test
    fun `a readable wallet is imported and ends up at the destination`() {
        val runtime = runtime()
        val from = source()
        val to = destination()

        val result = import(runtime, from, to)

        assertEquals(to, result.destination)
        assertEquals(-1, result.addressesImported)
        assertTrue(to.isFile)
        assertTrue("the source was modified", from.exists())
        assertEquals(listOf("info", "dump", "createfromdump"), runtime.commands)
    }

    @Test
    fun `an unreadable source is refused before Core is asked anything`() {
        val runtime = runtime()
        val absent = File(temporaryFolder.root, "absent.dat")

        expectRefused("wallet.dat is not readable", runtime, absent, destination())

        assertTrue("the wallet tool ran on an unreadable source", runtime.commands.isEmpty())
    }

    @Test
    fun `a format Core does not recognise stops the import`() {
        val runtime = runtime()
        runtime.info = RuntimeBackend.ToolResult(0, "Format: unknown, plain", "")

        expectRefused("Unrecognized wallet database format", runtime, source(), destination())

        assertEquals("the dump ran despite an unknown format", listOf("info"), runtime.commands)
    }

    @Test
    fun `an existing destination is never overwritten`() {
        val to = destination().apply { writeText("a wallet that is already there") }

        val failure = failure(runtime(), source(), to)

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Migration destination already exists"))
        assertEquals("the existing wallet was overwritten", "a wallet that is already there", to.readText())
    }

    @Test
    fun `a destination whose parent is a file is refused`() {
        val blockedParent = File(temporaryFolder.root, "not-a-directory").apply { writeText("x") }
        val to = File(blockedParent, "restored.dat")

        expectRefused("Migration destination parent is unavailable", runtime(), source(), to)
    }

    @Test
    fun `a failed dump is reported with Core's own error`() {
        val runtime = runtime()
        runtime.dump = RuntimeBackend.ToolResult(1, "", "cannot dump a corrupt wallet")

        expectRefused("wallet dump failed: cannot dump a corrupt wallet", runtime, source(), destination())

        assertEquals("createfromdump ran on a failed dump", listOf("info", "dump"), runtime.commands)
    }

    @Test
    fun `a dump that produced nothing is refused`() {
        val runtime = runtime()
        runtime.dumpBytes = null

        expectRefused("Core produced no wallet dump", runtime, source(), destination())
    }

    @Test
    fun `an empty dump file is refused too`() {
        val runtime = runtime()
        runtime.dumpBytes = ByteArray(0)

        expectRefused("Core produced no wallet dump", runtime, source(), destination())
    }

    @Test
    fun `a failed createfromdump is reported and leaves no wallet behind`() {
        val runtime = runtime()
        runtime.create = RuntimeBackend.ToolResult(1, "boom", "")

        expectRefused("wallet createfromdump failed: boom", runtime, source(), destination())
    }

    @Test
    fun `createfromdump reporting success without a wallet is refused`() {
        val runtime = runtime()
        runtime.createWallet = false

        expectRefused("Core did not create the migrated wallet", runtime, source(), destination())
    }

    @Test
    fun `a wallet appearing at the destination mid-import is refused rather than replaced`() {
        // Something wrote the destination while the tool was running; whatever it is, it is not ours
        // to overwrite, and the import must not claim success.
        val to = destination()
        val runtime = runtime().apply {
            destinationAtCreation = to
            whileCreating = { it.writeText("someone else's wallet") }
        }

        val failure = failure(runtime, source(), to)

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Migration destination was created unexpectedly"))
        assertEquals("the wallet that appeared was overwritten", "someone else's wallet", to.readText())
    }

    @Test
    fun `a Berkeley DB wallet is migrated too`() {
        val runtime = runtime()
        runtime.info = RuntimeBackend.ToolResult(0, "Format: Berkeley DB (BDB), encrypted with mkey", "")
        val to = destination()

        import(runtime, source(), to)

        assertTrue(to.isFile)
        assertEquals(listOf("info", "dump", "createfromdump"), runtime.commands)
    }

    @Test
    fun `a failing wallet info is reported instead of being treated as an unknown format`() {
        val runtime = runtime()
        runtime.info = RuntimeBackend.ToolResult(1, "", "wallet tool: cannot open file")

        expectRefused("wallet info failed: wallet tool: cannot open file", runtime, source(), destination())
    }
}
