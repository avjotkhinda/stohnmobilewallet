package org.stohncoin.wallet.wallet

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Wallet storage boundary. Private keys never pass through the UI layer. */
class WalletManager(context: Context) {
    private val root = File(context.filesDir, "wallet")
    private val backupDir = File(root, "backups")
    private val importCache = File(context.cacheDir, "wallet-import")

    init {
        root.mkdirs()
        backupDir.mkdirs()
        importCache.mkdirs()
    }

    fun walletDirectory(): File = root
    fun backupDirectory(): File = backupDir

    /**
     * Stages a wallet.dat only for an immediate migration operation. The staged file is placed
     * in app-private cache and MUST be deleted by the caller after the Core migration engine
     * finishes. It is never treated as an installed wallet and never retained as a backup.
     */
    fun stageWalletDat(source: File): Result<StagedWallet> {
        if (!source.isFile) return Result.failure(IllegalArgumentException("wallet.dat not found"))
        if (source.length() <= 0L) return Result.failure(IllegalArgumentException("wallet.dat is empty"))
        if (source.length() > MAX_IMPORT_BYTES) {
            return Result.failure(IllegalArgumentException("wallet.dat exceeds safety limit"))
        }

        val staged = File(importCache, "import-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.wallet.dat")
        return runCatching {
            source.inputStream().use { input ->
    staged.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L

        while (true) {
            val n = input.read(buffer)
            if (n < 0) break

            total += n
            require(total <= MAX_IMPORT_BYTES) {
                "wallet.dat exceeds safety limit"
            }

            output.write(buffer, 0, n)
        }

        output.fd.sync()
    }
}

    fun deleteStagedWallet(staged: StagedWallet) {
        require(staged.file.parentFile?.canonicalFile == importCache.canonicalFile) {
            "Staged wallet is outside import cache"
        }
        staged.file.delete()
    }

    data class StagedWallet(val file: File, val sha256: String)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        // Defensive upper bound for an imported wallet database. Actual Core wallets are normally
        // much smaller; oversized input should not be allowed to consume unbounded app storage.
        private const val MAX_IMPORT_BYTES = 512L * 1024L * 1024L
    }
}
