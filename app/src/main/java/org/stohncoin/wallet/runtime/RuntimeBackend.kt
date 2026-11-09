package org.stohncoin.wallet.runtime

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The seam between the app and its local node.
 *
 * Two backends implement it: [EmbeddedLinuxRuntime] runs the glibc Stohn Core through the embedded
 * Debian rootfs under PRoot, and [NativeRuntime] runs a Stohn Core built for Android directly.
 * Everything else in the app — the node controller, wallet RPC, backups, migration — is written
 * against this interface only, so changing which runtime runs underneath never touches a call site.
 */
interface RuntimeBackend {
    data class NodeInfo(val blocks: Long, val headers: Long, val peers: Int, val ibd: Boolean, val version: String = "unknown")
    data class ToolResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** Chain, wallets, the config file and the RPC cookie all live under this directory. */
    val dataDir: File

    /** The loopback port Core was started on; 0 until [start] has chosen one. */
    val rpcPort: Int

    /**
     * How Core sees [file]. The glibc runtime bind-mounts the data directory into its chroot, so a
     * path on this side of the boundary is not the same path on Core's side; a native runtime has
     * no boundary to cross and answers with the real path.
     */
    fun corePath(file: File): String

    suspend fun start()
    suspend fun stop()

    suspend fun status(): NodeInfo = withContext(Dispatchers.IO) {
        val rpc = LocalRpc(dataDir, rpcPort)
        val chain = rpc.objectResult("getblockchaininfo")
        val network = rpc.objectResult("getnetworkinfo")
        NodeInfo(
            blocks = chain.optLong("blocks", 0),
            headers = chain.optLong("headers", 0),
            peers = network.optInt("connections", 0),
            ibd = chain.optBoolean("initialblockdownload", true),
            version = network.optString("subversion", "unknown")
        )
    }

    suspend fun rpcCall(method: String, params: org.json.JSONArray = org.json.JSONArray(), walletName: String? = null): org.json.JSONObject =
        withContext(Dispatchers.IO) { LocalRpc(dataDir, rpcPort, walletName).objectResult(method, params) }

    suspend fun rpcString(method: String, params: org.json.JSONArray = org.json.JSONArray(), walletName: String? = null): String =
        withContext(Dispatchers.IO) { LocalRpc(dataDir, rpcPort, walletName).stringResult(method, params) }

    suspend fun rpcArray(method: String, params: org.json.JSONArray = org.json.JSONArray(), walletName: String? = null): org.json.JSONArray =
        withContext(Dispatchers.IO) { LocalRpc(dataDir, rpcPort, walletName).arrayResult(method, params) }

    suspend fun rpcVoid(method: String, params: org.json.JSONArray = org.json.JSONArray(), walletName: String? = null) {
        withContext(Dispatchers.IO) { LocalRpc(dataDir, rpcPort, walletName).voidResult(method, params) }
    }

    /** A fresh workspace for the official wallet tool: one per migration, never the live wallet. */
    fun createWalletToolWorkspace(prefix: String): File {
        val dir = File(dataDir, "migration-$prefix-${UUID.randomUUID()}")
        check(dir.mkdirs()) { "Unable to create wallet migration workspace" }
        return dir
    }

    /**
     * Runs the official wallet tool in [workspace]. The plaintext key-export guard lives in
     * [WalletToolArgs], which every backend builds its arguments through, so no launcher can reach
     * Core with a command that would write keys to app storage.
     */
    fun runWalletTool(
        workspace: File,
        walletName: String,
        command: String,
        dumpFile: File? = null,
        walletFormat: String? = null
    ): ToolResult

    /** Asks Core to snapshot the wallet it has loaded, and returns where the snapshot landed. */
    suspend fun backupWalletSnapshot(snapshotName: String, walletName: String? = null): File =
        withContext(Dispatchers.IO) {
            require(snapshotName.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid backup snapshot name" }
            val snapshot = File(dataDir, "backup-$snapshotName.dat")
            if (snapshot.exists()) snapshot.delete()
            val rpc = LocalRpc(dataDir, rpcPort, walletName)
            rpc.objectResult("backupwallet", org.json.JSONArray().put(corePath(snapshot)))
            check(snapshot.isFile && snapshot.length() > 0L) { "Core did not create a backup snapshot" }
            snapshot
        }

    companion object {
        /** The node data directory, shared with the controller so both sides measure the same tree. */
        fun dataDirectory(context: Context): File = File(File(context.filesDir, "fullmode"), "stohn-data")

        /** Core's configuration: loopback-only RPC, single instance, no daemon fork. */
        fun coreConfig() = """
            server=1
            daemon=0
            listen=1
            txindex=1
            rpcbind=127.0.0.1
            rpcallowip=127.0.0.1
            # RPC port is chosen randomly per start and passed with -rpcport on the CLI.
        """.trimIndent()

        /** Binds loopback port 0 so the kernel picks a free private port, then releases it for Core. */
        fun reserveRpcPort(): Int {
            java.net.ServerSocket().use { socket ->
                socket.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0))
                require(socket.localPort in 1024..65535) { "Core RPC port selection failed" }
                return socket.localPort
            }
        }
    }
}

/** SHA-256 of a runtime artifact as lowercase hex — one implementation behind every pin check. */
fun artifactSha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Chooses the backend for this APK.
 *
 * The native backend only ever wins on a verified pair of binaries: a malformed pin, a missing
 * binary or a hash mismatch throws instead of quietly starting the other runtime, because a swap
 * nobody notices is exactly what the pins exist to prevent.
 */
object RuntimeBackendFactory {
    fun create(context: Context, pruneMiB: Int = 0): RuntimeBackend {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val pins = SignedRuntimeManifest.effective(context)
        val decision = NativeLaunch.decide(
            core = File(nativeDir, NativeLaunch.CORE_LIB),
            walletTool = File(nativeDir, NativeLaunch.WALLET_TOOL_LIB),
            corePin = pins.nativeCoreSha256,
            walletToolPin = pins.nativeWalletToolSha256
        )
        return when (decision) {
            is NativeLaunch.Ready -> NativeRuntime(context, pruneMiB, pins)
            NativeLaunch.NoNativeBuild -> EmbeddedLinuxRuntime(context, pruneMiB)
            else -> error(decision.refusal)
        }
    }
}
