package org.stohncoin.wallet.runtime

import java.io.File

/**
 * PRoot unpacks its loader into `PROOT_TMP_DIR` on every run and leaves that copy behind whenever
 * the process dies without cleaning up — a crash, a low-memory kill, or a refused exec.
 *
 * Those copies used to land directly in the app cache, next to QR images, staged wallets and
 * backup snapshots, so nothing could safely delete them. Giving PRoot a directory of its own makes
 * the cleanup total and scoped: purging it can never touch anything that is not PRoot's.
 */
object RuntimeTemp {
    const val DIRECTORY_NAME = "proot-tmp"

    /** The PRoot temp directory below [cacheDir]; it is not required to exist yet. */
    fun directory(cacheDir: File): File = File(cacheDir, DIRECTORY_NAME)

    /** Creates the temp directory and returns it, for a PRoot launch that needs it to exist. */
    fun prepare(cacheDir: File): File {
        val dir = directory(cacheDir)
        check(dir.isDirectory || dir.mkdirs()) { "Unable to create the PRoot temp directory" }
        return dir
    }

    /**
     * Deletes everything inside the temp directory and returns how many entries went away.
     * Only a real directory that is a direct child of [cacheDir] is touched, so a doctored path
     * or a symlink cannot widen the delete beyond PRoot's own scratch space.
     */
    fun purge(cacheDir: File): Int {
        val dir = directory(cacheDir)
        if (!dir.isDirectory) return 0
        if (dir.canonicalFile.parentFile != cacheDir.canonicalFile) return 0
        val entries = dir.listFiles().orEmpty()
        var removed = 0
        for (entry in entries) {
            if (entry.deleteRecursively()) removed++
        }
        return removed
    }
}
