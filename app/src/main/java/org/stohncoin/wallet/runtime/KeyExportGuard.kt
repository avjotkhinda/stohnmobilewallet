package org.stohncoin.wallet.runtime

/**
 * Blocks every route by which Stohn Core could hand back unencrypted private key material.
 *
 * Android code never needs a private key: signing happens inside Core, a backup copies the
 * encrypted `wallet.dat`, and migration moves encrypted key records through the record dump.
 * Two Core entry points can still expose plaintext keys, so both are closed here instead of
 * being re-checked at each call site:
 *
 * - the wallet tool's `dumpwallet` subcommand, which writes a text file of WIF keys and seeds;
 * - the `dumpwallet`, `dumpprivkey`, `dumphdinfo` and `dumpmaster*` RPC methods.
 *
 * The wallet-tool side is an allowlist, so a subcommand added upstream cannot reach app storage
 * without a deliberate change here. `dump` stays allowed on purpose: it emits encrypted key
 * records, never plaintext, and the migration workspace holding it is deleted afterwards.
 *
 * Pure Kotlin with no Android dependencies, so the rule is unit-testable on the JVM.
 */
object KeyExportGuard {
    /** The only `stohncoin-wallet` subcommands the app may run. */
    val ALLOWED_WALLET_TOOL_COMMANDS = setOf("info", "dump", "createfromdump")

    /** RPC methods that return plaintext keys or write them outside Core's encrypted wallet. */
    val FORBIDDEN_RPC_METHODS = setOf(
        "dumpwallet",
        "dumpprivkey",
        "dumphdinfo",
        "dumpmasterprivkey",
        "dumpmasterkey"
    )

    fun requireWalletToolCommand(command: String): String {
        val normalized = command.trim()
        require(normalized in ALLOWED_WALLET_TOOL_COMMANDS) {
            "Wallet tool command '$normalized' is not permitted: the app never exports plaintext keys"
        }
        return normalized
    }

    fun requireRpcMethod(method: String): String {
        val normalized = method.trim()
        require(normalized.lowercase() !in FORBIDDEN_RPC_METHODS) {
            "RPC method '$normalized' is not permitted: the app never exports plaintext keys"
        }
        return normalized
    }
}
