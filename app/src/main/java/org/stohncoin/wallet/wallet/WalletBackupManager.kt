package org.stohncoin.wallet.wallet

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stohncoin.wallet.runtime.EmbeddedLinuxRuntime

/**
 * Creates an app-owned encrypted-wallet backup container without ever modifying a live Core wallet.
 * The actual wallet.dat import remains delegated to the official Core migration engine.
 */
class WalletBackupManager(private val context: Context, private val runtime: EmbeddedLinuxRuntime) {
    private val root = File(context.filesDir, "fullmode")
    private val walletDir = File(root, "stohn-data")

    suspend fun createCoreBackup(destination: File, password: CharArray): BackupResult = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "Backup password is required" }
        val core = runtime
        val snapshot = core.backupWalletSnapshot(java.util.UUID.randomUUID().toString().replace("-", ""))
        try {
            packageMigrationSnapshot(snapshot, destination, password)
        } finally {
            snapshot.delete()
        }
    }


    suspend fun restoreCoreBackup(input: File, password: CharArray, destination: File) = withContext(Dispatchers.IO) {
        require(input.isFile) { "Backup file does not exist" }
        require(password.isNotEmpty()) { "Backup password is required" }
        require(!destination.exists()) { "Restore destination must not already exist" }
        require(destination.canonicalFile != input.canonicalFile) { "Restore destination must differ from the backup" }
        val decrypted = File.createTempFile("stohn-restore-", ".snapshot", context.cacheDir)
        val stagedWallet = File.createTempFile("stohn-restore-wallet-", ".dat", context.cacheDir)
        try {
            require(input.length() <= MAX_BACKUP_CONTAINER_BYTES) { "Backup file exceeds safety limit" }
            BackupCrypto.decrypt(input, decrypted, password, MAX_BACKUP_PLAINTEXT_BYTES)
            var expectedHash: String? = null
            var foundWallet = false
            var entries = 0
            var total = 0L
            java.util.zip.ZipInputStream(FileInputStream(decrypted)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 8) { "Backup contains too many entries" }
                    val name = entry.name.replace('\\', '/')
                    require(name == "wallet.dat" || name == "MANIFEST.sha256") { "Unexpected backup entry" }
                    require(!entry.isDirectory && !name.contains("..") && !name.startsWith('/')) { "Unsafe backup entry" }
                    if (name == "wallet.dat") {
                        require(!foundWallet) { "Backup contains duplicate wallet.dat entries" }
                        FileOutputStream(stagedWallet).use { out ->
                            val remaining = MAX_WALLET_BYTES - total
                            require(remaining > 0L) { "Restored wallet exceeds safety limit" }
                            total += copyLimited(zip, out, remaining)
                        }
                        foundWallet = true
                    } else {
                        require(expectedHash == null) { "Backup contains duplicate manifest entries" }
                        val manifest = ByteArray(1024)
                        val count = zip.read(manifest)
                        require(count >= 0) { "Unable to read backup manifest" }
                        require(zip.read() < 0) { "Backup manifest is too large" }
                        expectedHash = String(manifest, 0, count, Charsets.US_ASCII).trim()
                    }
                    require(total <= MAX_WALLET_BYTES) { "Restored wallet exceeds safety limit" }
                    zip.closeEntry()
                }
            }
            require(foundWallet && stagedWallet.length() > 0L) { "Backup contains no wallet.dat" }
            require(expectedHash?.matches(Regex("[0-9a-f]{64}")) == true) { "Backup manifest is invalid" }
            require(sha256(stagedWallet) == expectedHash) { "Backup wallet checksum mismatch" }
            destination.parentFile?.mkdirs()
            require(stagedWallet.renameTo(destination)) { "Unable to install restored wallet" }
        } finally {
            decrypted.delete()
            if (stagedWallet.exists()) stagedWallet.delete()
            password.fill('\u0000')
        }
    }

    fun packageMigrationSnapshot(snapshot: File, destination: File, password: CharArray): BackupResult {
        require(snapshot.isFile) { "Backup snapshot does not exist" }
        require(password.isNotEmpty()) { "Backup password is required" }
        require(snapshot.canonicalFile != destination.canonicalFile) {
            "Backup destination must differ from the source snapshot"
        }
        require(destination.canonicalFile.parentFile?.exists() != false) {
            "Backup destination directory does not exist"
        }
        val plaintext = File.createTempFile("stohn-backup-", ".snapshot", context.cacheDir)
        try {
            // Keep the temporary plaintext inside app-private cache and delete it immediately.
            java.util.zip.ZipOutputStream(FileOutputStream(plaintext)).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("wallet.dat"))
                FileInputStream(snapshot).use { copyLimited(it, zip, MAX_WALLET_BYTES) }
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("MANIFEST.sha256"))
                zip.write(sha256(snapshot).toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
            }
            BackupCrypto.encrypt(plaintext, destination, password)
            return BackupResult(destination, sha256(destination))
        } finally {
            plaintext.delete()
            password.fill('\u0000')
        }
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        require(limit >= 0L) { "Invalid copy limit" }
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val maxRead = minOf(buffer.size.toLong(), limit - total).toInt()
            if (maxRead == 0) {
                // A further byte means the entry exceeds the limit.
                require(input.read() < 0) { "Restored wallet exceeds safety limit" }
                break
            }
            val n = input.read(buffer, 0, maxRead)
            if (n < 0) break
            total += n
            output.write(buffer, 0, n)
        }
        return total
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    data class BackupResult(val file: File, val sha256: String)

    companion object {
        private const val MAX_WALLET_BYTES = 512L * 1024 * 1024
        private const val MAX_BACKUP_PLAINTEXT_BYTES = 520L * 1024 * 1024
        private const val MAX_BACKUP_CONTAINER_BYTES = 600L * 1024 * 1024
    }
}
