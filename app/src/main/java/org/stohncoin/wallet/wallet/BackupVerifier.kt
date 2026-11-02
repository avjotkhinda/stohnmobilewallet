package org.stohncoin.wallet.wallet

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * Test-restore: proves a backup decrypts and unpacks into a usable wallet *without* touching the
 * live wallet. A user can therefore confirm a backup before they need it, which is the only way a
 * backup is worth anything.
 *
 * The structural rules are kept free of Android and Core so they stay unit-testable on the JVM:
 * [BackupCrypto] authenticates the container, [BackupContainer] enforces the entry whitelist,
 * traversal/symlink and size limits, and the optional [WalletCheck] callback lets the caller add a
 * decision from the official Core wallet tool. Every plaintext artifact lives in a caller-supplied
 * workspace and is deleted before this returns, whatever the outcome.
 */
object BackupVerifier {

    /** Result of asking the caller to judge the unpacked wallet (Core's opinion, in production). */
    data class WalletCheck(val format: String, val encrypted: Boolean)

    data class Report(
        val walletBytes: Long,
        val walletSha256: String,
        val labelCount: Int,
        val walletFormat: String?,
        val walletEncrypted: Boolean?,
        val walletName: String?,
        val creationHeight: Long?,
        val appVersion: String?
    )

    const val MAX_WALLET_BYTES = 512L * 1024 * 1024
    const val MAX_PLAINTEXT_BYTES = 520L * 1024 * 1024

    /**
     * Decrypts [encrypted], unpacks the wallet into [workspace], and returns what it found.
     * Throws when the container is not a valid Stohn backup, when the password is wrong, when an
     * entry violates the restore rules, or when [validateWallet] rejects the unpacked wallet.
     */
    suspend fun verify(
        encrypted: File,
        password: CharArray,
        workspace: File,
        maxWalletBytes: Long = MAX_WALLET_BYTES,
        maxPlaintextBytes: Long = MAX_PLAINTEXT_BYTES,
        validateWallet: (suspend (File) -> WalletCheck)? = null
    ): Report {
        require(encrypted.isFile) { "Backup file does not exist" }
        require(password.isNotEmpty()) { "Backup password is required" }
        require(maxWalletBytes > 0L) { "Wallet size limit must be positive" }
        require(workspace.isDirectory || workspace.mkdirs()) { "Verification workspace is unavailable" }
        val plaintext = File.createTempFile("stohn-verify-", ".snapshot", workspace)
        val staged = File.createTempFile("stohn-verify-wallet-", ".dat", workspace)
        try {
            BackupCrypto.decrypt(encrypted, plaintext, password, maxPlaintextBytes)
            val contents = BackupContainer.restore(plaintext, staged, maxWalletBytes)
            val check = validateWallet?.invoke(contents.wallet)
            return Report(
                walletBytes = contents.wallet.length(),
                walletSha256 = contents.walletSha256,
                labelCount = contents.labels.count { it.raw.isNotBlank() && it.label.isNotBlank() },
                walletFormat = check?.format,
                walletEncrypted = check?.encrypted,
                walletName = contents.manifest.walletName,
                creationHeight = contents.manifest.creationHeight,
                appVersion = contents.manifest.appVersion
            )
        } finally {
            plaintext.delete()
            staged.delete()
            password.fill('\u0000')
        }
    }
}

/** What a successful test-restore proved about a backup file. */
data class BackupVerification(
    val walletBytes: Long,
    val walletSha256: String,
    val labelCount: Int,
    val walletFormat: String?,
    val walletEncrypted: Boolean?,
    /** Wallet details from the manifest; null when the backup predates them. */
    val walletName: String? = null,
    val creationHeight: Long? = null,
    val appVersion: String? = null,
    /** Differences between this backup and this device, e.g. a different wallet or app version. */
    val notes: List<String> = emptyList()
) {
    /** True only when Core itself read the unpacked wallet; false means the structure alone was checked. */
    val coreChecked: Boolean get() = walletFormat != null

    /** One line for a toast or card: which wallet, from when, made by which build. */
    val summary: String? = when {
        walletName != null && creationHeight != null ->
            "Wallet '$walletName' \u00b7 created at height $creationHeight" +
                (appVersion?.let { " \u00b7 app $it" } ?: "")
        else -> null
    }
}

/**
 * Test-restore from the user's chosen file. Uses app-private cache as scratch space, never the
 * wallet directory, and asks the official Core wallet tool to confirm the wallet when the local
 * runtime is up so "valid" means "Core can open it", not merely "the zip looked right".
 */
class WalletBackupVerifier(private val context: Context, private val runtime: RuntimeBackend?) {

    suspend fun verify(input: File, password: CharArray): BackupVerification = withContext(Dispatchers.IO) {
        require(input.isFile) { "Backup file does not exist" }
        require(input.length() <= MAX_CONTAINER_BYTES) { "Backup file exceeds safety limit" }
        val workspace = File(context.cacheDir, "backup-verify")
        try {
            workspace.deleteRecursively()
            check(workspace.mkdirs()) { "Unable to create the verification workspace" }
            // Core is the authority on "is this a wallet": ask the official tool when it is up.
            val validator: (suspend (File) -> BackupVerifier.WalletCheck)? = if (runtime == null) null else { wallet: File ->
                val info = CoreWalletMigration(runtime).inspect(wallet)
                require(info.format == "sqlite" || info.format == "bdb") {
                    "Backup does not contain a Stohn Core wallet"
                }
                BackupVerifier.WalletCheck(info.format, info.encrypted)
            }
            val report = BackupVerifier.verify(
                encrypted = input,
                password = password,
                workspace = workspace,
                validateWallet = validator
            )
            BackupVerification(
                walletBytes = report.walletBytes,
                walletSha256 = report.walletSha256,
                labelCount = report.labelCount,
                walletFormat = report.walletFormat,
                walletEncrypted = report.walletEncrypted,
                walletName = report.walletName,
                creationHeight = report.creationHeight,
                appVersion = report.appVersion
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    companion object {
        private const val MAX_CONTAINER_BYTES = 600L * 1024 * 1024
    }
}
