package org.stohncoin.wallet.runtime

/**
 * Optional peer for Stohn testnet, whose chain parameters ship with no seeds. Accepts a
 * hostname or IP with an optional port, for example `node.example.org` or `1.2.3.4:47218`.
 * Brackets are required for a literal IPv6 address.
 */
object TestnetPeer {
    /** A peer that passed validation, plus the port Core will actually dial. */
    data class Address(val host: String, val port: Int, val explicitPort: Boolean) {
        /** `host:port` with the port spelled out; IPv6 hosts keep the brackets the field requires. */
        val formatted: String get() = "$host:$port"

        /** What is stored: the port is left off when the user relied on the network default. */
        val normalized: String get() = if (explicitPort) "$host:$port" else host

        /** The host without IPv6 brackets, as a socket connection and `InetAddress` expect it. */
        val bareHost: String get() = host.removeSurrounding("[", "]")
    }

    fun normalize(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        require(value.length <= 255) { "Peer is too long" }
        require(value.none { it.isWhitespace() || it in "/\\@=" }) { "Peer contains invalid characters" }
        val host: String
        val port: String?
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            require(close > 1) { "IPv6 peer is missing a closing bracket" }
            host = value.substring(0, close + 1)
            val rest = value.substring(close + 1)
            port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> throw IllegalArgumentException("Peer has trailing characters")
            }
            require(Regex("^\\[[0-9A-Fa-f:.]+]$").matches(host)) { "Peer is not a valid IPv6 address" }
        } else {
            val parts = value.split(":")
            require(parts.size <= 2) { "IPv6 peers must be wrapped in brackets" }
            host = parts[0]
            port = parts.getOrNull(1)
            require(Regex("^[A-Za-z0-9.-]+$").matches(host)) { "Peer host is invalid" }
            require(!host.startsWith(".") && !host.endsWith(".")) { "Peer host is invalid" }
            validateHost(host)
        }
        if (port != null) {
            require(port.isNotEmpty() && port.all(Char::isDigit)) { "Peer port is invalid" }
            val parsedPort = port.toIntOrNull()
            require(parsedPort != null && parsedPort in 1..65535) { "Peer port must be 1-65535" }
        }
        return if (port == null) host else "$host:$port"
    }

    /**
     * The stored form of [raw] when it validates, or null when it is blank or no longer valid.
     * Startup paths use this so a value saved under older, looser rules cannot crash the node.
     */
    fun normalizedOrNull(raw: String?): String? = runCatching { normalize(raw) }.getOrNull()

    /**
     * Parses and validates a peer for the given network, filling in the port Core would use when
     * the user did not type one. Returns null for a blank value, which means "clear the peer".
     * Throws [IllegalArgumentException] with a user-facing message for anything invalid.
     */
    fun address(raw: String?, defaultPort: Int): Address? {
        require(defaultPort in 1..65535) { "Default port must be 1-65535" }
        val value = normalize(raw) ?: return null
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            val host = value.substring(0, close + 1)
            val rest = value.substring(close + 1)
            val explicitPort = rest.startsWith(":")
            return Address(host, if (explicitPort) rest.substring(1).toInt() else defaultPort, explicitPort)
        }
        val parts = value.split(":")
        return Address(parts[0], parts.getOrNull(1)?.toInt() ?: defaultPort, parts.size == 2)
    }

    /** Rejects hosts that are syntactically plausible in the field but not a usable address. */
    private fun validateHost(host: String) {
        require(host.length <= 253) { "Peer host is too long" }
        val labels = host.split(".")
        require(labels.all { it.length in 1..63 }) { "Peer host is invalid" }
        require(labels.none { it.startsWith("-") || it.endsWith("-") }) { "Peer host labels cannot start or end with a dash" }
        if (labels.all { label -> label.all(Char::isDigit) }) {
            require(labels.size == 4 && labels.all { it.length <= 3 && it.toInt() in 0..255 }) {
                "Peer is not a valid IPv4 address"
            }
        }
    }
}
