package org.stohncoin.wallet.core

import android.content.Context
import android.content.Intent
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import kotlinx.coroutines.withContext
import org.stohncoin.wallet.runtime.RuntimeBackend
import org.stohncoin.wallet.runtime.RuntimeBackendFactory
import org.stohncoin.wallet.runtime.RuntimeInstaller
import org.stohncoin.wallet.runtime.RuntimeManifest
import org.stohncoin.wallet.wallet.BackupManifest
import org.stohncoin.wallet.wallet.CoreWalletMigration
import org.stohncoin.wallet.wallet.WalletBackupManager
import org.stohncoin.wallet.wallet.WalletBackupVerifier
import org.stohncoin.wallet.wallet.BackupReminder
import org.stohncoin.wallet.wallet.BackupVerification
import org.stohncoin.wallet.wallet.ImportResult
import org.stohncoin.wallet.wallet.WalletInfo
import org.stohncoin.wallet.wallet.WalletRpc
import java.io.File

/** Process-wide owner for the single Full Mode node instance. */
class NodeController private constructor(context: Context) : SendGateway {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val walletMutex = Mutex()
    private val prefs = appContext.getSharedPreferences("stohn_wallet", Context.MODE_PRIVATE)
    private val dataDir = RuntimeBackend.dataDirectory(appContext)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _backupReminder = MutableStateFlow(readBackupReminder())
    val backupReminder: StateFlow<BackupReminder.State> = _backupReminder

    @Volatile private var backend: RuntimeBackend? = null
    @Volatile private var userStopped = false
    @Volatile private var activeWalletName: String? = prefs.getString(KEY_WALLET_NAME, null)
    private var startJob: Job? = null

    data class State(
        val status: Status = Status.STOPPED,
        val blocks: Long = 0,
        val headers: Long = 0,
        val peers: Int = 0,
        val initialBlockDownload: Boolean = true,
        /** Whole percent of headers validated, or null before Core reports any headers. */
        val syncPercent: Int? = null,
        val message: String = "Node stopped"
    ) {
        /** True while the node is up, which is when the user may ask it to stop. */
        val isRunning: Boolean
            get() = status == Status.STARTING || status == Status.SYNCING || status == Status.READY

        /** True while the node is down, which is when the user may ask it to start. */
        val canStart: Boolean
            get() = status == Status.STOPPED || status == Status.ERROR
    }

    enum class Status { STOPPED, STARTING, SYNCING, READY, STOPPING, ERROR }

    fun start() {
        if (_state.value.isRunning || _state.value.status == Status.STOPPING) return
        userStopped = false
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
                    val b = RuntimeBackendFactory.create(appContext, pruneMode().pruneMiB)
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
        val node = b
        val loadedWallets = node.rpcArray("listwallets")
        val savedName = activeWalletName
        if (savedName != null) {
            val alreadyLoaded = (0 until loadedWallets.length()).any { loadedWallets.optString(it) == savedName }
            if (!alreadyLoaded) node.rpcCall("loadwallet", org.json.JSONArray().put(savedName))
            return
        }

        if (loadedWallets.length() > 0) {
            setActiveWalletName(loadedWallets.optString(0))
            return
        }

        // Reuse a wallet left on disk before creating a new one; never silently replace it.
        val walletDirectory = node.rpcCall("listwalletdir").optJSONArray("wallets")
        if (walletDirectory != null && walletDirectory.length() > 0) {
            val name = walletDirectory.optJSONObject(0)?.optString("name").orEmpty()
            require(name.isNotBlank()) { "Core returned an invalid wallet directory entry" }
            node.rpcCall("loadwallet", org.json.JSONArray().put(name))
            setActiveWalletName(name)
            return
        }

        node.rpcCall("createwallet", org.json.JSONArray().put(DEFAULT_WALLET_NAME))
        setActiveWalletName(DEFAULT_WALLET_NAME)
    }

