package org.stohncoin.wallet.wallet

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stohncoin.wallet.runtime.RuntimeBackend

/**
 * Exercises [WalletRpc] against a scripted runtime, so the assertions are about the JSON-RPC
 * contract Core sees and the values that come back — never about a live node.
 */
class WalletRpcTest {
    /** Records every call and answers from lambdas, so both halves of the boundary are testable. */
    private class FakeRuntime : RuntimeBackend {
        data class Call(val method: String, val params: JSONArray, val walletName: String?)

        val calls = mutableListOf<Call>()
        var objectReply: (String) -> JSONObject = { JSONObject() }
        var stringReply: String = ""
        var arrayReply: JSONArray = JSONArray()
        var failure: Throwable? = null

        fun method(): String = calls.last().method
        fun params(): JSONArray = calls.last().params

        private fun record(method: String, params: JSONArray, walletName: String?) {
            calls += Call(method, params, walletName)
            failure?.let { throw it }
        }

        override val dataDir: File = File("build/tmp/wallet-rpc-fake")
        override val rpcPort: Int = 0

        override fun corePath(file: File): String = file.absolutePath
        override suspend fun start() = Unit
        override suspend fun stop() = Unit

        override fun runWalletTool(
            workspace: File,
            walletName: String,
            command: String,
            dumpFile: File?,
            walletFormat: String?
        ): RuntimeBackend.ToolResult = error("WalletRpc must not shell out to the wallet tool")

        override suspend fun rpcCall(method: String, params: JSONArray, walletName: String?): JSONObject {
            record(method, params, walletName)
            return objectReply(method)
        }

        override suspend fun rpcString(method: String, params: JSONArray, walletName: String?): String {
            record(method, params, walletName)
            return stringReply
        }

        override suspend fun rpcArray(method: String, params: JSONArray, walletName: String?): JSONArray {
            record(method, params, walletName)
            return arrayReply
        }

        override suspend fun rpcVoid(method: String, params: JSONArray, walletName: String?) {
            record(method, params, walletName)
        }
    }

    private val runtime = FakeRuntime()
    private val address = "SaddrRecipient"

    // ---- wallet info ---------------------------------------------------------------------

    @Test
    fun `walletInfo converts every coin amount to satoshis exactly`() = runBlocking {
        runtime.objectReply = {
            JSONObject()
                .put("balance", 1.23456789)
                .put("unconfirmed_balance", 0.00000001)
                .put("immature_balance", 0)
                .put("txcount", 42)
                .put("unlocked_until", 1794240000)
        }

        val info = WalletRpc(runtime).walletInfo()

        assertEquals("getwalletinfo", runtime.method())
        assertEquals(123_456_789L, info.balanceSatoshis)
        assertEquals(1L, info.unconfirmedSatoshis)
        assertEquals(0L, info.immatureSatoshis)
        assertEquals(42, info.txCount)
        assertTrue("unlocked_until marks an encrypted wallet", info.encrypted)
    }

    @Test
    fun `walletInfo treats a wallet without unlocked_until as unencrypted`() = runBlocking {
        runtime.objectReply = { JSONObject().put("balance", 0.5) }

        val info = WalletRpc(runtime).walletInfo()

        assertEquals(50_000_000L, info.balanceSatoshis)
        assertEquals(0, info.txCount)
        assertFalse(info.encrypted)
    }

    @Test
    fun `walletInfo passes the wallet name through`() = runBlocking {
        WalletRpc(runtime, "stohn-wallet").walletInfo()

        assertEquals("stohn-wallet", runtime.calls.single().walletName)
    }

    // ---- addresses -----------------------------------------------------------------------

    @Test
    fun `newAddress sends the label and defaults to the wallet name`() = runBlocking {
        runtime.stringReply = "SaddrFresh"

        val fresh = WalletRpc(runtime).newAddress()

        assertEquals("getnewaddress", runtime.method())
        assertEquals("Stohn Wallet", runtime.params().getString(0))
        assertNull("no wallet name was given, so Core uses the default", runtime.calls.single().walletName)
        assertEquals("SaddrFresh", fresh)
    }

    @Test
    fun `newAddress honours an explicit label`() = runBlocking {
        WalletRpc(runtime, "stohn-wallet").newAddress("Savings")

        assertEquals("Savings", runtime.params().getString(0))
        assertEquals("stohn-wallet", runtime.calls.single().walletName)
    }

    // ---- fee estimation ------------------------------------------------------------------

