package org.stohncoin.wallet.runtime

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Bounded, rotating capture of Core's stdout/stderr. Core logs connection and DNS failures here,
 * which is the evidence needed when the node shows 0 peers.
 */
object CoreLog {
    const val FILE_NAME = "core_output.log"
    const val ROTATED_FILE_NAME = "core_output.log.1"
    const val MAX_BYTES = 256L * 1024L
    private val lock = Any()

    fun append(context: Context, line: String) {
        try {
            val dir = context.filesDir
            val file = File(dir, FILE_NAME)
            synchronized(lock) {
                if (file.length() >= MAX_BYTES) {
                    val rotated = File(dir, ROTATED_FILE_NAME)
                    if (rotated.exists()) rotated.delete()
                    if (!file.renameTo(rotated)) file.writeText("")
                }
                file.appendText("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())} $line\n")
            }
        } catch (_: Throwable) {
        }
    }
}
