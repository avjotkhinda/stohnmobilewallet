package org.stohncoin.wallet.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountsTest {
    @Test
    fun parsesDecimalCoinsExactly() {
        assertEquals(100_000_000L, Amounts.parse("1"))
        assertEquals(125_000_000L, Amounts.parse("1.25"))
        assertEquals(1L, Amounts.parse("0.00000001"))
        assertEquals(100_000_000L, Amounts.parse("1.00000000"))
        assertEquals(1_230L, Amounts.parse("0.0000123"))
    }

    @Test
    fun rejectsInvalidAmounts() {
        listOf("", " ", "abc", "-1", "0", "0.0", "1.123456789", "1e-8", "1,5", "+1", ".", "1.2.3")
            .forEach { assertNull("must reject '$it'", Amounts.parse(it)) }
    }

    @Test
    fun formattingKeepsSatoshiPrecision() {
        assertEquals("1.25", Amounts.format(125_000_000L))
        assertEquals("0", Amounts.format(0L))
        assertEquals("1.25000000", Amounts.formatFixed(125_000_000L))
        // The classic binary-float trap: 0.1 + 0.2 must be exactly 0.3 in satoshis.
        val sum = Amounts.parse("0.1")!! + Amounts.parse("0.2")!!
        assertEquals(30_000_000L, sum)
        assertEquals("0.3", Amounts.format(sum))
    }

    @Test
    fun jsonConversionRoundTrips() {
        val satoshis = 123_456_789L
        assertEquals(satoshis, Amounts.fromJsonCoin(Amounts.toJsonCoin(satoshis).toDouble()))
        assertEquals("1.23456789", Amounts.toCoinString(satoshis))
    }

    @Test
    fun uriCarriesAmountAndLabel() {
        val uri = Amounts.buildUri("Sabc123", 250_000_000L, "Rent money")
        assertTrue(uri.startsWith("stohn:Sabc123?amount=2.5"))
        val parsed = Amounts.parseUri(uri)!!
        assertEquals("Sabc123", parsed.address)
        assertEquals(250_000_000L, parsed.satoshis)
        assertEquals("Rent money", parsed.label)
    }

    @Test
    fun bareAddressAndForeignSchemesStillParse() {
        assertEquals("Sabc123", Amounts.parseUri("Sabc123")?.address)
        assertEquals("Sabc123", Amounts.parseUri("bitcoin:Sabc123")?.address)
        assertEquals(50_000_000L, Amounts.parseUri("stohn:Sabc123?amount=0.5&label=Tip")?.satoshis)
        assertNull(Amounts.parseUri(""))
        assertNull(Amounts.parseUri("stohn:?amount=1"))
    }

    @Test
    fun `the supported ceiling is exact and one coin above it is refused`() {
        // MAX_SATOSHIS is the largest amount that still fits the JSON boundary; the wallet must not
        // accept an amount it can only send as a corrupted number.
        assertEquals(Amounts.MAX_SATOSHIS, Amounts.parse("900000000"))
        assertNull(Amounts.parse("900000001"))
        // Past what a Long can hold at all, even before the ceiling applies.
        assertNull(Amounts.parse("92233720368547.75808"))
    }

    @Test
    fun `an amount Core reports as not-a-number is refused`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val failure = runCatching { Amounts.fromJsonCoin(value) }.exceptionOrNull()
            assertTrue(
                "$value was converted into an amount",
                failure is IllegalArgumentException && failure.message.orEmpty().contains("non-finite")
            )
        }
    }

    @Test
    fun `an amount outside the supported range never reaches Core`() {
        for (value in listOf(0L, -1L, Amounts.MAX_SATOSHIS + 1, Long.MIN_VALUE)) {
            val failure = runCatching { Amounts.toJsonCoin(value) }.exceptionOrNull()
            assertTrue(
                "$value was accepted as a sendable amount",
                failure is IllegalArgumentException && failure.message.orEmpty().contains("outside the supported range")
            )
        }
        assertEquals(Amounts.MAX_SATOSHIS, Amounts.toJsonCoin(Amounts.MAX_SATOSHIS).movePointRight(8).longValueExact())
    }

    @Test
    fun `a uri with nothing to say is only the address`() {
        assertEquals("stohn:Sabc123", Amounts.buildUri("Sabc123"))
        assertEquals("stohn:Sabc123", Amounts.buildUri("Sabc123", satoshis = null, label = "   "))
        assertEquals("stohn:Sabc123?label=Rent+money", Amounts.buildUri("Sabc123", null, "Rent money"))
    }

    @Test
    fun `a uri refuses a blank address`() {
        for (address in listOf("", "   ")) {
            val failure = runCatching { Amounts.buildUri(address) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException && failure.message.orEmpty().contains("Address is required"))
        }
    }

    @Test
    fun `a payment request with a broken amount or label keeps the address`() {
        // A scanner hands over whatever was in the QR code: a bad field must cost that field, not
        // the address the user is trying to pay.
        val unreadableAmount = Amounts.parseUri("stohn:Sabc123?amount=not-a-number")
        assertEquals("Sabc123", unreadableAmount?.address)
        assertNull(unreadableAmount?.satoshis)

        val brokenEscape = Amounts.parseUri("stohn:Sabc123?label=%zz")
        assertEquals("Sabc123", brokenEscape?.address)
        assertNull(brokenEscape?.label)
    }

    @Test
    fun `a colon in the address is not mistaken for a scheme`() {
        // Only a run of letters before the colon counts as a scheme, so a value that merely contains
        // one keeps its text.
        assertEquals("Sabc123", Amounts.parseUri("Sabc123?amount=1")?.address)
        assertEquals("1:a:b", Amounts.parseUri("1:a:b")?.address)
        assertEquals("Sabc123", Amounts.parseUri(" stohn:Sabc123 ")?.address)
    }
}
