package org.stohncoin.wallet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProgressTest {
    @Test
    fun `a node without headers has no percentage to show`() {
        assertNull(SyncProgress.percent(0, 0))
        assertNull(SyncProgress.percent(840_000, 0))
        assertNull(SyncProgress.percent(10, -1))
        assertNull(SyncProgress.remaining(0, 0))
        assertFalse(SyncProgress.isSyncing(0, 0))
    }

    @Test
    fun `percent is whole and never rounds a partial sync up to complete`() {
        assertEquals(33, SyncProgress.percent(1, 3))
        assertEquals(99, SyncProgress.percent(999, 1000))
        assertEquals(100, SyncProgress.percent(1000, 1000))
    }

    @Test
    fun `blocks ahead of headers read as complete instead of above one hundred`() {
        assertEquals(100, SyncProgress.percent(1001, 1000))
        assertFalse(SyncProgress.isSyncing(1001, 1000))
        assertEquals(0L, SyncProgress.remaining(1001, 1000))
    }

    @Test
    fun `a fresh node reports zero rather than nothing`() {
        assertEquals(0, SyncProgress.percent(0, 100))
        assertEquals(100L, SyncProgress.remaining(0, 100))
        assertTrue(SyncProgress.isSyncing(0, 100))
    }

    @Test
    fun `remaining is the distance to the header tip`() {
        assertEquals(500L, SyncProgress.remaining(500, 1000))
        assertEquals(0L, SyncProgress.remaining(1000, 1000))
        assertNull(SyncProgress.remaining(0, 0))
    }

    @Test
    fun `a chain far larger than a phone will ever see does not overflow`() {
        val headers = 21_000_000_000_000L

        assertEquals(50, SyncProgress.percent(headers / 2, headers))
        assertEquals(100, SyncProgress.percent(headers, headers))
    }
}
