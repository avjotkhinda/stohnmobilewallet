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

    private const val CUSTOM_PEER_LABEL = "custom peer"

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

    /** One TCP attempt against a single resolved address. */
    data class TcpAttempt(val address: String, val ok: Boolean, val error: String?)

    /** What probing one target (a bundled seed or the custom peer) produced. */
    data class TargetResult(
        val target: Target,
        val addresses: List<String>,
        val dnsError: String?,
        val tcpAttempts: List<TcpAttempt>,
    )

    /** Everything one self-test observed, kept separate from the text report. */
    data class ProbeResult(
        val network: RuntimeBackend.Network,
        val customPeer: String?,
        val targets: List<TargetResult>,
    )

    private enum class Outcome { REACHABLE, DNS_FAILED, TIMEOUT, REFUSED, UNREACHABLE }

    fun defaultPort(network: RuntimeBackend.Network): Int =
        if (network == RuntimeBackend.Network.TESTNET) TESTNET_PORT else MAINNET_PORT

    fun targets(network: RuntimeBackend.Network, customPeer: String?): List<Target> {
        val port = defaultPort(network)
        val list = mutableListOf<Target>()
        if (network == RuntimeBackend.Network.MAINNET) {
            MAINNET_FIXED_SEEDS.forEach { list += Target(it, port, "bundled fixed seed") }
        }
        parsePeer(customPeer, port)?.let { (host, peerPort) ->
            list += Target(host, peerPort, CUSTOM_PEER_LABEL)
        }
        return list
    }

    /**
     * Parses host or host:port, including bracketed IPv6, using the network default port.
     *
     * A peer that [TestnetPeer] rejects is still returned: the trimmed text becomes the host and
     * the default port applies when no numeric port is present, so the probe reports why a
     * malformed peer fails instead of the peer silently disappearing from the self-test.
     */
    fun parsePeer(raw: String?, defaultPort: Int): Pair<String, Int>? {
        val value = normalizedPeer(raw) ?: return null
        val close = value.indexOf(']')
        if (value.startsWith("[") && close > 1) {
            val host = value.substring(1, close)
            val rest = value.substring(close + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: defaultPort else defaultPort
            return host to port
        }
        val colon = value.lastIndexOf(':')
        val suffix = if (colon > 0 && value.indexOf(':') == colon) value.substring(colon + 1) else null
        val port = suffix?.toIntOrNull()?.takeIf { it in 1..65535 }
        return if (port != null) value.substring(0, colon) to port else value to defaultPort
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

    /** Runs every target once and keeps the individual DNS/TCP outcomes. */
    suspend fun probe(
        network: RuntimeBackend.Network,
        customPeer: String?,
        resolver: Resolver = defaultResolver,
        connector: Connector = defaultConnector,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val results = targets(network, customPeer).map { target -> probeTarget(target, resolver, connector) }
        ProbeResult(network, normalizedPeer(customPeer), results)
    }

    /** Probes one host:port, for the custom-peer dialog's reachability check. */
    suspend fun probePeer(
        host: String,
        port: Int,
        resolver: Resolver = defaultResolver,
        connector: Connector = defaultConnector,
    ): TargetResult = withContext(Dispatchers.IO) {
        probeTarget(Target(host, port, CUSTOM_PEER_LABEL), resolver, connector)
    }

    private fun probeTarget(target: Target, resolver: Resolver, connector: Connector): TargetResult {
        val addresses: List<String>
        val dnsError: String?
        if (isIpLiteral(target.host)) {
            addresses = listOf(target.host)
            dnsError = null
        } else {
            val resolved = resolver.resolve(target.host)
            addresses = resolved.getOrNull().orEmpty().distinct()
            dnsError = resolved.exceptionOrNull()?.let { describe(it) }
        }
        val attempts = addresses.map { address ->
            val attempt = connector.connect(address, target.port, CONNECT_TIMEOUT_MILLIS)
            TcpAttempt(address, attempt.isSuccess, attempt.exceptionOrNull()?.let { describe(it) })
        }
        return TargetResult(target, addresses, dnsError, attempts)
    }

    /** The full multi-line diagnostic report, appended to the node diagnostics log. */
    fun report(result: ProbeResult): String = buildString {
        appendLine("Seed connection self-test")
        appendLine("Network: ${result.network.label}")
        appendLine("Default port: ${defaultPort(result.network)}")
        when (result.network) {
            RuntimeBackend.Network.MAINNET ->
                appendLine("Mainnet has no DNS seed domains; testing bundled fixed seeds (Stohn Core v26.x).")
            RuntimeBackend.Network.TESTNET ->
                appendLine("Testnet has no DNS or fixed seeds configured in Stohn Core; it stays at 0 peers unless a custom peer is reachable.")
        }
        val custom = result.customPeer
        if (custom.isNullOrEmpty()) {
            appendLine("Custom peer: none")
        } else {
            appendLine("Custom peer: $custom")
        }
        if (result.targets.isEmpty()) {
            appendLine("No seed or peer targets to probe for this network.")
            appendLine("Result: cannot test reachability without a custom peer. DNS/routing to bundled seeds is not applicable.")
            return@buildString
        }
        appendLine("Targets: ${result.targets.size}")
        result.targets.forEachIndexed { index, target ->
            appendLine()
            appendLine("[${index + 1}/${result.targets.size}] ${target.target.label}: ${target.target.host}:${target.target.port}")
            when {
                isIpLiteral(target.target.host) -> appendLine("  DNS: not required (IP literal)")
                target.dnsError != null -> appendLine("  DNS: FAILED (${target.dnsError})")
                else -> appendLine("  DNS: resolved ${target.addresses.size} address(es): ${target.addresses.joinToString(", ")}")
            }
            if (target.addresses.isEmpty()) {
                appendLine("  TCP: skipped (no resolved addresses)")
            } else {
                target.tcpAttempts.forEach { attempt ->
                    if (attempt.ok) {
                        appendLine("  TCP ${attempt.address}:${target.target.port}: OK (connected)")
                    } else {
                        appendLine("  TCP ${attempt.address}:${target.target.port}: FAILED (${attempt.error})")
                    }
                }
            }
        }
        appendLine()
        appendSummary(this, result)
    }.trimEnd()

    /**
     * One or two plain-language sentences naming what failed, for the Node Status screen where a
     * full report is too long to read.
     */
    fun summary(result: ProbeResult): String {
        if (result.targets.isEmpty()) {
            return "${result.network.label} has no bundled seeds to test. Set a custom peer, then run the test again."
        }
        val custom = result.targets.firstOrNull { it.target.label == CUSTOM_PEER_LABEL }
        val seeds = result.targets.filter { it.target.label != CUSTOM_PEER_LABEL }
        if (custom != null) {
            val address = "${custom.target.host}:${custom.target.port}"
            val message = when (custom.outcome()) {
                Outcome.REACHABLE ->
                    "Custom peer $address is reachable. Stohn Core connects on its next retry; restart the node if it stays at 0 peers."
                Outcome.DNS_FAILED ->
                    "Could not resolve ${custom.target.host} (DNS lookup failed). Check the name or use an IP address."
                Outcome.TIMEOUT ->
                    "Custom peer $address timed out. It may be offline, or this network blocks the port."
                Outcome.REFUSED ->
                    "Custom peer $address refused the connection. Check the port (default ${defaultPort(result.network)})."
                Outcome.UNREACHABLE ->
                    "Custom peer $address is unreachable${custom.firstError()?.let { ": $it" }.orEmpty()}."
            }
            if (custom.outcome() == Outcome.REACHABLE || seeds.isEmpty()) return message
            return "$message Bundled seeds: ${seeds.count { it.outcome() == Outcome.REACHABLE }} of ${seeds.size} responded."
        }
        val reachable = seeds.count { it.outcome() == Outcome.REACHABLE }
        if (reachable > 0) {
            return "$reachable of ${seeds.size} bundled seeds responded. If Core still shows 0 peers, restart the node or set a custom peer."
        }
        val timedOut = seeds.any { it.outcome() == Outcome.TIMEOUT }
        return "None of the ${seeds.size} bundled seeds responded${if (timedOut) " (timeouts)" else ""}. They may be stale or blocked; set a custom peer."
    }

    /**
     * Short feedback for the custom-peer dialog's reachability check. A successful TCP connect
     * only proves the address answers; it says nothing about whether a Stohn node is there.
     */
    fun reachability(result: TargetResult): String {
        val address = "${result.target.host}:${result.target.port}"
        return when (result.outcome()) {
            Outcome.REACHABLE ->
                "Reached $address over TCP. This only shows the address answers; it does not confirm a compatible Stohn peer."
            Outcome.DNS_FAILED ->
                "Could not resolve ${result.target.host} (DNS lookup failed). Check the name or use an IP address."
            Outcome.TIMEOUT ->
                "No TCP answer from $address within ${CONNECT_TIMEOUT_MILLIS}ms. It may be offline, or this network blocks the port."
            Outcome.REFUSED ->
                "$address refused the connection. Check the port and that the node is running."
            Outcome.UNREACHABLE ->
                "Could not reach $address${result.firstError()?.let { ": $it" }.orEmpty()}."
        }
    }

    /** Convenience for callers that only need the text report. */
    suspend fun run(
        network: RuntimeBackend.Network,
        customPeer: String?,
        resolver: Resolver = defaultResolver,
        connector: Connector = defaultConnector,
    ): String = report(probe(network, customPeer, resolver, connector))

    private fun appendSummary(out: StringBuilder, result: ProbeResult) {
        if (result.targets.isEmpty()) return
        out.appendLine("Summary: review DNS and TCP results above.")
        if (result.network == RuntimeBackend.Network.MAINNET) {
            out.appendLine("If all bundled fixed seeds fail by TCP, the seeds may be stale or unreachable from this network; set a reachable custom mainnet peer.")
        } else {
            out.appendLine("If the custom peer fails, verify the host/port is a reachable Stohn testnet node from this network.")
        }
    }

    private fun TargetResult.outcome(): Outcome {
        if (dnsError != null) return Outcome.DNS_FAILED
        if (tcpAttempts.any { it.ok }) return Outcome.REACHABLE
        val errors = tcpAttempts.mapNotNull { it.error }
        return when {
            errors.any { it.contains("timed out", ignoreCase = true) } -> Outcome.TIMEOUT
            errors.any { it.contains("refused", ignoreCase = true) } -> Outcome.REFUSED
            else -> Outcome.UNREACHABLE
        }
    }

    private fun TargetResult.firstError(): String? = tcpAttempts.firstOrNull { !it.ok }?.error

    /**
     * The peer as stored when it validates, otherwise the trimmed text as typed. Falling back
     * rather than returning null keeps a malformed peer probe-able, so the report can explain it.
     */
    private fun normalizedPeer(raw: String?): String? =
        runCatching { TestnetPeer.normalize(raw) }.getOrElse {
            raw?.trim()?.takeIf { it.isNotEmpty() }
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