    @Test
    fun `estimateFee asks Core to fund the transaction and adds the fee on top`() = runBlocking {
        runtime.objectReply = { JSONObject().put("fee", 0.00002000) }

        val estimate = WalletRpc(runtime).estimateFee(address, 100_000L, subtractFeeFromAmount = false)

        assertEquals("walletcreatefundedpsbt", runtime.method())
        val params = runtime.params()
        assertEquals(5, params.length())
        assertEquals(0, params.getJSONArray(0).length())
        assertEquals(100_000L, Amounts.fromJsonCoin(params.getJSONObject(1).getDouble(address)))
        assertEquals(0, params.getInt(2))
        val options = params.getJSONObject(3)
        assertTrue(options.getBoolean("replaceable"))
        assertFalse(options.getBoolean("lockUnspents"))
        assertFalse("no fee is taken out of the amount", options.has("subtractFeeFromOutputs"))
        assertFalse("no confirmation target means no feerate opinion", options.has("conf_target"))
        assertTrue("Core should replace rather than accumulate", params.getBoolean(4))

        assertEquals(2_000L, estimate.feeSatoshis)
        assertEquals(100_000L, estimate.recipientSatoshis)
        assertEquals(102_000L, estimate.totalSatoshis)
    }

    @Test
    fun `estimateFee subtracts the fee from the amount when asked`() = runBlocking {
        runtime.objectReply = { JSONObject().put("fee", 0.00002000) }

        val estimate = WalletRpc(runtime).estimateFee(address, 100_000L, subtractFeeFromAmount = true)

        assertEquals(JSONArray().put(0).toString(), runtime.params().getJSONObject(3).getJSONArray("subtractFeeFromOutputs").toString())
        assertEquals(2_000L, estimate.feeSatoshis)
        assertEquals(98_000L, estimate.recipientSatoshis)
        assertEquals("the total stays the amount the user typed", 100_000L, estimate.totalSatoshis)
    }

    @Test
    fun `estimateFee expresses the confirmation target the way Core documents it`() = runBlocking {
        runtime.objectReply = { JSONObject().put("fee", 0.00001) }

        WalletRpc(runtime).estimateFee(address, 50_000L, subtractFeeFromAmount = false, confTarget = 6)

        val options = runtime.params().getJSONObject(3)
        assertEquals(6, options.getInt("conf_target"))
        assertEquals("conservative", options.getString("estimate_mode"))
    }

    @Test
    fun `estimateFee ignores a nonsensical confirmation target instead of sending it`() = runBlocking {
        runtime.objectReply = { JSONObject().put("fee", 0.00001) }

        WalletRpc(runtime).estimateFee(address, 50_000L, subtractFeeFromAmount = false, confTarget = 0)

        assertFalse(runtime.params().getJSONObject(3).has("conf_target"))
    }

    @Test
    fun `estimateFee rejects a blank address before any RPC happens`() {
        expectFailure("Recipient address is required") {
            runBlocking { WalletRpc(runtime).estimateFee("  ", 1_000L, subtractFeeFromAmount = false) }
        }
        assertTrue("a rejected request must not reach Core", runtime.calls.isEmpty())
    }