    private fun setActiveWalletName(name: String) {
        activeWalletName = name
        prefs.edit { putString(KEY_WALLET_NAME, name) }
    }

    private suspend fun poll(b: RuntimeBackend) {
        var failures = 0
        while (scope.coroutineContext.isActive && backend === b) {
            // A stop request wins: keep the last counters on screen but never flip the status back
            // to SYNCING or READY while the service is on its way out.
            if (_state.value.status == Status.STOPPING) {
                delay(500)
                continue
            }
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
                syncPercent = if (ready) 100 else SyncProgress.percent(info.blocks, info.headers),
                message = if (ready) "Stohn Core is synchronized" else "Synchronizing Stohn blockchain…"
            )
            delay(3000)
        }
    }

    private fun node(): RuntimeBackend = backend ?: error("Full Mode node is not running")
    private fun walletRpc(): WalletRpc = WalletRpc(node(), activeWalletName)

    suspend fun walletInfo(): WalletRpc.WalletInfoSnapshot = walletMutex.withLock { walletRpc().walletInfo() }
    suspend fun newAddress(): String = walletMutex.withLock { walletRpc().newAddress() }
    override suspend fun estimateFee(address: String, amountSatoshis: Long, subtractFeeFromAmount: Boolean, confTarget: Int?): WalletRpc.FeeEstimate =
        walletMutex.withLock { walletRpc().estimateFee(address, amountSatoshis, subtractFeeFromAmount, confTarget) }

    private suspend fun retrySend(
    address: String,
    amountSatoshis: Long,
    passphrase: CharArray?,
    subtractFeeFromAmount: Boolean,
    confTarget: Int?
): String {
        var delayMs = 500L
        for (attempt in 1..4) {
            try {
                return walletMutex.withLock {
                    val rpc = walletRpc()
                    try {
                        if (passphrase != null && passphrase.isNotEmpty()) rpc.unlock(passphrase)
                        val txid = rpc.send(address, amountSatoshis, subtractFeeFromAmount, confTarget = confTarget)
                        // A spend is when a stale backup starts losing real money, so count it.
                        recordSend()
                        txid
                    } finally {
                        if (passphrase != null && passphrase.isNotEmpty()) runCatching { rpc.lock() }
                        passphrase?.fill('\u0000')
                    }
                }
            } catch (e: Exception) {
                if (attempt == 4) throw e
                Thread.sleep(delayMs)
                delayMs = Math.min(delayMs * 2, 8000L)
            }
        }
        error("Unreachable")
    }

    override suspend fun send(address: String, amountSatoshis: Long, passphrase: CharArray?, subtractFeeFromAmount: Boolean, confTarget: Int?): String =
        retrySend(address, amountSatoshis, passphrase, subtractFeeFromAmount, confTarget)

    suspend fun transactions(): List<WalletRpc.WalletTransaction> = walletMutex.withLock { walletRpc().transactions() }
    suspend fun lockWallet() = walletMutex.withLock { walletRpc().lock() }

    suspend fun setWalletPassphrase(newPassphrase: CharArray) = walletMutex.withLock {
        val b = node()
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

    private suspend fun restartCoreAfterWalletChange(b: RuntimeBackend, message: String) {
        startJob?.cancel()
        startJob = null
        backend = null
        _state.value = State(status = Status.STARTING, message = message)
        runCatching { b.stop() }
        delay(1200)
        startCoreFromService()
    }

    suspend fun inspectWalletDat(source: File): WalletInfo = CoreWalletMigration(node()).inspect(source)

    suspend fun importWalletDat(source: File, destination: File): ImportResult =
    walletMutex.withLock {
        val result = CoreWalletMigration(node()).import(source, destination)
        val walletName = destination.name
        node().rpcCall("loadwallet", org.json.JSONArray().put(walletName))
        setActiveWalletName(walletName)
        result
    }

    suspend fun createWalletBackup(destination: File, password: CharArray): WalletBackupManager.BackupResult =
        walletMutex.withLock {
            val name = activeWalletName ?: DEFAULT_WALLET_NAME
            val result = WalletBackupManager(appContext, node()).createCoreBackup(
                destination,
                password,
                name,
                walletCreationHeight(name)
            )
            recordBackupCreated()
            result
        }

    suspend fun restoreWalletBackup(input: File, password: CharArray, destination: File): ImportResult =
        walletMutex.withLock {
            val restored = WalletBackupManager(appContext, node()).restoreCoreBackup(input, password, destination)
            val walletName = destination.name
            node().rpcCall("loadwallet", org.json.JSONArray().put(walletName))
            setActiveWalletName(walletName)
            // The creation height belongs to the wallet, not to this restore: carry it forward so
            // the next backup still says when the wallet began.
            restored.manifest.creationHeight?.let { rememberWalletCreationHeight(walletName, it) }
            restored.import
        }

    /**
     * Test-restore: proves a backup decrypts and holds a wallet Core can open, without touching
     * the live wallet or loading anything. Safe to run before a real restore.
     */
    suspend fun verifyWalletBackup(input: File, password: CharArray): BackupVerification =
        walletMutex.withLock {
            val verification = WalletBackupVerifier(appContext, backend).verify(input, password)
            // Compare what the backup claims with this device: a different wallet, a different
            // build, or a height the node has not reached yet are all worth saying out loud.
            verification.copy(
                notes = BackupManifest.notes(
                    BackupManifest(
                        appVersion = verification.appVersion,
                        walletName = verification.walletName,
                        creationHeight = verification.creationHeight,
                        walletSha256 = verification.walletSha256
                    ),
                    currentAppVersion = RuntimeManifest.APP_VERSION,
                    chainHeight = currentChainHeight(),
                    activeWalletName = activeWalletName
                )
            )
        }

    /** Chain height as the node reports it, or null when the node cannot answer. */
    private suspend fun currentChainHeight(): Long? = runCatching {
        node().rpcCall("getblockchaininfo").optLong("blocks", -1L).takeIf { it >= 0L }
    }.getOrNull()

    /**
     * The wallet's birthday on this device: remembered the first time this wallet is seen and
     * carried forward by restores, so every backup repeats one value instead of inventing a new one.
     */
    private suspend fun walletCreationHeight(walletName: String): Long {
        prefs.getLong(creationHeightKey(walletName), -1L).takeIf { it >= 0L }?.let { return it }
        val height = currentChainHeight()
            ?: error("The node did not report a chain height, so this wallet's creation height is unknown")
        rememberWalletCreationHeight(walletName, height)
        return height
    }

    /** Keeps the earliest height known for a wallet: a birthday only moves earlier, never later. */
    private fun rememberWalletCreationHeight(walletName: String, height: Long) {
        if (height < 0L) return
        val key = creationHeightKey(walletName)
        val current = prefs.getLong(key, -1L)
        if (current >= 0L && current <= height) return
        prefs.edit { putLong(key, height) }
    }

    private fun creationHeightKey(walletName: String) = KEY_CREATED_HEIGHT + walletName

    private fun readBackupReminder(): BackupReminder.State = BackupReminder.State(
        lastBackupAt = prefs.getLong(KEY_LAST_BACKUP_AT, 0L),
        sendsSinceBackup = prefs.getInt(KEY_SENDS_SINCE_BACKUP, 0)
    )

    private fun recordBackupCreated() {
        val now = System.currentTimeMillis()
        prefs.edit {
            putLong(KEY_LAST_BACKUP_AT, now)
            putInt(KEY_SENDS_SINCE_BACKUP, 0)
        }
        _backupReminder.value = BackupReminder.State(lastBackupAt = now, sendsSinceBackup = 0)
    }

    private fun recordSend() {
        val sends = prefs.getInt(KEY_SENDS_SINCE_BACKUP, 0) + 1
        prefs.edit { putInt(KEY_SENDS_SINCE_BACKUP, sends) }
        _backupReminder.value = _backupReminder.value.copy(sendsSinceBackup = sends)
    }

    /** The prune mode the next Core start will use. */
    fun pruneMode(): NodeStorage.Mode =
        NodeStorage.Mode.values().firstOrNull { it.name == prefs.getString(KEY_PRUNE_MODE, null) } ?: NodeStorage.Mode.FULL

    /** Bytes and files the node's data directory currently holds. */
    suspend fun chainUsage(): NodeStorage.Usage = withContext(Dispatchers.IO) { NodeStorage.measure(dataDir) }

    /** Free space on the volume the node writes to, or zero when the device will not say. */
    fun freeBytes(): Long = runCatching { StatFs(appContext.filesDir.absolutePath).availableBytes }.getOrDefault(0L)

    /**
     * Switches prune mode by clearing the chain database, which is the only way Core accepts a
     * different `-prune`. Wallet files, the config and the RPC cookie are untouched; the chain is
     * downloaded again on the next start. The node is stopped first so nothing writes into a
     * database that is about to be deleted.
     */
    suspend fun applyPruneMode(mode: NodeStorage.Mode): NodeStorage.Usage = withContext(Dispatchers.IO) {
        val previous = pruneMode()
        if (mode == previous) return@withContext NodeStorage.Usage(0L, 0)
        val wasRunning = _state.value.isRunning || _state.value.status == Status.STOPPING
        if (wasRunning) {
            stop()
            repeat(60) {
                if (_state.value.status == Status.STOPPED) return@repeat
                delay(250)
            }
            check(_state.value.status == Status.STOPPED) {
                "The node did not stop, so the chain database was left alone"
            }
            // The service clears the backend before Core's process is gone; give it a moment so the
            // delete never races a dying writer.
            delay(1200)
        }
        prefs.edit { putString(KEY_PRUNE_MODE, mode.name) }
        // A chain that could not be cleared must not leave a mode Core will refuse on next start.
        val freed = runCatching { NodeStorage.resetChainData(dataDir) }.getOrElse { failure ->
            prefs.edit { putString(KEY_PRUNE_MODE, previous.name) }
            if (wasRunning) start()
            throw failure
        }
        if (wasRunning) start()
        freed
    }

    /**
     * Asks the foreground service to stop. The service owns the runtime, so the shutdown itself
     * happens in [stopCoreFromService]; this only records the user's choice and shows it, so the
     * node screen cannot offer Start until Stop has finished.
     */
    fun stop() {
        if (!_state.value.isRunning) return
        userStopped = true
        _state.value = _state.value.copy(status = Status.STOPPING, message = "Stopping the local node…")
        val stopped = runCatching { appContext.stopService(Intent(appContext, NodeService::class.java)) }
            .getOrDefault(false)
        // stopService returns false when the service was never running (for example after the
        // system killed it), and then nothing else will release the backend.
        if (!stopped) stopCoreFromService()
    }

    /**
     * Auto-start on app open, unless the user has asked the node to stay stopped. A rotation or a
     * trip through a system dialog recreates the screen and re-runs this, so the choice has to
     * outlive the composition; it deliberately does not outlive the process.
     */
    fun startUnlessStopped() {
        if (userStopped) return
        start()
    }

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
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_SENDS_SINCE_BACKUP = "sends_since_backup"
        private const val KEY_CREATED_HEIGHT = "created_height:"
        private const val KEY_PRUNE_MODE = "prune_mode"
        private const val DEFAULT_WALLET_NAME = "stohn-wallet"
        @Volatile private var instance: NodeController? = null
        fun get(context: Context): NodeController = instance ?: synchronized(this) {
            instance ?: NodeController(context).also { instance = it }
        }
    }
}
