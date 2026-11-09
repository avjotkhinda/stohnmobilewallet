package org.stohncoin.wallet.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a short-lived Core tool as a child process: drain both pipes so a full buffer can never
 * stall it, kill it on timeout, and hand the result back as plain text.
 *
 * Shared by both backends so the wallet tool gets one timeout policy and one output-capture path
 * instead of two copies that can drift.
 */
internal object ProcessRunner {
    private const val DEFAULT_TIMEOUT_SECONDS = 120L

    fun run(
        command: List<String>,
        directory: File? = null,
        environment: Map<String, String> = emptyMap(),
        label: String = "Core tool",
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        onTimeout: () -> Unit = {}
    ): RuntimeBackend.ToolResult {
        val process = ProcessBuilder(command)
            .apply {
                if (directory != null) directory(directory)
                environment().putAll(environment)
            }
            .start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutDrain = Thread { process.inputStream.bufferedReader().forEachLine { line -> stdout.append(line).append('\n') } }
        val stderrDrain = Thread { process.errorStream.bufferedReader().forEachLine { line -> stderr.append(line).append('\n') } }
        stdoutDrain.start()
        stderrDrain.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdoutDrain.join(2_000)
            stderrDrain.join(2_000)
            onTimeout()
            error("$label timed out")
        }
        stdoutDrain.join(2_000)
        stderrDrain.join(2_000)
        return RuntimeBackend.ToolResult(process.exitValue(), stdout.toString(), stderr.toString())
    }
}
