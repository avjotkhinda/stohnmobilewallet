package org.stohncoin.wallet.runtime

import org.stohncoin.wallet.runtime.RuntimeBackend.Network

/**
 * The arguments after the wallet tool's own path, built in one place for every backend.
 *
 * The plaintext key-export guard runs here, so a launcher cannot skip it by constructing its own
 * argument list: `dumpwallet`, `dumpprivkey`, `dumphdinfo` and friends never reach exec, whichever
 * runtime is running.
 */
object WalletToolArgs {
    private val ALLOWED_FORMATS = setOf("sqlite", "bdb")

    /**
     * @param dataDir the tool's `-datadir`: a chroot path under PRoot, a real path when native.
     * @param dumpFile full path for `-dumpfile`, already resolved the way Core will see it.
     */
    fun build(
        dataDir: String,
        walletName: String,
        command: String,
        dumpFile: String? = null,
        walletFormat: String? = null,
        network: Network = Network.MAINNET
    ): List<String> {
        val guardedCommand = KeyExportGuard.requireWalletToolCommand(command)
        val args = mutableListOf("-datadir=$dataDir", "-wallet=$walletName")
        network.cliFlag()?.let { args += it }
        if (dumpFile != null) {
            args += "-dumpfile=$dumpFile"
        }
        if (walletFormat != null) {
            require(command == "createfromdump" && walletFormat in ALLOWED_FORMATS) {
                "Wallet format is only supported for createfromdump"
            }
            args += "-format=$walletFormat"
        }
        // The guard has already rejected any command that could export plaintext keys.
        args += guardedCommand
        return args
    }

    /** Convenience overload for callers that only know whether testnet is requested. */
    fun build(
        dataDir: String,
        walletName: String,
        command: String,
        dumpFile: String? = null,
        walletFormat: String? = null,
        testnet: Boolean
    ): List<String> = build(
        dataDir = dataDir,
        walletName = walletName,
        command = command,
        dumpFile = dumpFile,
        walletFormat = walletFormat,
        network = if (testnet) Network.TESTNET else Network.MAINNET
    )
}
