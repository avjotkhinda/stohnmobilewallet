package org.stohncoin.wallet.runtime

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The NDK-native backend: a Stohn Core built for Android (bionic), executed directly.
 *
 * There is no PRoot, no Debian rootfs and no glibc loader on this path — Core runs as an ordinary
 * Android process, which is what removes PRoot's pointer-translation overhead and the store-policy
 * question about shipping a glibc userspace inside the APK.
 *
 * The binary ships as a native library: Android only extracts files named `lib*.so` from the APK,
 * and `nativeLibraryDir` is the one directory the platform still lets an app `execve` from (app
 * data has been off limits since API 29). That requires `useLegacyPackaging = true`, which this
 * app already sets so the PRoot launcher can be executed the same way.
 *
 * It is refused unless its SHA-256 matches the pin in [RuntimeManifest], and the pin is checked
 * again immediately before the process starts. With no pin published the factory keeps running
 * the glibc backend — a native binary that does not match its pin is a hard failure, never a
 * quiet swap to some other runtime.
 */
class NativeRuntime(private val context: Context, private val pruneMiB: Int, private val pins: RuntimePins) : RuntimeBackend {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val core = File(nativeDir, NativeLaunch.CORE_LIB)
    private val walletTool = File(nativeDir, NativeLaunch.WALLET_TOOL_LIB)
    override val dataDir: File = RuntimeBackend.dataDirectory(context)
    private val conf = File(dataDir, "stohn.conf")
    private var process: Process? = null

    @Volatile
    override var rpcPort: Int = 0
        private set

    /** No chroot to cross: Core reads and writes the real paths this app owns. */
    override fun corePath(file: File): String = file.absolutePath

    override suspend fun start() = withContext(Dispatchers.IO) {
        // Verified again here, not only in the factory: nothing runs between the decision and the
        // exec without being checked against the pin.
        NativeLaunch.requireReady(core, walletTool, pins.nativeCoreSha256, pins.nativeWalletToolSha256)
        dataDir.mkdirs()
        if (!conf.exists()) conf.writeText(RuntimeBackend.coreConfig())
        // RPC listens on a fresh random loopback port for every start so another app on the
        // device cannot pre-bind a fixed, publicly known port.
        rpcPort = RuntimeBackend.reserveRpcPort()
        val command = listOf(core.absolutePath) + NativeLaunch.coreArguments(
            conf = conf.absolutePath,
            dataDir = dataDir.absolutePath,
            rpcPort = rpcPort,
            pruneMiB = pruneMiB
        )
        process = ProcessBuilder(command).apply {
            // The daemon links the shared libraries shipped beside it in nativeLibraryDir.
            environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
        }.redirectErrorStream(true).start()
        // Drain Core output so a full pipe can never stall the node.
        Thread { process?.inputStream?.bufferedReader()?.forEachLine { } }.start()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        val p = process
        process = null
        if (p != null) {
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
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
        check(walletTool.isFile) { "Native stohncoin-wallet tool is not installed in this build" }
        check(workspace.isDirectory) { "Wallet migration workspace is missing" }
        NativeLaunch.requireReady(core, walletTool, pins.nativeCoreSha256, pins.nativeWalletToolSha256)
        // WalletToolArgs applies the key-export guard, so this launcher is as locked down as PRoot's.
        val args = listOf(walletTool.absolutePath) + WalletToolArgs.build(
            dataDir = workspace.absolutePath,
            walletName = walletName,
            command = command,
            dumpFile = dumpFile?.absolutePath,
            walletFormat = walletFormat
        )
        return ProcessRunner.run(command = args, directory = workspace, label = "stohncoin-wallet")
    }
}

/**
 * The rules that decide whether a native Stohn Core may be executed, plus the exact argument list
 * it is executed with. Deliberately free of Android types so every decision is unit tested on the JVM.
 */
object NativeLaunch {
    /** Android only extracts files named `lib*.so` from the APK, and only those are executable. */
    const val CORE_LIB = "libstohncoind.so"
    const val WALLET_TOOL_LIB = "libstohncoin-wallet.so"

    private val SHA256 = Regex("[0-9a-f]{64}")

    sealed interface Decision {
        /** Why this backend may not run; empty when the decision does not forbid execution. */
        val refusal: String
    }

    /** Both binaries are present and match their pins: run this backend. */
    data class Ready(val core: File, val walletTool: File) : Decision {
        override val refusal: String = ""
    }

    /** This build publishes no native pin, so the glibc runtime is the one this APK carries. */
    object NoNativeBuild : Decision {
        override val refusal: String = "no native Stohn Core is pinned in this build"
    }

    /** A pin is malformed, or the pair was published half-finished: refuse, do not degrade. */
    data class InvalidPin(val problem: String) : Decision {
        override val refusal: String = problem
    }

    /** The APK pins a native runtime it does not carry. */
    object MissingBinary : Decision {
        override val refusal: String = "this build pins a native Stohn Core but does not ship it"
    }

    /** A binary is present but does not hash to its pin. */
    data class Tampered(val artifact: String, val expected: String, val found: String) : Decision {
        override val refusal: String = "$artifact failed its integrity check: pinned $expected, found $found"
    }

    /** True when [pin] is a complete lowercase SHA-256 digest. */
    fun isPinned(pin: String): Boolean = pin.matches(SHA256)

    /**
     * Decides which backend this APK may run. Blank pins mean no native build is published; once a
     * pin exists, both binaries must exist and match it or the decision is a refusal.
     */
    fun decide(core: File, walletTool: File, corePin: String, walletToolPin: String): Decision {
        if (corePin.isBlank() && walletToolPin.isBlank()) return NoNativeBuild
        if (corePin.isBlank() || walletToolPin.isBlank()) {
            return InvalidPin("native pins are published together, not one at a time")
        }
        if (!isPinned(corePin)) return InvalidPin("native Stohn Core pin is not a SHA-256 digest")
        if (!isPinned(walletToolPin)) return InvalidPin("native wallet tool pin is not a SHA-256 digest")
        if (!core.isFile || !walletTool.isFile) return MissingBinary
        val coreHash = artifactSha256(core)
        if (coreHash != corePin) return Tampered(CORE_LIB, corePin, coreHash)
        val toolHash = artifactSha256(walletTool)
        if (toolHash != walletToolPin) return Tampered(WALLET_TOOL_LIB, walletToolPin, toolHash)
        return Ready(core, walletTool)
    }

    /** [decide], but any refusal is thrown instead of returned — used directly before an exec. */
    fun requireReady(core: File, walletTool: File, corePin: String, walletToolPin: String): Ready {
        val decision = decide(core, walletTool, corePin, walletToolPin)
        return decision as? Ready ?: error(decision.refusal)
    }

    /**
     * Core's command line as a native process sees it: no `-r`/`-b` path bindings, no chroot, and
     * the same `-prune` rule as the glibc backend — the depth is fixed for the life of a chain
     * database, because Core refuses to reuse one built under another mode.
     */
    fun coreArguments(conf: String, dataDir: String, rpcPort: Int, pruneMiB: Int): List<String> = listOf(
        "-conf=$conf",
        "-datadir=$dataDir",
        "-rpcport=$rpcPort",
        "-daemon=0",
        "-prune=$pruneMiB"
    )
}