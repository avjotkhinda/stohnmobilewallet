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
 * catches a refactor: if a source file starts carrying its own copy of a version, it can disagree
 * with the APK it ships in, and nothing else in the build would notice — a stale copy in a backup
 * manifest or a user agent looks perfectly valid on the device.
 *
 * "A version" and not "the current version": a copy left behind by an earlier bump is the more
 * likely mistake, so any string shaped like this project's version name (`x.y.z-fullmode`) counts,
 * as well as every release in `runtime/release-history.json`.
 */
class AppVersionSingleSourceTest {
    private val version = BuildConfig.VERSION_NAME

    @Test
    fun runtimeReportsTheVersionAndroidBuilt() {
        assertTrue("versionName should look like a version, was '$version'", NUMERIC_VERSION.containsMatchIn(version))
        assertEquals("RuntimeManifest.APP_VERSION must come from BuildConfig.VERSION_NAME", version, RuntimeManifest.APP_VERSION)
    }

    @Test
    fun noSourceFileRepeatsTheVersion() {
        val sources = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("cannot find the app sources from ${File(".").absolutePath}")
        val forbidden = forbiddenVersions()

        val offences = sources.walkTopDown()
            .filter { it.isFile && it.extension in SOURCE_TYPES }
            .flatMap { file ->
                val code = withoutComments(file.readText()).lines()
                code.withIndex().flatMap { (index, line) ->
                    forbidden.mapNotNull { pattern -> pattern.find(line)?.let { "  ${file.path}:${index + 1}: ${it.value}" } }
                }
            }
            .sorted()
            .toList()

        assertEquals(
            "these lines repeat an app version instead of reading BuildConfig.VERSION_NAME:\n" + offences.joinToString("\n"),
            emptyList<String>(),
            offences
        )
    }

    /**
     * The current version in full and bare, every released version the same way, and any string that
     * looks like this project's version name whatever its number. Bare historical version numbers are
     * only forbidden once a release recorded them: a source file comparing against a compatibility
     * threshold is doing something different from duplicating the version it ships.
     */
    private fun forbiddenVersions(): List<Regex> = buildList {
        (listOf(version) + releasedVersions()).forEach { candidate ->
            add(wholeToken(candidate))
            numericCore(candidate)?.let { add(wholeToken(it)) }
        }
        add(SUFFIXED_VERSION)
    }

    private fun numericCore(candidate: String): String? = NUMERIC_VERSION.find(candidate)?.value?.takeIf { it != candidate }

    private fun wholeToken(value: String): Regex = Regex("(?<![\\w.])${Regex.escape(value)}(?![\\w.])")

    /**
     * Every version in [HISTORY_FILE], not just the newest one: a copy of a release from three
     * versions ago is exactly as wrong as a copy of the current one, and it is the one that survives
     * a bump nobody re-read.
     */
    private fun releasedVersions(): List<String> {
        val history = listOf(File("../runtime/release-history.json"), File("runtime/release-history.json"))
            .firstOrNull { it.isFile }
            ?: error("cannot find runtime/release-history.json from ${File(".").absolutePath}")
        return HISTORY_VERSION.findAll(history.readText()).map { it.groupValues[1] }.distinct().toList()
    }

    /** Comments are documentation, not shipped values: only code is scanned. */
    private fun withoutComments(source: String): String = source
        .replace(BLOCK_COMMENT, "")
        .replace(LINE_COMMENT, "")

    private companion object {
        val NUMERIC_VERSION = Regex("""\d+\.\d+\.\d+""")
        val SUFFIXED_VERSION = Regex("""(?<![\w.])[0-9]+\.[0-9]+\.[0-9]+-fullmode(?![\w.])""")
        val HISTORY_VERSION = Regex(""""versionName"\s*:\s*"([^"]+)"""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val SOURCE_TYPES = setOf("kt", "java", "xml")
    }
}
