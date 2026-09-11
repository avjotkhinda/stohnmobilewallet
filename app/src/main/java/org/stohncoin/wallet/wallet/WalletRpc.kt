package org.stohncoin.wallet.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.stohncoin.wallet.runtime.EmbeddedLinuxRuntime

/** High-level wallet operations delegated to Stohn Core JSON-RPC. */
class WalletRpc(private val runtime: EmbeddedLinuxRuntime, private val walletName: String? = null) {
    suspend fun walletInfo(): WalletInfoSnapshot = runtime.rpcCall("getwalletinfo", walletName = walletName).let { r ->
        WalletInfoSnapshot(
            balance = r.optDouble("balance", 0.0),
            unconfirmed = r.optDouble("unconfirmed_balance", 0.0),
            immature = r.optDouble("immature_balance", 0.0),
            txCount = r.optInt("txcount", 0),
            encrypted = r.has("unlocked_until")
        )
    }

    suspend fun newAddress(label: String = "Stohn Wallet"): String =
        runtime.rpcString("getnewaddress", JSONArray().put(label), walletName)

    data class FeeEstimate(val fee: Double, val total: Double, val recipientAmount: Double)

    /** Uses Core's wallet coin-selection/funding path; never treats a fee-rate as a fee. */
    suspend fun estimateFee(address: String, amount: Double, subtractFeeFromAmount: Boolean): FeeEstimate {
        require(address.isNotBlank()) { "Recipient address is required" }
        require(amount.isFinite() && amount > 0.0) { "Amount must be a finite positive number" }
        val options = JSONObject().put("replaceable", true).put("lockUnspents", false)
        if (subtractFeeFromAmount) options.put("subtractFeeFromOutputs", JSONArray().put(0))
        val outputs = JSONObject().put(address, amount)
        val result = runtime.rpcCall(
            "walletcreatefundedpsbt",
            JSONArray().put(JSONArray()).put(outputs).put(0).put(options).put(true),
            walletName
        )
        val fee = result.optDouble("fee", Double.NaN)
        require(fee.isFinite() && fee >= 0.0) { "Core did not return a valid transaction fee" }
        val recipient = if (subtractFeeFromAmount) amount - fee else amount
        require(recipient >= 0.0) { "Amount is too small to cover the transaction fee" }
        return FeeEstimate(fee = fee, total = if (subtractFeeFromAmount) amount else amount + fee, recipientAmount = recipient)
    }

    suspend fun send(address: String, amount: Double, subtractFeeFromAmount: Boolean, comment: String = ""): String {
        require(address.isNotBlank()) { "Recipient address is required" }
        require(amount.isFinite() && amount > 0.0) { "Amount must be a finite positive number" }
        val params = JSONArray()
            .put(address)
            .put(amount)
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
                    amount = item.optDouble("amount", 0.0),
                    fee = item.optDouble("fee", 0.0),
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
        val balance: Double,
        val unconfirmed: Double,
        val immature: Double,
        val txCount: Int,
        val encrypted: Boolean
    )

    data class WalletTransaction(
        val txid: String,
        val category: String,
        val amount: Double,
        val fee: Double,
        val confirmations: Int,
        val address: String,
        val time: Long
    )
}
