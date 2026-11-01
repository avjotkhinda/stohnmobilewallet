package org.stohncoin.wallet.wallet

/**
 * Backup reminder policy.
 *
 * A backup only protects a user who made one recently, so the app records the time of the last
 * successful export and the number of spends since then, and nudges them once either threshold is
 * crossed. Spending matters because an old backup silently loses every coin received after it.
 *
 * Kept free of Android and storage so the policy itself is unit-tested directly; the timestamps
 * live in `NodeController`'s preferences and are surfaced as a flow.
 */
object BackupReminder {
    /** Long enough to be forgettable, short enough that the loss is not a month of history. */
    const val MAX_AGE_DAYS = 30L

    /** Every spend pushes the live wallet further ahead of any existing backup. */
    const val MAX_SENDS = 20

    const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    data class State(val lastBackupAt: Long = 0L, val sendsSinceBackup: Int = 0) {
        val hasBackup: Boolean get() = lastBackupAt > 0L
    }

    /** True when the user should be asked to create a fresh backup. */
    fun shouldRemind(
        state: State,
        now: Long,
        maxAgeDays: Long = MAX_AGE_DAYS,
        maxSends: Int = MAX_SENDS
    ): Boolean {
        if (!state.hasBackup) return true
        if (state.sendsSinceBackup >= maxSends) return true
        return ageDays(state, now) >= maxAgeDays
    }

    /**
     * Whole days since the last backup; -1 when there is none. A clock that moved backwards is
     * reported as "today" rather than as a negative age, so a device time change cannot hide a
     * reminder permanently.
     */
    fun ageDays(state: State, now: Long): Long {
        if (!state.hasBackup) return -1L
        val elapsed = now - state.lastBackupAt
        return if (elapsed <= 0L) 0L else elapsed / DAY_MILLIS
    }

    fun summary(state: State, now: Long): String {
        val days = ageDays(state, now)
        return when {
            days < 0L -> "No backup yet"
            days == 0L -> "Backed up today"
            days == 1L -> "Backed up 1 day ago"
            else -> "Backed up $days days ago"
        }
    }
}
