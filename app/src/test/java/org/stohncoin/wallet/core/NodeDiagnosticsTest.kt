package org.stohncoin.wallet.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.MainActivity
import org.stohncoin.wallet.runtime.CoreLog
import org.stohncoin.wallet.ui.readNodeDiagnostics

@RunWith(RobolectricTestRunner::class)
class NodeDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var logFile: File
    private lateinit var rotatedFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logFile = File(context.filesDir, "node_diagnostics.log")
        rotatedFile = File(context.filesDir, "node_diagnostics.log.1")
        logFile.delete()
        rotatedFile.delete()
        coreFile().delete()
        coreRotatedFile().delete()
        resetCrashHandlerFlag()
    }

    @After
    fun tearDown() {
        logFile.delete()
        rotatedFile.delete()
        coreFile().delete()
        coreRotatedFile().delete()
        resetCrashHandlerFlag()
    }

    @Test
    fun appendDiagnosticRotatesOnceLogReachesLimit() {
        logFile.writeText("x".repeat(512 * 1024))

        NodeController.appendDiagnostic(context, "Test", "after rotation")

        assertTrue("previous log should be kept as .1", rotatedFile.isFile)
        assertTrue("rotated log keeps the old contents", rotatedFile.length() >= 512L * 1024L)
        assertTrue("current log starts fresh", logFile.length() < 1024L)
        assertTrue(logFile.readText().contains("after rotation"))
    }

    @Test
    fun appendDiagnosticKeepsSmallLogInPlace() {
        NodeController.appendDiagnostic(context, "Test", "first line")
        NodeController.appendDiagnostic(context, "Test", "second line")

        assertTrue(logFile.isFile)
        assertEquals(false, rotatedFile.exists())
        val log = logFile.readText()
        assertTrue(log.contains("first line"))
        assertTrue(log.contains("second line"))
    }

    @Test
    fun diagnosticsIncludeRotatedLogBeforeCurrentLog() {
        rotatedFile.writeText("older entry\n")
        logFile.writeText("newer entry\n")

        val text = readNodeDiagnostics(context)

        assertTrue(text.contains("older entry"))
        assertTrue(text.contains("newer entry"))
        assertTrue(text.indexOf("older entry") < text.indexOf("newer entry"))
    }

    @Test
    fun cappedDiagnosticsKeepTheNewestBytesAcrossRotation() {
        rotatedFile.writeText("A".repeat(1000))
        logFile.writeText("B".repeat(1000))

        val text = readNodeDiagnostics(context, 500)

        assertEquals(500, text.length)
        assertFalse(text.contains("A"))
        assertTrue(text.startsWith("B"))
    }

    @Test
    fun coreOutputRotatesAndAppearsInDiagnostics() {
        coreFile().writeText("x".repeat(256 * 1024))

        CoreLog.append(context, "dns error connecting to seed")

        assertTrue(coreRotatedFile().isFile)
        assertTrue(coreFile().readText().contains("dns error connecting to seed"))
        val text = readNodeDiagnostics(context)
        assertTrue(text.contains("=== Core output ==="))
        assertTrue(text.contains("dns error connecting to seed"))
    }

    @Test
    fun crashHandlerIsInstalledOnlyOncePerProcess() {
        val savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        try {
            MainActivity.installCrashHandler(context)
            val first = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(first)

            MainActivity.installCrashHandler(context)
            val second = Thread.getDefaultUncaughtExceptionHandler()

            assertSame("repeated installs must not stack handlers", first, second)

            first!!.uncaughtException(Thread.currentThread(), IllegalStateException("handler test"))
            assertTrue(logFile.readText().contains("handler test"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(savedHandler)
        }
    }

    private fun coreFile(): File = File(context.filesDir, CoreLog.FILE_NAME)

    private fun coreRotatedFile(): File = File(context.filesDir, CoreLog.ROTATED_FILE_NAME)

    private fun resetCrashHandlerFlag() {
        val field = MainActivity::class.java.getDeclaredField("crashHandlerInstalled")
        field.isAccessible = true
        field.setBoolean(null, false)
    }
}
