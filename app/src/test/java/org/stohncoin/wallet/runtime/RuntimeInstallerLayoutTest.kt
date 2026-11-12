package org.stohncoin.wallet.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

/**
 * The layout decision in [RuntimeInstaller.extractTarXz]: is this archive a plain root tree or a
 * single top-level wrapper?
 *
 * It used to be answered by asking the half-extracted tree for bin/sh. A Debian bookworm rootfs is
 * merged-/usr - ./bin is a symlink to usr/bin - and symlinks are created only after that decision,
 * so bin/sh could never resolve, no wrapper candidate matched either, and every install of the
 * pinned archive failed with "Invalid Debian rootfs archive layout". The decision had no test,
 * which is why the fixtures below are the shapes it has to accept and to refuse.
 */
class RuntimeInstallerLayoutTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** One archive member: a directory, a regular file, or a symbolic link. */
    private sealed class Member(val name: String) {
        class Dir(name: String) : Member(name)
        class Regular(name: String, val bytes: ByteArray) : Member(name)
        class Link(name: String, val target: String) : Member(name)
    }

    private fun archive(entries: List<Member>): File {
        val file = temporaryFolder.newFile("rootfs.tar.xz")
        XZOutputStream(FileOutputStream(file), LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { member ->
                    val entry = when (member) {
                        is Member.Dir -> TarArchiveEntry(member.name)
                        is Member.Regular -> TarArchiveEntry(member.name).also {
                            it.size = member.bytes.size.toLong()
                        }
                        is Member.Link -> TarArchiveEntry(member.name, TarConstants.LF_SYMLINK).also {
                            it.linkName = member.target
                        }
                    }
                    tar.putArchiveEntry(entry)
                    if (member is Member.Regular) {
                        tar.write(member.bytes)
                    }
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return file
    }

    @Test
    fun aMergedUsrRootfsIsReadAsAPlainRootTree() {
        val archive = archive(
            listOf(
                Member.Dir("./"),
                Member.Link("./bin", "usr/bin"),
                Member.Dir("./etc/"),
                Member.Dir("./usr/"),
                Member.Dir("./usr/bin/"),
                Member.Link("./usr/bin/sh", "dash"),
                Member.Regular("./usr/bin/dash", byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            )
        )
        val out = temporaryFolder.newFolder("merged-usr")

        RuntimeInstaller.extractTarXz(archive, out)

        assertTrue(
            "/bin/sh must resolve through bin -> usr/bin and sh -> dash",
            File(out, "bin/sh").isFile
        )
        assertTrue(
            "bin must stay the archive's own symlink",
            Files.isSymbolicLink(File(out, "bin").toPath())
        )
        assertEquals(
            "usr/bin",
            Files.readSymbolicLink(File(out, "bin").toPath()).toString()
        )
        assertTrue("the rest of the tree must come with it", File(out, "etc").isDirectory)
    }

    /**
     * A Debian rootfs links `etc/alternatives/awk` to `/usr/bin/mawk`. An absolute target is
     * guest-root-relative, so it must be resolved inside the extraction root instead of being
     * refused as an escape - which is what made every install fail with "Unsafe link target:
     * absolute linkName=/usr/bin/mawk".
     */
    @Test
    fun anAbsoluteLinkTargetIsGuestRootRelativeNotAnEscape() {
        val archive = archive(
            listOf(
                Member.Dir("./"),
                Member.Link("./bin", "usr/bin"),
                Member.Dir("./etc/"),
                Member.Dir("./etc/alternatives/"),
                Member.Dir("./usr/"),
                Member.Dir("./usr/bin/"),
                Member.Link("./usr/bin/sh", "dash"),
                Member.Regular("./usr/bin/dash", byteArrayOf(0x7f, 0x45, 0x4c, 0x46)),
                Member.Regular("./usr/bin/mawk", byteArrayOf(0x7f, 0x45, 0x4c, 0x46)),
                Member.Link("./etc/alternatives/awk", "/usr/bin/mawk")
            )
        )
        val out = temporaryFolder.newFolder("absolute-link")

        RuntimeInstaller.extractTarXz(archive, out)

        val link = File(out, "etc/alternatives/awk")
        assertTrue(
            "the absolute target must become a symlink inside the rootfs",
            Files.isSymbolicLink(link.toPath())
        )
        assertEquals(
            "the link must land on the rootfs's own /usr/bin/mawk",
            File(out, "usr/bin/mawk").canonicalPath,
            link.canonicalFile.path
        )
    }

    /**
     * `etc/dhcp/dhclient-enter-hooks.d/debug` is a relative link to `../debug`, which lands on
     * `etc/dhcp/debug` inside the rootfs. Refusing every ".." component refused this archive.
     */
    @Test
    fun aParentStepThatStaysInsideTheRootIsAccepted() {
        val archive = archive(
            listOf(
                Member.Dir("./"),
                Member.Link("./bin", "usr/bin"),
                Member.Dir("./etc/"),
                Member.Dir("./etc/dhcp/"),
                Member.Dir("./etc/dhcp/dhclient-enter-hooks.d/"),
                Member.Regular("./etc/dhcp/debug", byteArrayOf(0x01)),
                Member.Dir("./usr/"),
                Member.Dir("./usr/bin/"),
                Member.Link("./usr/bin/sh", "dash"),
                Member.Regular("./usr/bin/dash", byteArrayOf(0x7f, 0x45, 0x4c, 0x46)),
                Member.Link("./etc/dhcp/dhclient-enter-hooks.d/debug", "../debug")
            )
        )
        val out = temporaryFolder.newFolder("relative-parent")

        RuntimeInstaller.extractTarXz(archive, out)

        val link = File(out, "etc/dhcp/dhclient-enter-hooks.d/debug")
        assertTrue(
            "a contained ../ target must become a symlink",
            Files.isSymbolicLink(link.toPath())
        )
        assertEquals(
            "../debug must land on the rootfs's own etc/dhcp/debug",
            File(out, "etc/dhcp/debug").canonicalPath,
            link.canonicalFile.path
        )
    }

    /** Resolution, not the presence of "..", decides: a target that climbs out is still refused. */
    @Test
    fun aLinkThatClimbsOutOfTheRootfsIsStillRefused() {
        val archive = archive(
            listOf(
                Member.Dir("./"),
                Member.Link("./bin", "usr/bin"),
                Member.Dir("./etc/"),
                Member.Dir("./usr/"),
                Member.Dir("./usr/bin/"),
                Member.Link("./usr/bin/sh", "dash"),
                Member.Regular("./usr/bin/dash", byteArrayOf(0x7f, 0x45, 0x4c, 0x46)),
                Member.Link("./etc/escape", "../../../../../../etc/passwd")
            )
        )
        val out = temporaryFolder.newFolder("escaping-relative")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            RuntimeInstaller.extractTarXz(archive, out)
        }

        assertTrue(
            "the refusal must name the escaping resolution, not a bare '..': ${failure.message}",
            failure.message.orEmpty().contains("Link target escapes rootfs")
        )
        assertTrue(
            "the refusal must carry the link and archive: ${failure.message}",
            failure.message.orEmpty().contains("link=etc/escape") &&
                failure.message.orEmpty().contains("archive=")
        )
        assertTrue("a refused link must not be created", !Files.isSymbolicLink(File(out, "etc/escape").toPath()))
    }

    @Test
    fun aSingleTopLevelWrapperIsStillUnwrapped() {
        val archive = archive(
            listOf(
                Member.Dir("rootfs/"),
                Member.Dir("rootfs/bin/"),
                Member.Regular("rootfs/bin/sh", byteArrayOf(0x01)),
                Member.Dir("rootfs/etc/")
            )
        )
        val out = temporaryFolder.newFolder("wrapped")

        RuntimeInstaller.extractTarXz(archive, out)

        assertTrue(File(out, "bin/sh").isFile)
        assertTrue(File(out, "etc").isDirectory)
        assertTrue("the wrapper itself must not survive", !File(out, "rootfs").exists())
    }

    @Test
    fun aTreeWithoutAUsableShellIsStillRefused() {
        val archive = archive(
            listOf(
                Member.Dir("./"),
                Member.Dir("./bin/"),
                Member.Dir("./etc/")
            )
        )
        val out = temporaryFolder.newFolder("no-shell")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            RuntimeInstaller.extractTarXz(archive, out)
        }

        assertEquals("Invalid Debian rootfs: /bin/sh missing", failure.message)
    }
}
