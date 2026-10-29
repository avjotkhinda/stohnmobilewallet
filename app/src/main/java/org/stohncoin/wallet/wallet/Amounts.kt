package org.stohncoin.wallet.wallet

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Currency boundary for the wallet.
 *
 * Amounts are `Long` satoshis everywhere outside user input and display. A `Double` cannot
 * represent 0.1 exactly, so arithmetic on coin amounts silently loses satoshis; parsing and
 * formatting happen only at this edge, and only through [BigDecimal].
 */
object Amounts {
    const val SATOSHIS_PER_COIN = 100_000_000L
    const val DECIMALS = 8

    /** Upper bound that keeps every amount inside a Long once multiplied by the coin unit. */
    const val MAX_SATOSHIS = 90_000_000_000_000_000L

    const val URI_SCHEME = "stohn"

    data class PaymentRequest(val address: String, val satoshis: Long?, val label: String?)

    /** Parses user input such as `1.25` into satoshis. Returns null for invalid or non-positive input. */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isLetter() }) return null
        // A currency field never needs a sign: the wallet only ever moves positive amounts.
        if (trimmed.first() == '+' || trimmed.first() == '-') return null
        val decimal = runCatching { BigDecimal(trimmed) }.getOrNull() ?: return null
        if (decimal.signum() <= 0) return null
        val satoshis = runCatching { decimal.movePointRight(DECIMALS).longValueExact() }.getOrNull() ?: return null
        return satoshis.takeIf { it in 1..MAX_SATOSHIS }
    }

    /** Short human form: trailing zeros removed, never an exponent. */
    fun format(satoshis: Long): String =
        BigDecimal.valueOf(satoshis, DECIMALS).stripTrailingZeros().toPlainString()

    /** Fixed eight-decimal form for places where columns must line up. */
    fun formatFixed(satoshis: Long): String =
        BigDecimal.valueOf(satoshis, DECIMALS).setScale(DECIMALS).toPlainString()

    /** Converts a Core JSON amount (already a decimal number of coins) into satoshis. */
    fun fromJsonCoin(value: Double): Long {
        require(value.isFinite()) { "Core returned a non-finite amount" }
        return BigDecimal.valueOf(value)
            .movePointRight(DECIMALS)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    /** Coin amount for a JSON-RPC request, kept exact by sending a decimal rather than a Double. */
    fun toJsonCoin(satoshis: Long): BigDecimal {
        require(satoshis in 1..MAX_SATOSHIS) { "Amount is outside the supported range" }
        return BigDecimal.valueOf(satoshis, DECIMALS).stripTrailingZeros()
    }

    /** Coin amount as text, used inside `stohn:` payment requests. */
    fun toCoinString(satoshis: Long): String = toJsonCoin(satoshis).toPlainString()

    fun buildUri(address: String, satoshis: Long? = null, label: String? = null): String {
        require(address.isNotBlank()) { "Address is required" }
        val query = buildList {
            satoshis?.let { add("amount=${toCoinString(it)}") }
            label?.takeIf { it.isNotBlank() }?.let {
                add("label=${URLEncoder.encode(it, "UTF-8")}")
            }
        }
        return "$URI_SCHEME:$address" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    }

    /** Parses a scanned or pasted `stohn:` URI, or a bare address. */
    fun parseUri(raw: String): PaymentRequest? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // Accept `stohn:`, another wallet scheme, or a bare address.
        val schemeEnd = text.indexOf(':')
        val body = if (schemeEnd in 1 until text.length && text.substring(0, schemeEnd).all { it.isLetter() }) {
            text.substring(schemeEnd + 1)
        } else {
            text
        }
        val address = body.substringBefore("?").substringBefore("&").trim()
        if (address.isEmpty()) return null
        val query = body.substringAfter("?", "")
        val params = query.split("&")
            .filter { it.contains("=") }
            .associate { it.substringBefore("=") to it.substringAfter("=") }
        val satoshis = params["amount"]?.let(::parse)
        val label = params["label"]?.let { encoded ->
            runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
        }
        return PaymentRequest(address = address, satoshis = satoshis, label = label)
    }
}
