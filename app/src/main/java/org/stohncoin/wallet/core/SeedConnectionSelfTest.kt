package org.stohncoin.wallet.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stohncoin.wallet.runtime.RuntimeBackend
import org.stohncoin.wallet.runtime.TestnetPeer

/**
 * Probes the seed/peer endpoints Stohn Core would try to reach, so a node stuck at 0 peers can
 * show whether the failure is DNS resolution, routing/TCP reachability, or simply that no seeds
 * exist for the selected network.
 */
object SeedConnectionSelfTest {
    const val MAINNET_PORT = 37218
    const val TESTNET_PORT = 47218
    const val CONNECT_TIMEOUT_MILLIS = 4000

    /**
     * Mainnet bundled fixed seed IPs from Stohn Core v26.x `chainparamsseeds.h`. Mainnet has no
     * DNS seed domains, so these IPs are the only built-in bootstrap peers Core can try.
     */
    val MAINNET_FIXED_SEEDS: List<String> = listOf(
        "5.196.91.67",
        "154.221.22.12",
        "37.59.20.42",
        "138.68.155.81",
        "159.89.53.185",
        "149.50.96.167",
        "168.119.129.110",
        "185.183.35.91",
        "66.70.182.1",
    )

    data class Target(val host: String, val port: Int, val label: String)

    fun defaultPort(network: RuntimeBackend.Network): Int =
        if (network == RuntimeBackend.Network.TESTNET) TESTNET_PORT else MAINNET_PORT

    fun targets(network: RuntimeBackend.Network, customPeer: String?): List<Target> {
        val port = defaultPort(network)
        val list = mutableListOf<Target>()
        if (network == RuntimeBackend.Network.MAINNET) {
            MAINNET_FIXED_SEEDS.forEach { list += Target(it, port, "bundled fixed seed") }
        }
        parsePeer(customPeer, port)?.let { (host, peerPort) ->
            list += Target(host, peerPort, "custom peer")
        }
        return list
    }

    /** Parses host or host:port, including bracketed IPv6, using the network default port. */
    fun parsePeer(raw: String?, defaultPort: Int): Pair<String, Int>? {
        val value = try {
            TestnetPeer.normalize(raw)
        } catch (_: IllegalArgumentException) {
            raw?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close <= 1) return null
            val host = value.substring(1, close)
            val rest = value.substring(close + 1)
            val port = when {
                rest.isEmpty() -> defaultPort
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
            return host to port
        }
        val parts = value.split(":")
        return when (parts.size) {
            1 -> parts[0] to defaultPort
            2 -> {
                val port = parts[1].toIntOrNull() ?: return null
                parts[0] to port
            }
            else -> null
        }
    }

    fun interface Resolver {
        fun resolve(host: String): Result<List<String>>
    }

    fun interface Connector {
        fun connect(host: String, port: Int, timeoutMillis: Int): Result<Unit>
    }

    private val defaultResolver = Resolver { host ->
        runCatching { InetAddress.getAllByName(host).map { it.hostAddress ?: it.toString() } }
    }

    private val defaultConnector = Connector { host, port, timeoutMillis ->
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
            }
        }
    }

    suspend fun run(
        network: RuntimeBackend.Network,
        customPeer: String?,
        resolver: Resolver = defaultResolver,
        connector: Connector = defaultConnector,
    ): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("Seed connection self-test")
            appendLine("Network: ${network.label}")
            appendLine("Default port: ${defaultPort(network)}")
            when (network) {
                RuntimeBackend.Network.MAINNET ->
                    appendLine("Mainnet has no DNS seed domains; testing bundled fixed seeds (Stohn Core v26.x).")
                RuntimeBackend.Network.TESTNET ->
                    appendLine("Testnet has no DNS or fixed seeds configured in Stohn Core; it stays at 0 peers unless a custom peer is reachable.")
            }
            val custom = try { TestnetPeer.normalize(customPeer) } catch (_: IllegalArgumentException) { customPeer?.trim() }
            if (custom.isNullOrEmpty()) {
                appendLine("Custom peer: none")
            } else {
                appendLine("Custom peer: $custom")
            }
            val probeTargets = targets(network, customPeer)
            if (probeTargets.isEmpty()) {
                appendLine("No seed or peer targets to probe for this network.")
                appendLine("Result: cannot test reachability without a custom peer. DNS/routing to bundled seeds is not applicable.")
                return@buildString
            }
            appendLine("Targets: ${probeTargets.size}")
            probeTargets.forEachIndexed { index, target ->
                appendLine()
                appendLine("[${index + 1}/${probeTargets.size}] ${target.label}: ${target.host}:${target.port}")
                val addresses = if (isIpLiteral(target.host)) {
                    appendLine("  DNS: not required (IP literal)")
                    Result.success(listOf(target.host))
                } else {
                    val resolved = resolver.resolve(target.host)
                    resolved.fold(
                        onSuccess = { addrs ->
                            val uniq = addrs.distinct()
                            appendLine("  DNS: resolved ${uniq.size} address(es): ${uniq.joinToString(", ")}")
                        },
                        onFailure = { err ->
                            appendLine("  DNS: FAILED (${describe(err)})")
                        },
                    )
                    resolved
                }
                val resolvedList = addresses.getOrNull().orEmpty().distinct()
                if (resolvedList.isEmpty()) {
                    appendLine("  TCP: skipped (no resolved addresses)")
                } else {
                    resolvedList.forEach { addr ->
                        val attempt = connector.connect(addr, target.port, CONNECT_TIMEOUT_MILLIS)
                        attempt.fold(
                            onSuccess = { appendLine("  TCP $addr:${target.port}: OK (connected)") },
                            onFailure = { err -> appendLine("  TCP $addr:${target.port}: FAILED (${describe(err)})") },
                        )
                    }
                }
            }
            appendLine()
            appendSummary(this, probeTargets, network)
        }.trimEnd()
    }

    private fun appendSummary(
        out: StringBuilder,
        probeTargets: List<Target>,
        network: RuntimeBackend.Network,
    ) {
        if (probeTargets.isEmpty()) return
        out.appendLine("Summary: review DNS and TCP results above.")
        if (network == RuntimeBackend.Network.MAINNET) {
            out.appendLine("If all bundled fixed seeds fail by TCP, the seeds may be stale or unreachable from this network; set a reachable custom mainnet peer.")
        } else {
            out.appendLine("If the custom peer fails, verify the host/port is a reachable Stohn testnet node from this network.")
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is UnknownHostException -> "DNS lookup failed: ${t.message ?: "unknown host"}"
        is SocketTimeoutException -> "connection timed out after ${CONNECT_TIMEOUT_MILLIS}ms"
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    private fun isIpLiteral(host: String): Boolean {
        // IPv4 literal or raw IPv6 (without brackets, as passed to InetSocketAddress/Target.host)
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) return true
        return host.contains(':')
    }
}
