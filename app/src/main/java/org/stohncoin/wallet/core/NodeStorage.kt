package org.stohncoin.wallet.core

import java.io.File
import java.util.Locale

/**
 * Chain-storage accounting and prune-mode rules for the local node.
 *
 * Deliberately free of Android types so the size walk, the human formatting, and — most importantly
 * — the exact set of paths a mode change is allowed to delete are unit tested on the JVM. Deleting
 * one directory too many here would take the wallet with it.
 */
object NodeStorage {
    /**
     * Modes offered in the UI. Core only accepts `-prune` when it starts, and refuses to reuse a
     * chain database built under a different mode, which is why switching clears the chain.
     */
    enum class Mode(val pruneMiB: Int, val label: String, val detail: String) {
        FULL(0, "Full chain", "Keeps every block and serves old ones to other nodes"),
        MINIMAL(550, "Pruned · 550 MiB", "Keeps only recent blocks; the smallest footprint"),
        COMPACT(5500, "Pruned · 5.5 GiB", "Keeps more history than the minimal mode")
    }

    data class Usage(val bytes: Long, val files: Int)

    /**
     * The only directories a mode change may delete. `wallets`, `stohn.conf`, the RPC cookie and
     * every backup artefact live next to these and are never in the list.
     */
    private val CHAIN_DIRECTORIES = listOf("blocks", "chainstate", "indexes")

    /** Total bytes and file count under [root]; a missing directory measures as empty. */
    fun measure(root: File): Usage {
        if (!root.isDirectory) return Usage(0L, 0)
        var bytes = 0L
        var files = 0
        root.walkTopDown().forEach { entry ->
            if (entry.isFile) {
                bytes += entry.length()
                files++
            }
        }
        return Usage(bytes, files)
    }

    /** Binary units with one decimal, formatted with a fixed locale so tests and UI agree. */
    fun format(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    /**
     * Clears the chain database so Core will start under a new prune mode. Only [CHAIN_DIRECTORIES]
     * is touched, and each target is re-checked to be a direct child of [dataDir] first, so a
     * doctored path cannot turn this into a wider delete.
     */
    fun resetChainData(dataDir: File): Usage {
        require(dataDir.isDirectory) { "The node data directory does not exist" }
        val before = measure(dataDir)
        for (name in CHAIN_DIRECTORIES) {
            val target = File(dataDir, name)
            require(target.parentFile?.canonicalPath == dataDir.canonicalPath) {
                "Refusing to delete outside the node data directory"
            }
            if (target.exists() && !target.deleteRecursively()) {
                error("Unable to remove ${target.name} from the node data directory")
            }
        }
        val after = measure(dataDir)
        return Usage(
            bytes = (before.bytes - after.bytes).coerceAtLeast(0L),
            files = (before.files - after.files).coerceAtLeast(0)
        )
    }
}
