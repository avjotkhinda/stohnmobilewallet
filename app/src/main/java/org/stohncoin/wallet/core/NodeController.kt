package org.stohncoin.wallet.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.stohncoin.wallet.runtime.EmbeddedLinuxRuntime
import org.stohncoin.wallet.runtime.RuntimeBackend
import org.stohncoin.wallet.runtime.RuntimeBackendFactory
import org.stohncoin.wallet.runtime.RuntimeInstaller
import org.stohncoin.wallet.wallet.CoreWalletMigration
import org.stohncoin.wallet.wallet.ImportResult
import org.stohncoin.wallet.wallet.WalletInfo
import org.stohncoin.wallet.wallet.WalletRpc
import java.io.File

/** Process-wide owner for the single Full Mode node instance. */
class NodeController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val walletMutex = Mutex()
    private val prefs = appContext.getSharedPreferences("stohn_wallet", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var backend: RuntimeBackend? = null
    @Volatile private var activeWalletName: String? = prefs.getString(KEY_WALLET_NAME, null)
    private var startJob: Job? = null

    data class State(
        val status: Status = Status.STOPPED,
        val blocks: Long = 0,
        val headers: Long = 0,
        val peers: Int = 0,
        val initialBlockDownload: Boolean = true,
        val message: String = "Node stopped"
    )

    enum class Status { STOPPED, STARTING, SYNCING, READY, ERROR }

    fun start() {
        if (_state.value.status == Status.READY || _state.value.status == Status.SYNCING || _state.value.status == Status.STARTING) return
        val intent = Intent(appContext, NodeService::class.java)
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
            .onFailure { _state.value = State(status = Status.ERROR, message = it.message ?: "Unable to start node service") }
    }

    internal fun startCoreFromService() {
        if (startJob?.isActive == true || backend != null) return
        startJob = scope.launch {
            startMutex.withLock {
                if (backend != null) return@withLock
                try {
                    _state.value = State(status = Status.STARTING, message = "Installing Full Mode runtime…")
                    RuntimeInstaller.installIfNeeded(appContext)
                    _state.value = State(status = Status.STARTING, message = "Starting Stohn Core…")
                    val b = RuntimeBackendFactory.create(appContext)
                    b.start()
                    backend = b
                    waitForRpc(b)
                    restoreWallet(b)
                    poll(b)
                } catch (t: Throwable) {
                    runCatching { backend?.stop() }
                    backend = null
                    _state.value = State(status = Status.ERROR, message = t.message ?: "Unable to start Core")
                }
            }
        }
    }

    private suspend fun waitForRpc(b: RuntimeBackend) {
        repeat(60) {
            try {
                b.status()
                return
            } catch (_: Throwable) {
                delay(500)
            }
        }
        error("Stohn Core RPC did not become ready")
    }

    private suspend fun restoreWallet(b: RuntimeBackend) {
        val name = activeWalletName ?: return
        val embedded = b as? EmbeddedLinuxRuntime ?: return
        val loaded = runCatching { embedded.rpcArray("listwallets") }.getOrNull()
            ?.let { array -> (0 until array.length()).any { array.optString(it) == name } } == true
        if (!loaded) {
            embedded.rpcCall("loadwallet", org.json.JSONArray().put(name))
        }
    }

    private suspend fun poll(b: RuntimeBackend) {
        var failures = 0
        while (scope.coroutineContext.isActive && backend === b) {
            val info = try {
                b.status()
            } catch (t: Throwable) {
                failures++
                if (failures >= 20) throw t
                delay(1000)
                continue
            }
            failures = 0
            val ready = info.headers > 0 && info.blocks >= info.headers && !info.ibd
            _state.value = State(
                status = if (ready) Status.READY else Status.SYNCING,
                blocks = info.blocks,
                headers = info.headers,
                peers = info.peers,
                initialBlockDownload = info.ibd,
                message = if (ready) "Stohn Core is synchronized" else "Synchronizing Stohn blockchain…"
            )
            delay(3000)
        }
    }

    private fun embedded(): EmbeddedLinuxRuntime = backend as? EmbeddedLinuxRuntime ?: error("Full Mode node is not running")
    private fun walletRpc(): WalletRpc = WalletRpc(embedded(), activeWalletName)

    suspend fun walletInfo(): WalletRpc.WalletInfoSnapshot = walletMutex.withLock { walletRpc().walletInfo() }
    suspend fun newAddress(): String = walletMutex.withLock { walletRpc().newAddress() }
    suspend fun estimateFee(address: String, amount: Double, subtractFeeFromAmount: Boolean): WalletRpc.FeeEstimate =
        walletMutex.withLock { walletRpc().estimateFee(address, amount, subtractFeeFromAmount) }

    suspend fun send(address: String, amount: Double, passphrase: CharArray?, subtractFeeFromAmount: Boolean): String =
        walletMutex.withLock {
            val rpc = walletRpc()
            try {
                if (passphrase != null && passphrase.isNotEmpty()) rpc.unlock(passphrase)
                rpc.send(address, amount, subtractFeeFromAmount)
            } finally {
                if (passphrase != null && passphrase.isNotEmpty()) runCatching { rpc.lock() }
                passphrase?.fill('\u0000')
            }
        }

    suspend fun transactions(): List<WalletRpc.WalletTransaction> = walletMutex.withLock { walletRpc().transactions() }
    suspend fun lockWallet() = walletMutex.withLock { walletRpc().lock() }

    suspend fun setWalletPassphrase(newPassphrase: CharArray) = walletMutex.withLock {
        val b = embedded()
        try {
            walletRpc().encrypt(newPassphrase)
        } finally {
            newPassphrase.fill('\u0000')
        }
        restartCoreAfterWalletChange(b, "Wallet encrypted. Core is restarting…")
    }

    suspend fun changeWalletPassphrase(oldPassphrase: CharArray, newPassphrase: CharArray) = walletMutex.withLock {
        try {
            walletRpc().changePassphrase(oldPassphrase, newPassphrase)
        } finally {
            oldPassphrase.fill('\u0000')
            newPassphrase.fill('\u0000')
        }
    }

    private suspend fun restartCoreAfterWalletChange(b: EmbeddedLinuxRuntime, message: String) {
        startJob?.cancel()
        startJob = null
        backend = null
        _state.value = State(status = Status.STARTING, message = message)
        runCatching { b.stop() }
        delay(1200)
        startCoreFromService()
    }

    suspend fun inspectWalletDat(source: File): WalletInfo = CoreWalletMigration(embedded()).inspect(source)

    suspend fun importWalletDat(source: File, destination: File): ImportResult =
    walletMutex.withLock {
        val result = CoreWalletMigration(embedded()).import(source, null, destination)
        val walletName = destination.name
        embedded().rpcCall("loadwallet", org.json.JSONArray().put(walletName))
        activeWalletName = walletName
        prefs.edit().putString(KEY_WALLET_NAME, walletName).apply()
        result
    }

    fun stop() = appContext.stopService(Intent(appContext, NodeService::class.java))

    internal fun stopCoreFromService() {
        startJob?.cancel()
        startJob = null
        val b = backend
        backend = null
        scope.launch { runCatching { b?.stop() } }
        _state.value = State()
    }

    companion object {
        private const val KEY_WALLET_NAME = "active_wallet_name"
        @Volatile private var instance: NodeController? = null
        fun get(context: Context): NodeController = instance ?: synchronized(this) {
            instance ?: NodeController(context).also { instance = it }
        }
    }
}