    @Test
    fun `estimateFee rejects amounts it cannot represent`() {
        expectFailure("Amount must be a positive number of satoshis") {
            runBlocking { WalletRpc(runtime).estimateFee(address, 0L, subtractFeeFromAmount = false) }
        }
        expectFailure("Amount must be a positive number of satoshis") {
            runBlocking { WalletRpc(runtime).estimateFee(address, Amounts.MAX_SATOSHIS + 1, subtractFeeFromAmount = false) }
        }
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `estimateFee refuses a missing or negative fee from Core`() {
        runtime.objectReply = { JSONObject() }
        expectFailure("Core did not return a valid transaction fee") {
            runBlocking { WalletRpc(runtime).estimateFee(address, 1_000L, subtractFeeFromAmount = false) }
        }

        runtime.objectReply = { JSONObject().put("fee", -0.00001) }
        expectFailure("Core did not return a valid transaction fee") {
            runBlocking { WalletRpc(runtime).estimateFee(address, 1_000L, subtractFeeFromAmount = false) }
        }
    }

    @Test
    fun `estimateFee refuses an amount the fee would swallow`() {
        runtime.objectReply = { JSONObject().put("fee", 0.00002000) }

        expectFailure("Amount is too small to cover the transaction fee") {
            runBlocking { WalletRpc(runtime).estimateFee(address, 2_000L, subtractFeeFromAmount = true) }
        }
    }

    // ---- sending -------------------------------------------------------------------------

    @Test
    fun `send passes the documented argument order and returns the txid`() = runBlocking {
        runtime.stringReply = "txid-abc"

        val txid = WalletRpc(runtime).send(address, 25_000L, subtractFeeFromAmount = false, comment = "lunch")

        assertEquals("sendtoaddress", runtime.method())
        val params = runtime.params()
        assertEquals("no confirmation target means the legacy five-argument form", 5, params.length())
        assertEquals(address, params.getString(0))
        assertEquals(25_000L, Amounts.fromJsonCoin(params.getDouble(1)))
        assertEquals("lunch", params.getString(2))
        assertEquals("", params.getString(3))
        assertFalse(params.getBoolean(4))
        assertEquals("txid-abc", txid)
    }

    @Test
    fun `send extends the arguments with the feerate options when a target is given`() = runBlocking {
        runtime.stringReply = "txid-def"

        val txid = WalletRpc(runtime).send(address, 25_000L, subtractFeeFromAmount = true, confTarget = 2)

        val params = runtime.params()
        assertEquals(8, params.length())
        assertTrue(params.getBoolean(4))
        assertTrue(params.getBoolean(5))
        assertEquals(2, params.getInt(6))
        assertEquals("conservative", params.getString(7))
        assertEquals("txid-def", txid)
    }

    @Test
    fun `send refuses an address or amount it cannot use`() {
        expectFailure("Recipient address is required") {
            runBlocking { WalletRpc(runtime).send("", 1_000L, subtractFeeFromAmount = false) }
        }
        expectFailure("Amount must be a positive number of satoshis") {
            runBlocking { WalletRpc(runtime).send(address, -1L, subtractFeeFromAmount = false) }
        }
        assertTrue(runtime.calls.isEmpty())
    }

    // ---- transactions --------------------------------------------------------------------

    @Test
    fun `transactions maps Core's fields and skips entries without a txid`() = runBlocking {
        runtime.arrayReply = JSONArray()
            .put(
                JSONObject()
                    .put("txid", "aaaa")
                    .put("category", "receive")
                    .put("amount", 1.5)
                    .put("confirmations", 12)
                    .put("address", "SaddrAlice")
                    .put("timereceived", 1794240000L)
            )
            .put(JSONObject().put("txid", "").put("amount", 9.0))
            .put(
                JSONObject()
                    .put("txid", "bbbb")
                    .put("category", "send")
                    .put("amount", -0.25)
                    .put("fee", -0.00001)
                    .put("time", 1794000000L)
            )

        val transactions = WalletRpc(runtime).transactions(count = 7)

        assertEquals("listtransactions", runtime.method())
        val params = runtime.params()
        assertEquals("*", params.getString(0))
        assertEquals(7, params.getInt(1))
        assertEquals(0, params.getInt(2))
        assertTrue(params.getBoolean(3))

        assertEquals("an entry with no txid is not a transaction", 2, transactions.size)
        val received = transactions[0]
        assertEquals("aaaa", received.txid)
        assertEquals("receive", received.category)
        assertEquals(150_000_000L, received.amountSatoshis)
        assertEquals(0L, received.feeSatoshis)
        assertEquals(12, received.confirmations)
        assertEquals("SaddrAlice", received.address)
        assertEquals(1794240000L, received.time)

        val sent = transactions[1]
        assertEquals("bbbb", sent.txid)
        assertEquals(-25_000_000L, sent.amountSatoshis)
        assertEquals(-1_000L, sent.feeSatoshis)
        assertEquals("", sent.address)
        assertEquals("time is the fallback when timereceived is absent", 1794000000L, sent.time)
    }

    @Test
    fun `transactions returns an empty list for an empty wallet`() = runBlocking {
        runtime.arrayReply = JSONArray()

        assertTrue(WalletRpc(runtime).transactions().isEmpty())
    }

    // ---- passphrases ---------------------------------------------------------------------

    @Test
    fun `encrypt hands Core the passphrase and wipes the caller's copy`() = runBlocking {
        runtime.stringReply = "wallet encrypted"
        val passphrase = "hunter2".toCharArray()

        val message = WalletRpc(runtime).encrypt(passphrase)

        assertEquals("encryptwallet", runtime.method())
        assertEquals("hunter2", runtime.params().getString(0))
        assertEquals("wallet encrypted", message)
        assertWiped(passphrase)
    }

    @Test
    fun `encrypt rejects an empty passphrase`() {
        expectFailure("Passphrase is required") {
            runBlocking { WalletRpc(runtime).encrypt(CharArray(0)) }
        }
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `encrypt wipes the passphrase even when Core fails`() {
        runtime.failure = IllegalStateException("Core is not running")
        val passphrase = "hunter2".toCharArray()

        expectFailure("Core is not running") {
            runBlocking { WalletRpc(runtime).encrypt(passphrase) }
        }

        assertWiped(passphrase)
    }

    @Test
    fun `changePassphrase sends both passphrases and wipes both`() = runBlocking {
        val old = "old-pass".toCharArray()
        val new = "new-pass".toCharArray()

        WalletRpc(runtime).changePassphrase(old, new)

        assertEquals("walletpassphrasechange", runtime.method())
        assertEquals("old-pass", runtime.params().getString(0))
        assertEquals("new-pass", runtime.params().getString(1))
        assertWiped(old)
        assertWiped(new)
    }

    @Test
    fun `changePassphrase wipes both passphrases even when Core fails`() {
        runtime.failure = IllegalStateException("Core rejected the passphrase")
        val old = "old-pass".toCharArray()
        val new = "new-pass".toCharArray()

        expectFailure("Core rejected the passphrase") {
            runBlocking { WalletRpc(runtime).changePassphrase(old, new) }
        }

        assertWiped(old)
        assertWiped(new)
    }

    @Test
    fun `changePassphrase rejects an empty side`() {
        expectFailure("Passphrase is required") {
            runBlocking { WalletRpc(runtime).changePassphrase("old".toCharArray(), CharArray(0)) }
        }
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `unlock passes the duration and wipes the passphrase`() = runBlocking {
        val passphrase = "hunter2".toCharArray()

        WalletRpc(runtime).unlock(passphrase, seconds = 600)

        assertEquals("walletpassphrase", runtime.method())
        assertEquals("hunter2", runtime.params().getString(0))
        assertEquals(600, runtime.params().getInt(1))
        assertWiped(passphrase)
    }

    @Test
    fun `unlock defaults to two minutes and wipes on failure`() {
        runtime.failure = IllegalStateException("Core is not running")
        val passphrase = "hunter2".toCharArray()

        expectFailure("Core is not running") { runBlocking { WalletRpc(runtime).unlock(passphrase) } }

        assertEquals(120, runtime.params().getInt(1))
        assertWiped(passphrase)
    }

    @Test
    fun `lock sends no passphrase`() = runBlocking {
        WalletRpc(runtime, "stohn-wallet").lock()

        assertEquals("walletlock", runtime.method())
        assertEquals(0, runtime.params().length())
        assertEquals("stohn-wallet", runtime.calls.single().walletName)
    }

    // ---- helpers -------------------------------------------------------------------------

    /** No passphrase may survive a call, whether it succeeded or threw. */
    private fun assertWiped(passphrase: CharArray) {
        assertTrue(
            "the passphrase was left in memory",
            passphrase.all { it == '\u0000' }
        )
    }

    private fun expectFailure(expected: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
            ?: error("expected a failure containing \"$expected\"")
        assertTrue(
            "expected \"$expected\" but got \"${failure.message}\"",
            failure.message.orEmpty().contains(expected)
        )
    }

    @Test
    fun `send refuses an amount it cannot represent`() {
        expectFailure("Amount must be a positive number of satoshis") {
            runBlocking { WalletRpc(runtime).send(address, 0L, subtractFeeFromAmount = false) }
        }
        expectFailure("Amount must be a positive number of satoshis") {
            runBlocking { WalletRpc(runtime).send(address, Amounts.MAX_SATOSHIS + 1, subtractFeeFromAmount = false) }
        }
        assertTrue("a rejected send must not reach Core", runtime.calls.isEmpty())
    }

    @Test
    fun `transactions skips entries Core did not send as objects`() {
        // A listtransactions answer can carry a non-object entry; that entry is not a transaction and
        // must not become an empty row.
        runtime.arrayReply = JSONArray()
            .put(JSONObject().put("txid", "tx1").put("amount", 1.5))
            .put("not-a-transaction")
            .put(JSONObject.NULL)

        val transactions = runBlocking { WalletRpc(runtime).transactions() }

        assertEquals(1, transactions.size)
        assertEquals("tx1", transactions.single().txid)
        assertEquals(150_000_000L, transactions.single().amountSatoshis)
    }

    @Test
    fun `changePassphrase requires both passphrases`() {
        expectFailure("Passphrase is required") {
            runBlocking { WalletRpc(runtime).changePassphrase(CharArray(0), "new".toCharArray()) }
        }
        expectFailure("Passphrase is required") {
            runBlocking { WalletRpc(runtime).changePassphrase("old".toCharArray(), CharArray(0)) }
        }
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `unlock asks Core for a timed unlock and wipes the passphrase`() {
        val passphrase = "secret".toCharArray()

        runBlocking { WalletRpc(runtime, "stohn-wallet").unlock(passphrase, seconds = 600) }

        assertEquals("walletpassphrase", runtime.method())
        assertEquals("secret", runtime.params().getString(0))
        assertEquals(600, runtime.params().getInt(1))
        assertEquals("stohn-wallet", runtime.calls.last().walletName)
        assertTrue("the passphrase must not stay in memory", passphrase.all { it == '\u0000' })
    }

    @Test
    fun `unlock refuses an empty passphrase`() {
        expectFailure("Passphrase is required") {
            runBlocking { WalletRpc(runtime).unlock(CharArray(0)) }
        }
        assertTrue(runtime.calls.isEmpty())
    }
}
