package org.stohncoin.wallet.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the glibc Stohn Core through an embedded Linux userspace. Nothing is installed as a
 * separate Termux/Debian app. The runtime lives entirely inside this application's private files.
 *
 * Production path: replace this backend with the Android-native NDK backend once stohncoind is
 * successfully ported to bionic. The interface stays identical.
 */
class EmbeddedLinuxRuntime(private val context: Context) : RuntimeBackend {
    private val root = File(context.filesDir, "fullmode")
    private val runtime = File(root, "runtime")
    private val rootfs = File(runtime, "rootfs")
    private val coreBin = File(runtime, "bin/stohncoind")
    private val walletTool = File(runtime, "bin/stohncoin-wallet")
    private val dataDir = File(root, "stohn-data")
    private val conf = File(dataDir, "stohn.conf")
    private var process: Process? = null
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libproot-loader.so")
    private val libTalloc = File(nativeDir, "libtalloc.so")
    private val libShmem = File(nativeDir, "libandroid-shmem.so")
    private val dnsConfig = File(dataDir, "resolv.conf")

    override suspend fun start() = withContext(Dispatchers.IO) {
        root.mkdirs(); runtime.mkdirs(); dataDir.mkdirs()
        check(rootfs.isDirectory) { "Embedded Debian rootfs is missing" }
        check(coreBin.isFile) { "Stohn Core binary is missing" }
        coreBin.setExecutable(true)
        if (!conf.exists()) conf.writeText(defaultConfig())
        writeDnsConfig()
        check(proot.isFile) { "Embedded Android PRoot is missing from nativeLibraryDir" }
        check(loader.isFile) { "Embedded PRoot loader is missing from nativeLibraryDir" }
        check(libTalloc.isFile) { "Embedded libtalloc is missing from nativeLibraryDir" }
        check(libShmem.isFile) { "Embedded libandroid-shmem is missing from nativeLibraryDir" }
        check(RuntimeManifest.PROOT_SHA256.matches(Regex("[0-9a-f]{64}"))) { "PRoot SHA-256 is not pinned" }
        check(RuntimeManifest.PROOT_LOADER_SHA256.matches(Regex("[0-9a-f]{64}"))) { "PRoot loader SHA-256 is not pinned" }
        check(RuntimeManifest.LIBTALLOC_SHA256.matches(Regex("[0-9a-f]{64}"))) { "libtalloc SHA-256 is not pinned" }
        check(RuntimeManifest.LIBANDROID_SHMEM_SHA256.matches(Regex("[0-9a-f]{64}"))) { "libandroid-shmem SHA-256 is not pinned" }
        check(sha256(proot) == RuntimeManifest.PROOT_SHA256) { "Bundled PRoot integrity check failed" }
        check(sha256(loader) == RuntimeManifest.PROOT_LOADER_SHA256) { "Bundled PRoot loader integrity check failed" }
        check(sha256(libTalloc) == RuntimeManifest.LIBTALLOC_SHA256) { "Bundled libtalloc integrity check failed" }
        check(sha256(libShmem) == RuntimeManifest.LIBANDROID_SHMEM_SHA256) { "Bundled libandroid-shmem integrity check failed" }
        process = ProcessBuilder(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-b", dataDir.absolutePath + ":/home/stohn/.stohn",
            "-b", runtime.absolutePath + ":/opt/stohn",
            "-b", dnsConfig.absolutePath + ":/etc/resolv.conf",
            "/opt/stohn/bin/stohncoind",
            "-conf=/home/stohn/.stohn/stohn.conf",
            "-datadir=/home/stohn/.stohn",
            "-daemon=0"
        ).apply {
            environment()["PROOT_LOADER"] = loader.absolutePath
            environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }.redirectErrorStream(true).start()
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


    fun createWalletToolWorkspace(prefix: String): File {
        val dir = File(dataDir, "migration-$prefix-${UUID.randomUUID()}")
        check(dir.mkdirs()) { "Unable to create wallet migration workspace" }
        return dir
    }

    data class ToolResult(val exitCode: Int, val stdout: String, val stderr: String)

    fun runWalletTool(workspace: File, walletName: String, command: String, dumpFile: File? = null): ToolResult {
        check(walletTool.isFile) { "Official stohncoin-wallet tool is not installed in the runtime" }
        check(workspace.isDirectory) { "Wallet migration workspace is missing" }
        val linuxWorkspace = "/workspace"
        val linuxWalletTool = "/opt/stohn/bin/stohncoin-wallet"
        val args = mutableListOf(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-b", workspace.absolutePath + ":" + linuxWorkspace,
            "-b", runtime.absolutePath + ":/opt/stohn",
            linuxWalletTool,
            "-datadir=$linuxWorkspace",
            "-wallet=$walletName"
        )
        if (dumpFile != null) {
            val name = dumpFile.name
            args += "-dumpfile=$linuxWorkspace/$name"
        }
        args += command
        val process = ProcessBuilder(args)
            .directory(workspace)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .redirectErrorStream(true)
            .start()
        val combined = process.inputStream.bufferedReader().use { reader ->
            val output = StringBuilder()
            val drain = Thread {
                reader.forEachLine { line -> output.append(line).append('\n') }
            }
            drain.start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                drain.join(2_000)
                error("stohncoin-wallet timed out")
            }
            drain.join(2_000)
            output.toString()
        }
        return ToolResult(process.exitValue(), combined, combined)
    }

