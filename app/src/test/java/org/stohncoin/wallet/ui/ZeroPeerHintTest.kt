package org.stohncoin.wallet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroPeerHintTest {
    @Test
    fun hiddenJustBeforeTheThreshold() {
        assertFalse(ZeroPeerHint.shouldShow(true, 0, ZeroPeerHint.DELAY_MILLIS - 1))
    }

    @Test
    fun shownAtTheThreshold() {
        assertTrue(ZeroPeerHint.shouldShow(true, 0, ZeroPeerHint.DELAY_MILLIS))
    }

    @Test
    fun shownAfterTheThreshold() {
        assertTrue(ZeroPeerHint.shouldShow(true, 0, ZeroPeerHint.DELAY_MILLIS * 2))
    }

    @Test
    fun hiddenOncePeersConnect() {
        assertFalse(ZeroPeerHint.shouldShow(true, 1, ZeroPeerHint.DELAY_MILLIS * 2))
    }

    @Test
    fun hiddenWhenTheNodeIsNotRunning() {
        assertFalse(ZeroPeerHint.shouldShow(false, 0, ZeroPeerHint.DELAY_MILLIS * 2))
    }

    @Test
    fun reopenedScreenWaitsOnlyTheRemainingTime() {
        assertEquals(ZeroPeerHint.DELAY_MILLIS, ZeroPeerHint.remainingDelay(null))
        assertEquals(ZeroPeerHint.DELAY_MILLIS - 10_000, ZeroPeerHint.remainingDelay(10_000))
        assertEquals(0, ZeroPeerHint.remainingDelay(ZeroPeerHint.DELAY_MILLIS))
        assertEquals(0, ZeroPeerHint.remainingDelay(ZeroPeerHint.DELAY_MILLIS * 3))
    }
}
