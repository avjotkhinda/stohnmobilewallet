package org.stohncoin.wallet.runtime

/**
 * Optional peer for Stohn testnet, whose chain parameters ship with no seeds. Accepts a
 * hostname or IP with an optional port, for example `node.example.org` or `1.2.3.4:47218`.
 * Brackets are required for a literal IPv6 address.
 */
object TestnetPeer {
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
        }
        if (port != null) {
            require(port.isNotEmpty() && port.all(Char::isDigit)) { "Peer port is invalid" }
            val parsedPort = port.toIntOrNull()
            require(parsedPort != null && parsedPort in 1..65535) { "Peer port must be 1-65535" }
        }
        return if (port == null) host else "$host:$port"
    }
}
