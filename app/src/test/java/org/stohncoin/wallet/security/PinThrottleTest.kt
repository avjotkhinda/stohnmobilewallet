package org.stohncoin.wallet.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinThrottleTest {
    private val now = 1_000_000L

    @Test
    fun attemptsBelowTheLimitAreNotBlocked() {
        var state = PinThrottle.State()
        repeat(PinThrottle.FREE_ATTEMPTS - 1) {
            state = PinThrottle.afterFailure(state, now)
            assertFalse("attempt ${state.failures} should still be allowed", PinThrottle.isBlocked(state, now))
            assertEquals(0L, PinThrottle.remainingMillis(state, now))
        }
    }

    @Test
    fun reachingTheLimitBlocksForTheBaseBackoff() {
        var state = PinThrottle.State()
        repeat(PinThrottle.FREE_ATTEMPTS) { state = PinThrottle.afterFailure(state, now) }
        assertTrue(PinThrottle.isBlocked(state, now))
        assertEquals(PinThrottle.BASE_BACKOFF_MILLIS, PinThrottle.remainingMillis(state, now))
        assertFalse("the block must expire", PinThrottle.isBlocked(state, now + PinThrottle.BASE_BACKOFF_MILLIS))
    }

    @Test
    fun backoffDoublesAndIsCapped() {
        assertEquals(PinThrottle.BASE_BACKOFF_MILLIS, PinThrottle.backoffMillis(PinThrottle.FREE_ATTEMPTS))
        assertEquals(PinThrottle.BASE_BACKOFF_MILLIS * 2, PinThrottle.backoffMillis(PinThrottle.FREE_ATTEMPTS + 1))
        assertEquals(PinThrottle.BASE_BACKOFF_MILLIS * 4, PinThrottle.backoffMillis(PinThrottle.FREE_ATTEMPTS + 2))
        repeat(200) { extra ->
            val delay = PinThrottle.backoffMillis(PinThrottle.FREE_ATTEMPTS + extra)
            assertTrue("backoff must never exceed the cap", delay <= PinThrottle.MAX_BACKOFF_MILLIS)
            assertTrue("backoff must never be negative", delay >= 0L)
        }
        assertEquals("delays below the free limit carry no penalty", 0L, PinThrottle.backoffMillis(0))
    }

    @Test
    fun successClearsTheCounter() {
        var state = PinThrottle.State()
        repeat(PinThrottle.FREE_ATTEMPTS) { state = PinThrottle.afterFailure(state, now) }
        assertTrue(PinThrottle.isBlocked(state, now))
        val cleared = PinThrottle.afterSuccess()
        assertEquals(0, cleared.failures)
        assertFalse(PinThrottle.isBlocked(cleared, now))
    }

    @Test
    fun repeatedFailuresExtendTheBlock() {
        var state = PinThrottle.State()
        repeat(PinThrottle.FREE_ATTEMPTS) { state = PinThrottle.afterFailure(state, now) }
        val extended = PinThrottle.afterFailure(state, now)
        assertTrue(PinThrottle.remainingMillis(extended, now) > PinThrottle.remainingMillis(state, now))
    }
}
