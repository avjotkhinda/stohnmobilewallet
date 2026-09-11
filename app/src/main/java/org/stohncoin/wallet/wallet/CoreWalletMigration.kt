package org.stohncoin.wallet.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stohncoin.wallet.runtime.EmbeddedLinuxRuntime
import java.io.File

/**
 * Migration boundary around the official Stohn Core wallet tool.
 * No Berkeley DB/SQLite records are parsed by Android code.
 */
class CoreWalletMigration(private val runtime: EmbeddedLinuxRuntime) : WalletMigration {
    override suspend fun inspect(source: File): WalletInfo = withContext(Dispatchers.IO) {
        val staged = requireReadable(source)
        val workspace = runtime.createWalletToolWorkspace("inspect")
        try {
            val sourceCopy = File(workspace, "wallet.dat")
            staged.copyTo(sourceCopy)
            val result = runtime.runWalletTool(workspace, "wallet.dat", "info")
            check(result.exitCode == 0) { "wallet info failed: ${result.stderr.ifBlank { result.stdout }}" }
            parseInfo(result.stdout + "\n" + result.stderr)
        } finally {
            workspace.deleteRecursively()
        }
    }

    override suspend fun import(source: File, passphrase: CharArray?, destination: File): ImportResult =
        withContext(Dispatchers.IO) {
            val staged = requireReadable(source)
            require(!destination.exists()) {
                "Migration destination already exists"
            }
            destination.parentFile?.mkdirs()
            require(destination.parentFile?.isDirectory == true) {
                "Migration destination parent is unavailable"
            }
            val workspace = runtime.createWalletToolWorkspace("import")
            try {
                // The dump format is produced by Core itself, so encrypted ckey/mkey records remain
                // encrypted. Android never decrypts private-key material during this conversion.
                val dump = File(workspace, "wallet.dump")
                val sourceCopy = File(workspace, "wallet.dat")
                staged.copyTo(sourceCopy)
                val dumpResult = runtime.runWalletTool(workspace, "wallet.dat", "dump", dump)
                check(dumpResult.exitCode == 0) { "wallet dump failed: ${dumpResult.stderr.ifBlank { dumpResult.stdout }}" }
                check(dump.isFile && dump.length() > 0) { "Core produced no wallet dump" }

                val migratedName = "migrated-wallet"
                val createResult = runtime.runWalletTool(workspace, migratedName, "createfromdump", dump)
                val newWallet = File(workspace, migratedName)
                check(createResult.exitCode == 0) {
                    "wallet createfromdump failed: ${createResult.stderr.ifBlank { createResult.stdout }}"
                }
                check(newWallet.exists()) { "Core did not create the migrated wallet" }
                check(!destination.exists()) { "Migration destination was created unexpectedly" }
                check(newWallet.renameTo(destination)) { "Unable to move migrated wallet into destination" }
                ImportResult(destination, addressesImported = -1)
            } finally {
                passphrase?.fill('\u0000')
                workspace.deleteRecursively()
            }
        }

    private fun requireReadable(source: File): File {
        require(source.isFile && source.canRead()) { "wallet.dat is not readable" }
        return source
    }

    private fun parseInfo(text: String): WalletInfo {
        val lower = text.lowercase()
        val format = when {
            "sqlite" in lower -> "sqlite"
            "bdb" in lower || "berkeley" in lower -> "bdb"
            else -> "unknown"
        }
        return WalletInfo(
            format = format,
            encrypted = "encrypted" in lower || "mkey" in lower,
            descriptors = "descriptor" in lower,
            hd = "hd" in lower || "hdseed" in lower
        )
    }
}
