package org.stohncoin.wallet.runtime

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RuntimeBackend {
    data class NodeInfo(val blocks: Long, val headers: Long, val peers: Int, val ibd: Boolean, val version: String = "unknown")
    suspend fun start()
    suspend fun stop()
    suspend fun status(): NodeInfo
}

object RuntimeBackendFactory {
    fun create(context: Context): RuntimeBackend = EmbeddedLinuxRuntime(context)
}
