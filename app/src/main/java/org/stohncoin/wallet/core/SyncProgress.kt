package org.stohncoin.wallet.core

/**
 * Sync completion derived from Core's own block and header counters.
 *
 * Kept pure and separate from [NodeController] so the awkward states — a node that has not heard a
 * header yet, headers arriving faster than blocks, a node that already finished — are unit tested
 * instead of being eyeballed on a device. Integer arithmetic only, so the percentage on screen can
 * never disagree with the two counters printed next to it.
 */
object SyncProgress {
    /**
     * Whole percent of headers whose blocks are already validated, or null while Core has not
     * reported a header count. Blocks ahead of headers (validation outrunning header download)
     * reads as complete rather than as a percentage above 100.
     */
    fun percent(blocks: Long, headers: Long): Int? {
        if (headers <= 0L) return null
        if (blocks <= 0L) return 0
        if (blocks >= headers) return 100
        return (blocks * 100L / headers).toInt().coerceIn(0, 100)
    }

    /** Blocks still to download, or null when the target is unknown. Never negative. */
    fun remaining(blocks: Long, headers: Long): Long? {
        if (headers <= 0L) return null
        return (headers - blocks).coerceAtLeast(0L)
    }

    /** True while headers are still ahead of blocks, which is what "syncing" means here. */
    fun isSyncing(blocks: Long, headers: Long): Boolean = headers > 0L && blocks < headers
}
