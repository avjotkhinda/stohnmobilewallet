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
}
