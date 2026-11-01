package org.stohncoin.wallet.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReminderTest {
    private val now = 1_700_000_000_000L

    private fun daysAgo(days: Long): Long = now - days * BackupReminder.DAY_MILLIS

    private fun backedUp(days: Long, sends: Int = 0) =
        BackupReminder.State(lastBackupAt = daysAgo(days), sendsSinceBackup = sends)

    @Test
    fun `a wallet that was never backed up is always reminded`() {
        assertTrue(BackupReminder.shouldRemind(BackupReminder.State(), now))
        assertEquals(-1L, BackupReminder.ageDays(BackupReminder.State(), now))
        assertEquals("No backup yet", BackupReminder.summary(BackupReminder.State(), now))
    }

    @Test
    fun `a recent backup with a few spends is left alone`() {
        assertFalse(BackupReminder.shouldRemind(backedUp(days = 3, sends = 4), now))
    }

    @Test
    fun `a backup older than a month is reminded`() {
        assertFalse(BackupReminder.shouldRemind(backedUp(days = 29), now))
        assertTrue(BackupReminder.shouldRemind(backedUp(days = 30), now))
        assertTrue(BackupReminder.shouldRemind(backedUp(days = 400), now))
    }

    @Test
    fun `enough spends trigger the reminder even for a fresh backup`() {
        assertFalse(BackupReminder.shouldRemind(backedUp(days = 1, sends = BackupReminder.MAX_SENDS - 1), now))
        assertTrue(BackupReminder.shouldRemind(backedUp(days = 1, sends = BackupReminder.MAX_SENDS), now))
    }

    @Test
    fun `a clock that jumped backwards does not look like an ancient backup`() {
        val future = BackupReminder.State(lastBackupAt = now + 5 * BackupReminder.DAY_MILLIS, sendsSinceBackup = 0)

        assertEquals(0L, BackupReminder.ageDays(future, now))
        assertFalse(BackupReminder.shouldRemind(future, now))
        assertEquals("Backed up today", BackupReminder.summary(future, now))
    }

    @Test
    fun `summary describes the age in human terms`() {
        assertEquals("Backed up today", BackupReminder.summary(backedUp(days = 0), now))
        assertEquals("Backed up 1 day ago", BackupReminder.summary(backedUp(days = 1), now))
        assertEquals("Backed up 12 days ago", BackupReminder.summary(backedUp(days = 12), now))
    }

    @Test
    fun `thresholds can be configured per call`() {
        val state = backedUp(days = 2, sends = 1)

        assertFalse(BackupReminder.shouldRemind(state, now, maxAgeDays = 7))
        assertTrue(BackupReminder.shouldRemind(state, now, maxAgeDays = 1))
        assertTrue(BackupReminder.shouldRemind(state, now, maxAgeDays = 7, maxSends = 1))
    }
}
