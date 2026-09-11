package org.stohncoin.wallet.wallet

import java.io.File

/** Import contract for official Stohn Core wallet databases (legacy BDB or SQLite). */
interface WalletMigration {
    suspend fun inspect(source: File): WalletInfo
    suspend fun import(source: File, passphrase: CharArray?, destination: File): ImportResult
}

data class WalletInfo(
    val format: String,
    val encrypted: Boolean,
    val descriptors: Boolean,
    val hd: Boolean
)

data class ImportResult(val destination: File, val addressesImported: Int)
