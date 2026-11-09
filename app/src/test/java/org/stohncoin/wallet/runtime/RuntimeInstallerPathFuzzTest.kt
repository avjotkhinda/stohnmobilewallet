package org.stohncoin.wallet.runtime

import java.io.File
import java.nio.file.Files
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Property tests for the three path guards RuntimeInstaller uses while unpacking an archive:
 * [RuntimeInstaller.safeArchivePath], [RuntimeInstaller.safeDestination] and
 * [RuntimeInstaller.safeLinkTarget].
 *
 * The property under test is always the same one that matters for safety: either the guard
 * refuses the input with an IllegalArgumentException, or the value it hands back stays inside
 * the directory it was given. A generated case that is accepted but escapes, or that fails with
 * anything other than a refusal, fails the test with its seed so it can be replayed.
 */
class RuntimeInstallerPathFuzzTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val seed = 20261103L
    private val iterations = 500

    private fun <T> refusal(block: () -> T): Throwable? =
        runCatching(block).exceptionOrNull()

    /** Random names built from the segments traversal attacks are made of. */
    private fun randomName(rng: Random): String {
        val segments = listOf("..", ".", "", "a", "bin", "etc", "x.txt", "..", " ", "..")
        val separators = listOf("/", "//", "\\", "/\\", "", "/")
        val count = 1 + rng.nextInt(4)
        val out = StringBuilder()
        for (i in 0 until count) {
            out.append(segments[rng.nextInt(segments.size)])
            if (i != count - 1) out.append(separators[rng.nextInt(separators.size)])
        }
        return out.toString()
    }

    private fun confined(candidate: File, base: File): Boolean =
        candidate.path == base.path || candidate.path.startsWith(base.path + File.separator)

    @Test
    fun archivePathsAreEitherRefusedOrCleanlyRelative() {
        val rng = Random(seed)
        repeat(iterations) { i ->
            val name = randomName(rng)
            val failure = refusal { RuntimeInstaller.safeArchivePath(name) }
            if (failure == null) {
                val result = RuntimeInstaller.safeArchivePath(name)
                assertTrue(
                    "seed=$seed i=$i name='$name' produced a dirty path: '$result'",
                    (result == "." || (result.isNotEmpty() &&
                        !result.startsWith("/") &&
                        !result.contains('\\') &&
                        result.split('/').none { it.isEmpty() || it == "." || it == ".." }))
                )
            } else {
                assertTrue(
                    "seed=$seed i=$i name='$name' failed with ${failure.javaClass.simpleName}: ${failure.message}",
                    failure is IllegalArgumentException && failure.message == "Unsafe archive path"
                )
            }
        }
    }

    @Test
    fun aSanitizedDestinationAlwaysStaysInsideTheRoot() {
        val rng = Random(seed + 1)
        val root = temporaryFolder.newFolder("rootfs")
        val base = root.canonicalFile
        var checked = 0
        repeat(iterations) { i ->
            val relative = runCatching { RuntimeInstaller.safeArchivePath(randomName(rng)) }
                .getOrNull() ?: return@repeat
            val destination = RuntimeInstaller.safeDestination(root, relative)
            assertTrue(
                "seed=$seed i=$i relative='$relative' escaped to ${destination.path}",
                confined(destination, base)
            )
            checked++
        }
        assertTrue("no generated destination reached the check", checked > 100)
    }

    @Test
    fun anUnsanitizedDestinationThatEscapesTheRootIsRefused() {
        val root = temporaryFolder.newFolder("rootfs2")
        val escapes = listOf(
            "../escape", "../../escape", "a/../../escape", "/../escape",
            "..", "../a/../b", "../../outside", "sub/../../../outside"
        )
        for (relative in escapes) {
            val failure = refusal { RuntimeInstaller.safeDestination(root, relative) }
            assertTrue(
                "relative='$relative' escaped without a refusal: ${failure?.message}",
                failure is IllegalArgumentException && failure.message == "Archive path escapes destination"
            )
        }
    }

    @Test
    fun aSymlinkInsideTheRootThatPointsOutsideIsRefused() {
        val root = temporaryFolder.newFolder("linkroot")
        val outside = temporaryFolder.newFolder("outside")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        val failure = refusal { RuntimeInstaller.safeDestination(root, "escape/child") }
        assertTrue(
            "a symlinked child was allowed through: ${failure?.message}",
            failure is IllegalArgumentException && failure.message == "Archive path escapes destination"
        )
    }

    @Test
    fun linkTargetsAreEitherRefusedOrConfinedToTheRootfs() {
        val rng = Random(seed + 3)
        val root = temporaryFolder.newFolder("links")
        val base = root.canonicalFile
        val pool = listOf(
            "", "   ", "a\\b", "..", "../x", "/../etc/passwd", "bin/sh", "/bin/sh",
            "usr/../../outside", "../../../../../../etc/hosts", ".", "./bin/sh", "a/..",
            "bin/../../../..", "/bin/../.."
        )
        var accepted = 0
        repeat(iterations) { i ->
            val target = if (rng.nextBoolean()) pool[rng.nextInt(pool.size)] else randomName(rng)
            val failure = refusal { RuntimeInstaller.safeLinkTarget(root, root, target) }
            if (failure == null) {
                val resolved = RuntimeInstaller.safeLinkTarget(root, root, target)
                assertTrue(
                    "seed=$seed i=$i target='$target' resolved outside the rootfs: ${resolved.path}",
                    confined(resolved, base)
                )
                accepted++
            } else {
                assertTrue(
                    "seed=$seed i=$i target='$target' failed with ${failure.javaClass.simpleName}: ${failure.message}",
                    failure is IllegalArgumentException &&
                        (failure.message == "Unsafe link target" || failure.message == "Link target escapes rootfs")
                )
            }
        }
        assertTrue("every generated link target was refused, so nothing was exercised", accepted > 0)
    }
}
