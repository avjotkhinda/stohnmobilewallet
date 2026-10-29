package org.stohncoin.wallet.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    private val iterations = PinHasher.MIN_ITERATIONS

    @Test
    fun saltedRecordRoundTrips() {
        val record = PinHasher.hash("1234", iterations = iterations)
        assertTrue("record must carry the KDF parameters", record.startsWith("pbkdf2-sha256\$"))
        assertTrue(PinHasher.verify("1234", record))
        assertFalse(PinHasher.verify("4321", record))
    }

    @Test
    fun identicalPinsProduceDifferentRecords() {
        val first = PinHasher.hash("1234", iterations = iterations)
        val second = PinHasher.hash("1234", iterations = iterations)
        assertNotEquals("a shared salt would let one rainbow table cover every device", first, second)
        assertTrue(PinHasher.verify("1234", first))
        assertTrue(PinHasher.verify("1234", second))
    }

    @Test
    fun legacyUnsaltedDigestStillVerifiesAndIsFlaggedForUpgrade() {
        val legacy = PinHasher.legacySha256Hex("2468")
        assertTrue(PinHasher.isLegacy(legacy))
        assertTrue(PinHasher.verify("2468", legacy))
        assertFalse(PinHasher.verify("8642", legacy))
        assertFalse("a PBKDF2 record must not be treated as legacy", PinHasher.isLegacy(PinHasher.hash("2468", iterations = iterations)))
    }

    @Test
    fun malformedRecordsAreRejected() {
        val records = listOf(
            null,
            "",
            "   ",
            "garbage",
            "pbkdf2-sha256\$" + iterations + "\$not-base64\$not-base64",
            // iteration count below the floor must not be honoured
            "pbkdf2-sha256\$1000\$AAAAAAAAAAAAAAAAAAAAAA\$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            // wrong salt length
            "pbkdf2-sha256\$" + iterations + "\$AAAA\$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        )
        records.forEach { record ->
            assertFalse("must reject: $record", PinHasher.verify("1234", record))
        }
    }

    @Test
    fun emptyPinIsNeverAccepted() {
        assertFalse(PinHasher.verify("", PinHasher.hash("1234", iterations = iterations)))
    }
}
