package org.stohncoin.wallet.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.stohncoin.wallet.runtime.EmbeddedLinuxRuntime

/**
 * High-level wallet operations delegated to Stohn Core JSON-RPC.
 *
 * Every amount crossing this boundary is a `Long` of satoshis; Core's decimal coin values are
 * converted once on the way in and once on the way out, never through `Double` arithmetic.
 */
class WalletRpc(private val runtime: EmbeddedLinuxRuntime, private val walletName: String? = null) {
    suspend fun walletInfo(): WalletInfoSnapshot = runtime.rpcCall("getwalletinfo", walletName = walletName).let { r ->
        WalletInfoSnapshot(
            balanceSatoshis = Amounts.fromJsonCoin(r.optDouble("balance", 0.0)),
            unconfirmedSatoshis = Amounts.fromJsonCoin(r.optDouble("unconfirmed_balance", 0.0)),
            immatureSatoshis = Amounts.fromJsonCoin(r.optDouble("immature_balance", 0.0)),
            txCount = r.optInt("txcount", 0),
            encrypted = r.has("unlocked_until")
        )
    }

    suspend fun newAddress(label: String = "Stohn Wallet"): String =
        runtime.rpcString("getnewaddress", JSONArray().put(label), walletName)

    data class FeeEstimate(
        val feeSatoshis: Long,
        val totalSatoshis: Long,
        val recipientSatoshis: Long
    )

    /** Uses Core's wallet coin-selection/funding path; never treats a fee-rate as a fee. */
    suspend fun estimateFee(address: String, amountSatoshis: Long, subtractFeeFromAmount: Boolean): FeeEstimate {
        require(address.isNotBlank()) { "Recipient address is required" }
        require(amountSatoshis in 1..Amounts.MAX_SATOSHIS) { "Amount must be a positive number of satoshis" }
        val options = JSONObject().put("replaceable", true).put("lockUnspents", false)
        if (subtractFeeFromAmount) options.put("subtractFeeFromOutputs", JSONArray().put(0))
        val outputs = JSONObject().put(address, Amounts.toJsonCoin(amountSatoshis))
        val result = runtime.rpcCall(
            "walletcreatefundedpsbt",
            JSONArray().put(JSONArray()).put(outputs).put(0).put(options).put(true),
            walletName
        )
        val rawFee = result.optDouble("fee", Double.NaN)
        require(rawFee.isFinite() && rawFee >= 0.0) { "Core did not return a valid transaction fee" }
        val fee = Amounts.fromJsonCoin(rawFee)
        val recipient = if (subtractFeeFromAmount) amountSatoshis - fee else amountSatoshis
        require(recipient > 0L) { "Amount is too small to cover the transaction fee" }
        return FeeEstimate(
            feeSatoshis = fee,
            totalSatoshis = if (subtractFeeFromAmount) amountSatoshis else amountSatoshis + fee,
            recipientSatoshis = recipient
        )
    }

    suspend fun send(address: String, amountSatoshis: Long, subtractFeeFromAmount: Boolean, comment: String = ""): String {
        require(address.isNotBlank()) { "Recipient address is required" }
        require(amountSatoshis in 1..Amounts.MAX_SATOSHIS) { "Amount must be a positive number of satoshis" }
        val params = JSONArray()
            .put(address)
            .put(Amounts.toJsonCoin(amountSatoshis))
            .put(comment)
            .put("")
            .put(subtractFeeFromAmount)
        return runtime.rpcString("sendtoaddress", params, walletName)
    }

    suspend fun transactions(count: Int = 25): List<WalletTransaction> {
        val result = runtime.rpcArray("listtransactions", JSONArray().put("*").put(count).put(0).put(true), walletName)
        return buildList {
            for (i in 0 until result.length()) result.optJSONObject(i)?.let { item ->
                WalletTransaction(
                    txid = item.optString("txid"),
                    category = item.optString("category"),
                    amountSatoshis = Amounts.fromJsonCoin(item.optDouble("amount", 0.0)),
                    feeSatoshis = Amounts.fromJsonCoin(item.optDouble("fee", 0.0)),
                    confirmations = item.optInt("confirmations", 0),
                    address = item.optString("address", ""),
                    time = item.optLong("timereceived", item.optLong("time", 0L))
                ).takeIf { it.txid.isNotBlank() }?.let(::add)
            }
        }
    }

    suspend fun encrypt(passphrase: CharArray): String {
        require(passphrase.isNotEmpty()) { "Passphrase is required" }
        try {
            return runtime.rpcString("encryptwallet", JSONArray().put(String(passphrase)), walletName)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun changePassphrase(oldPassphrase: CharArray, newPassphrase: CharArray) {
        require(oldPassphrase.isNotEmpty() && newPassphrase.isNotEmpty()) { "Passphrase is required" }
        try {
            runtime.rpcVoid("walletpassphrasechange", JSONArray().put(String(oldPassphrase)).put(String(newPassphrase)), walletName)
        } finally {
            oldPassphrase.fill('\u0000')
            newPassphrase.fill('\u0000')
        }
    }

    suspend fun unlock(passphrase: CharArray, seconds: Int = 120) {
        require(passphrase.isNotEmpty()) { "Passphrase is required" }
        try {
            runtime.rpcVoid("walletpassphrase", JSONArray().put(String(passphrase)).put(seconds), walletName)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun lock() = runtime.rpcVoid("walletlock", walletName = walletName)

    data class WalletInfoSnapshot(
        val balanceSatoshis: Long,
        val unconfirmedSatoshis: Long,
        val immatureSatoshis: Long,
        val txCount: Int,
        val encrypted: Boolean
    )

    data class WalletTransaction(
        val txid: String,
        val category: String,
        val amountSatoshis: Long,
        val feeSatoshis: Long,
        val confirmations: Int,
        val address: String,
        val time: Long
    )
}
