package org.stohncoin.wallet.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.stohncoin.wallet.ui.CrashLogCapture

/**
 * Watches the install's catch-all work, instead of only compiling it.
 *
 * A shared crash log once arrived as a header and nothing else, because the ring buffer was only
 * ever written from the link-refusal path. `installIfNeeded` now records the stage it reached and
 * then the throwable, before rethrowing; this test drives a real install on a Robolectric context
 * until it fails, and reads the log the user would have shared.
 *
 * The earliest step that fails here is the native-artifact verification: the four pinned shared
 * libraries are not in a unit-test APK, and their SHA-256 values are pins of real binaries, so no
 * fixture can pass them. That is still far enough to observe the wrapper working — the failure comes
 * from a step this test did not patch, is recorded, and still reaches the caller.
 */
@RunWith(RobolectricTestRunner::class)
class RuntimeInstallerInstallFailureTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // An empty nativeLibraryDir makes the install fail at "bundled artifacts missing" rather
        // than at an unresolvable path, so the recorded message is the installer's own.
        context.applicationInfo.nativeLibraryDir =
            File(context.cacheDir, "native-lib").apply { mkdirs() }.absolutePath
    }

    @Test
    fun aFailedInstallLeavesTheStageItReachedAndTheExceptionInTheCrashLog() {
        val thrown =
            runCatching {
                runBlocking { RuntimeInstaller.installIfNeeded(context) }
            }.exceptionOrNull()

        assertNotNull("the install must fail when the pinned native artifacts are absent", thrown)
        requireNotNull(thrown)
        requireNotNull(thrown.message) {
            "the failure must carry a message, or the log assertions below prove nothing"
        }
        println("INSTALL FAILURE SEEN: ${thrown.javaClass.name}: ${thrown.message}")
        assertTrue(
            "the failure must come from native-artifact verification, not from something earlier " +
                "in the wrapper: ${thrown.message}",
            thrown.message!!.startsWith("Bundled ") && thrown.message!!.endsWith(" is missing from the APK")
        )

        val log = CrashLogCapture.buildLog()

        assertTrue(
            "the log must record the stage the install reached:\n$log",
            log.contains("install: start")
        )
        assertTrue(
            "the log must carry the exception section:\n$log",
            log.contains("=== Last exception ===")
        )
        assertTrue(
            "the log must name the throwable ${thrown.javaClass.name}:\n$log",
            log.contains(thrown.javaClass.name)
        )
        assertTrue(
            "the log must carry the failure's message, not just its type:\n$log",
            log.contains(thrown.message!!)
        )
        assertTrue(
            "the stage line must appear as a recorded line, after the log-lines header:\n$log",
            log.indexOf("install: start") > log.indexOf("=== Recent app log lines ===")
        )
    }
}
