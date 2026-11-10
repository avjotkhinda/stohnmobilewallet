package org.stohncoin.wallet.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stohncoin.wallet.BuildConfig

/**
 * The app version is declared once, as `versionName` in `app/build.gradle.kts`, and reaches the
 * runtime through [BuildConfig] and [RuntimeManifest.APP_VERSION].
 *
 * Both halves matter and neither is guaranteed by construction. The first test pins the contract:
 * whatever the runtime reports is what Android built into the APK. The second one is the one that
 * catches a refactor: if any source file starts carrying its own copy of the version again, it can
 * disagree with the APK it ships in, and nothing else in the build would notice — a stale copy in a
 * backup manifest or a user agent looks perfectly valid on the device.
 */
class AppVersionSingleSourceTest {
    private val version = BuildConfig.VERSION_NAME

    @Test
    fun runtimeReportsTheVersionAndroidBuilt() {
        assertTrue("versionName should look like a version, was '$version'", VERSION_SHAPE.containsMatchIn(version))
        assertEquals("RuntimeManifest.APP_VERSION must come from BuildConfig.VERSION_NAME", version, RuntimeManifest.APP_VERSION)
    }

    @Test
    fun noSourceFileRepeatsTheVersion() {
        val sources = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("cannot find the app sources from ${File(".").absolutePath}")

        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension in SOURCE_TYPES }
            .mapNotNull { file -> file.takeIf { withoutComments(it.readText()).contains(version) } }
            .map { it.path }
            .sorted()
            .toList()

        assertEquals(
            "these files repeat the app version instead of reading BuildConfig.VERSION_NAME: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    /** Comments are documentation, not shipped values: only code is scanned. */
    private fun withoutComments(source: String): String = source
        .replace(BLOCK_COMMENT, "")
        .replace(LINE_COMMENT, "")

    private companion object {
        val VERSION_SHAPE = Regex("""^\d+\.\d+\.\d+""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val SOURCE_TYPES = setOf("kt", "java", "xml")
    }
}
