package org.stohncoin.wallet.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the glibc Stohn Core through an embedded Linux userspace. Nothing is installed as a
 * separate Termux/Debian app. The runtime lives entirely inside this application's private files.
 *
 * This is the runtime every APK ships today. [NativeRuntime] takes over from it the moment a
 * pinned bionic build is published; the interface is identical, so the app cannot tell the two
 * apart — only [RuntimeBackendFactory] decides which one starts.
 */
class EmbeddedLinuxRuntime(private val context: Context, private val pruneMiB: Int = 0) : RuntimeBackend {
    private val root = File(context.filesDir, "fullmode")
    private val runtime = File(root, "runtime")
    private val rootfs = File(runtime, "rootfs")
    private val coreBin = File(runtime, "bin/stohncoind")
    private val walletTool = File(runtime, "bin/stohncoin-wallet")
    override val dataDir: File = RuntimeBackend.dataDirectory(context)
    private val conf = File(dataDir, "stohn.conf")
    private var process: Process? = null
    // RPC listens on a fresh random loopback port for every start so another app on the
    // device cannot pre-bind a fixed, publicly known port. A caller (or attacker) that wins
    // the bind race simply prevents Core from starting; the readiness check then fails.
    @Volatile
    override var rpcPort: Int = 0
        private set
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libproot-loader.so")
    private val libTalloc = File(nativeDir, "libtalloc.so")
    private val libShmem = File(nativeDir, "libandroid-shmem.so")
    private val dnsConfig = File(dataDir, "resolv.conf")

    /** The data directory is bound into the chroot at a fixed path, so that is the path Core sees. */
    override fun corePath(file: File): String = "/home/stohn/.stohn/${file.name}"

    override suspend fun start() = withContext(Dispatchers.IO) {
        root.mkdirs(); runtime.mkdirs(); dataDir.mkdirs()
        check(rootfs.isDirectory) { "Embedded Debian rootfs is missing" }
        check(coreBin.isFile) { "Stohn Core binary is missing" }
        coreBin.setExecutable(true)
        if (!conf.exists()) conf.writeText(RuntimeBackend.coreConfig())
        writeDnsConfig()
        check(proot.isFile) { "Embedded Android PRoot is missing from nativeLibraryDir" }
        check(loader.isFile) { "Embedded PRoot loader is missing from nativeLibraryDir" }
        check(libTalloc.isFile) { "Embedded libtalloc is missing from nativeLibraryDir" }
        check(libShmem.isFile) { "Embedded libandroid-shmem is missing from nativeLibraryDir" }
        check(RuntimeManifest.PROOT_SHA256.matches(Regex("[0-9a-f]{64}"))) { "PRoot SHA-256 is not pinned" }
        check(RuntimeManifest.PROOT_LOADER_SHA256.matches(Regex("[0-9a-f]{64}"))) { "PRoot loader SHA-256 is not pinned" }
        check(RuntimeManifest.LIBTALLOC_SHA256.matches(Regex("[0-9a-f]{64}"))) { "libtalloc SHA-256 is not pinned" }
        check(RuntimeManifest.LIBANDROID_SHMEM_SHA256.matches(Regex("[0-9a-f]{64}"))) { "libandroid-shmem SHA-256 is not pinned" }
        check(artifactSha256(proot) == RuntimeManifest.PROOT_SHA256) { "Bundled PRoot integrity check failed" }
        check(artifactSha256(loader) == RuntimeManifest.PROOT_LOADER_SHA256) { "Bundled PRoot loader integrity check failed" }
        check(artifactSha256(libTalloc) == RuntimeManifest.LIBTALLOC_SHA256) { "Bundled libtalloc integrity check failed" }
        check(artifactSha256(libShmem) == RuntimeManifest.LIBANDROID_SHMEM_SHA256) { "Bundled libandroid-shmem integrity check failed" }
        rpcPort = RuntimeBackend.reserveRpcPort()
        // PRoot unpacks its loader into PROOT_TMP_DIR on every run, so it gets a directory of its
        // own and leftover copies can be deleted without touching other cache entries.
        val tmp = RuntimeTemp.prepare(context.cacheDir)
        process = try {
            ProcessBuilder(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-b", dataDir.absolutePath + ":/home/stohn/.stohn",
            "-b", runtime.absolutePath + ":/opt/stohn",
            "-b", dnsConfig.absolutePath + ":/etc/resolv.conf",
            "/opt/stohn/bin/stohncoind",
            "-conf=/home/stohn/.stohn/stohn.conf",
            "-datadir=/home/stohn/.stohn",
            "-rpcport=$rpcPort",
            "-daemon=0",
            // Prune depth is fixed for the life of a chain database; NodeStorage clears the chain
            // when the mode changes, because Core refuses to reuse one built under another mode.
            "-prune=$pruneMiB"
            ).apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = tmp.absolutePath
                environment()["TMPDIR"] = tmp.absolutePath
            }.redirectErrorStream(true).start()
        } catch (failure: Throwable) {
            // A refused exec leaves PRoot's unpacked loader behind and nothing else collects it.
            RuntimeTemp.purge(context.cacheDir)
            throw failure
        }
        // Drain Core output so a full pipe can never stall the node.
        Thread { process?.inputStream?.bufferedReader()?.forEachLine { } }.start()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        val p = process
        process = null
        if (p != null) {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    override fun runWalletTool(
        workspace: File,
        walletName: String,
        command: String,
        dumpFile: File?,
        walletFormat: String?
    ): RuntimeBackend.ToolResult {
        check(walletTool.isFile) { "Official stohncoin-wallet tool is not installed in the runtime" }
        check(workspace.isDirectory) { "Wallet migration workspace is missing" }
        val linuxWorkspace = "/workspace"
        val args = mutableListOf(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-b", workspace.absolutePath + ":" + linuxWorkspace,
            "-b", runtime.absolutePath + ":/opt/stohn",
            "/opt/stohn/bin/stohncoin-wallet"
        )
        // The key-export guard lives in WalletToolArgs, so both backends refuse the same commands.
        args += WalletToolArgs.build(
            dataDir = linuxWorkspace,
            walletName = walletName,
            command = command,
            dumpFile = dumpFile?.let { "$linuxWorkspace/${it.name}" },
            walletFormat = walletFormat
        )
        // The wallet tool runs through the same PRoot launcher, so it shares the scoped temp dir.
        val tmp = RuntimeTemp.prepare(context.cacheDir)
        return ProcessRunner.run(
            command = args,
            directory = workspace,
            environment = mapOf(
                "PROOT_LOADER" to loader.absolutePath,
                "PROOT_TMP_DIR" to tmp.absolutePath,
                "TMPDIR" to tmp.absolutePath
            ),
            label = "stohncoin-wallet",
            onTimeout = { RuntimeTemp.purge(context.cacheDir) }
        )
    }

    private fun writeDnsConfig() {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val dnsServers = connectivity?.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }
            ?.map { it.hostAddress }
            ?.filter { !it.isNullOrBlank() }
            .orEmpty()
        require(dnsServers.isNotEmpty()) {
            "No system DNS servers are available"
        }
        dnsConfig.parentFile?.mkdirs()
        dnsConfig.writeText(dnsServers.joinToString("\n") { "nameserver $it" } + "\n")
    }
}