    suspend fun rpcCall(method: String, params: org.json.JSONArray = org.json.JSONArray(), walletName: String? = null): org.json.JSONObject =
        withContext(Dispatchers.IO) { LocalRpc(dataDir, walletName).objectResult(method, params) }

    suspend fun rpcString(method: String, params: org.json.JSONArray = org.json.JSONArray(), walletName: String? = null): String =
        withContext(Dispatchers.IO) { LocalRpc(dataDir, walletName).stringResult(method, params) }

    suspend fun rpcArray(method: String, params: org.json.JSONArray = org.json.JSONArray(), walletName: String? = null): org.json.JSONArray =
        withContext(Dispatchers.IO) { LocalRpc(dataDir, walletName).arrayResult(method, params) }

    suspend fun rpcVoid(method: String, params: org.json.JSONArray = org.json.JSONArray(), walletName: String? = null) =
        withContext(Dispatchers.IO) { LocalRpc(dataDir, walletName).voidResult(method, params) }

    suspend fun backupWalletSnapshot(snapshotName: String): File = withContext(Dispatchers.IO) {
        require(snapshotName.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid backup snapshot name" }
        val snapshot = File(dataDir, "backup-$snapshotName.dat")
        if (snapshot.exists()) snapshot.delete()
        val rpc = LocalRpc(dataDir)
        val linuxPath = "/home/stohn/.stohn/${snapshot.name}"
        rpc.objectResult("backupwallet", org.json.JSONArray().put(linuxPath))
        check(snapshot.isFile && snapshot.length() > 0L) { "Core did not create a backup snapshot" }
        snapshot
    }

    override suspend fun status(): RuntimeBackend.NodeInfo = withContext(Dispatchers.IO) {
        val rpc = LocalRpc(dataDir)
        val chain = rpc.objectResult("getblockchaininfo")
        val network = rpc.objectResult("getnetworkinfo")
        RuntimeBackend.NodeInfo(
            blocks = chain.optLong("blocks", 0),
            headers = chain.optLong("headers", 0),
            peers = network.optInt("connections", 0),
            ibd = chain.optBoolean("initialblockdownload", true),
            version = network.optString("subversion", "unknown")
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

val servers = dnsServers
        dnsConfig.parentFile?.mkdirs()
        dnsConfig.writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun defaultConfig() = """
        server=1
        daemon=0
        listen=1
        txindex=1
        rpcbind=127.0.0.1
        rpcallowip=127.0.0.1
        rpcport=32717
    """.trimIndent()
}

private class LocalRpc(private val dataDir: File, private val walletName: String? = null) {
    private fun request(method: String, params: org.json.JSONArray): org.json.JSONObject {
        val cookie = File(dataDir, ".cookie")
        check(cookie.isFile && cookie.length() > 0L) { "Core RPC cookie not available yet" }
        val endpoint = walletName?.let { "/wallet/" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "/"
        val connection = (URL("http://localhost:32717$endpoint").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2500
            connection.readTimeout = 5000
            val auth = android.util.Base64.encodeToString(cookie.readBytes(), android.util.Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $auth")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = org.json.JSONObject().apply {
                put("jsonrpc", "1.0")
                put("id", "stohn-wallet")
                put("method", method)
                put("params", params)
            }.toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = org.json.JSONObject(response)
            if (json.has("error") && !json.isNull("error")) {
                error(json.optJSONObject("error")?.optString("message") ?: "RPC error")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun objectResult(method: String, params: org.json.JSONArray = org.json.JSONArray()): org.json.JSONObject =
        request(method, params).optJSONObject("result") ?: error("RPC result for $method is not an object")

    fun arrayResult(method: String, params: org.json.JSONArray = org.json.JSONArray()): org.json.JSONArray =
        request(method, params).optJSONArray("result") ?: error("RPC result for $method is not an array")

    fun stringResult(method: String, params: org.json.JSONArray = org.json.JSONArray()): String =
        request(method, params).optString("result")

    fun voidResult(method: String, params: org.json.JSONArray = org.json.JSONArray()) {
        val response = request(method, params)
        require(response.has("result")) { "RPC response for $method has no result field" }
        require(response.isNull("result")) { "RPC result for $method was expected to be null" }
    }
}
