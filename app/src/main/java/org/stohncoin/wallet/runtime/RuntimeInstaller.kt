package org.stohncoin.wallet.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Installs the verified ARM64 Debian and Stohn Core runtime into app-private storage. */
object RuntimeInstaller {
    private val mutex = Mutex()

    suspend fun installIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = File(context.filesDir, "fullmode/runtime")
            val bin = File(root, "bin")
            val rootfs = File(root, "rootfs")
            root.mkdirs()
            bin.mkdirs()

            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            verifyNativeArtifact(
                File(nativeDir, "libproot.so"),
                "proot",
                RuntimeManifest.PROOT_SHA256
            )
            verifyNativeArtifact(
                File(nativeDir, "libproot-loader.so"),
                "proot-loader",
                RuntimeManifest.PROOT_LOADER_SHA256
            )
            verifyNativeArtifact(
                File(nativeDir, "libtalloc.so"),
                "libtalloc",
                RuntimeManifest.LIBTALLOC_SHA256
            )
            verifyNativeArtifact(
                File(nativeDir, "libandroid-shmem.so"),
                "libandroid-shmem",
                RuntimeManifest.LIBANDROID_SHMEM_SHA256
            )

            val rootfsMarker = File(root, ".debian-rootfs.complete")
            val rootfsValid =
                rootfsMarker.isFile &&
                    File(rootfs, "bin/sh").isFile &&
                    rootfsMarker.readText().trim() == RuntimeManifest.DEBIAN_SHA256

            if (!rootfsValid) {
                rootfs.deleteRecursively()
                rootfs.mkdirs()

                val archive = File(root, "debian.tar.xz")
                downloadVerified(
                    RuntimeManifest.DEBIAN_URL,
                    archive,
                    RuntimeManifest.DEBIAN_SHA256
                )
                extractTarXz(archive, rootfs)
                prepareRootfs(rootfs)
                archive.delete()
                rootfsMarker.writeText(RuntimeManifest.DEBIAN_SHA256 + "\n")
            }

            val core = File(bin, "stohncoind")
            val walletTool = File(bin, "stohncoin-wallet")
            val coreHashFile = File(root, ".stohncoind.sha256")
            val walletHashFile = File(root, ".stohncoin-wallet.sha256")

            val coreValid =
                core.isFile &&
                    coreHashFile.isFile &&
                    coreHashFile.readText().trim().matches(HEX64) &&
                    sha256(core) == coreHashFile.readText().trim()

            val walletValid =
                walletTool.isFile &&
                    walletHashFile.isFile &&
                    walletHashFile.readText().trim().matches(HEX64) &&
                    sha256(walletTool) == walletHashFile.readText().trim()

            if (!coreValid || !walletValid) {
                bin.deleteRecursively()
                bin.mkdirs()

                val archive = File(root, "stohn-core.tar.gz")
                downloadVerified(
                    RuntimeManifest.CORE_URL,
                    archive,
                    RuntimeManifest.CORE_SHA256
                )
                extractCore(archive, bin)
                archive.delete()
            }

            verifyArm64Elf(core, "stohncoind")
            verifyArm64Elf(walletTool, "stohncoin-wallet")

            core.setExecutable(true)
            walletTool.setExecutable(true)

            val coreHash = sha256(core)
            val walletHash = sha256(walletTool)

            coreHashFile.writeText("$coreHash\n")
            walletHashFile.writeText("$walletHash\n")

            File(root, ".installed-v5").writeText(
                "app=${RuntimeManifest.APP_VERSION}\n" +
                    "debian=${RuntimeManifest.DEBIAN_VERSION}\n" +
                    "debian-sha256=${RuntimeManifest.DEBIAN_SHA256}\n" +
                    "core=${RuntimeManifest.CORE_VERSION}\n" +
                    "core-archive-sha256=${RuntimeManifest.CORE_SHA256}\n" +
                    "stohncoind-sha256=$coreHash\n" +
                    "stohncoin-wallet-sha256=$walletHash\n"
            )
        }
    }

    private fun prepareRootfs(rootfs: File) {
        require(File(rootfs, "bin/sh").isFile) {
            "Invalid Debian rootfs: /bin/sh missing"
        }

        File(rootfs, "home/stohn").mkdirs()
        File(rootfs, "opt/stohn").mkdirs()

        val resolv = File(rootfs, "etc/resolv.conf")
        if (Files.isSymbolicLink(resolv.toPath())) {
            Files.delete(resolv.toPath())
        }

        if (!resolv.exists()) {
            resolv.parentFile?.mkdirs()
            resolv.writeText(
                "# Runtime DNS is bind-mounted by EmbeddedLinuxRuntime.\n"
            )
        }

        val hosts = File(rootfs, "etc/hosts")
        if (Files.isSymbolicLink(hosts.toPath())) {
            Files.delete(hosts.toPath())
        }

        if (!hosts.exists()) {
            hosts.parentFile?.mkdirs()
            hosts.writeText(
                "127.0.0.1 localhost\n" +
                    "::1 localhost ip6-localhost ip6-loopback\n"
            )
        }
    }

    private fun downloadVerified(
        url: String,
        target: File,
        expected: String
    ) {
        require(url.startsWith("https://")) {
            "Runtime source must use HTTPS"
        }

        require(expected.matches(HEX64)) {
            "Runtime artifact SHA-256 is not pinned"
        }

        val tmp = File(target.parentFile, target.name + ".part")
        tmp.delete()

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty(
            "User-Agent",
            "StohnWallet-FullMode/${RuntimeManifest.APP_VERSION}"
        )

        try {
            conn.connect()

            require(
                conn.url.protocol.equals("https", ignoreCase = true)
            ) {
                "Runtime download redirected to non-HTTPS"
            }

            require(conn.responseCode in 200..299) {
                "Download failed: HTTP ${conn.responseCode}"
            }

            val declared = conn.contentLengthLong
            require(
                declared < 0 || declared <= MAX_DOWNLOAD_BYTES
            ) {
                "Runtime download is unexpectedly large"
            }

            var total = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    total = copyLimited(
                        input,
                        output,
                        MAX_DOWNLOAD_BYTES
                    )
                    output.fd.sync()
                }
            }

            require(total > 0L) {
                "Runtime download was empty"
            }

            require(
                sha256(tmp) == expected.lowercase()
            ) {
                "Integrity check failed for ${target.name}"
            }

            if (target.exists()) {
                require(target.delete()) {
                    "Unable to replace ${target.name}"
                }
            }

            require(tmp.renameTo(target)) {
                "Unable to install ${target.name}"
            }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    private fun verifyNativeArtifact(
        file: File,
        name: String,
        expected: String
    ) {
        require(file.isFile) {
            "Bundled $name is missing from the APK"
        }

        verifyArm64Elf(file, name)

        require(expected.matches(HEX64)) {
            "$name SHA-256 is not pinned; release build blocked"
        }

        require(sha256(file) == expected.lowercase()) {
            "Bundled $name integrity check failed"
        }
    }

    private fun verifyArm64Elf(
        file: File,
        name: String
    ) {
        FileInputStream(file).use { input ->
            val h = ByteArray(20)

            require(input.read(h) == h.size) {
                "$name is too small to be ELF64"
            }

            require(
                h[0] == 0x7f.toByte() &&
                    h[1] == 'E'.code.toByte() &&
                    h[2] == 'L'.code.toByte() &&
                    h[3] == 'F'.code.toByte()
            ) {
                "$name is not ELF"
            }

            require(
                h[4].toInt() == 2 &&
                    h[5].toInt() == 1
            ) {
                "$name is not little-endian ELF64"
            }

            val machine =
                (h[18].toInt() and 0xff) or
                    ((h[19].toInt() and 0xff) shl 8)

            require(machine == 183) {
                "$name is not AArch64"
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")

        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)

            while (true) {
                val n = input.read(buffer)

                if (n < 0) {
                    break
                }

                md.update(buffer, 0, n)
            }
        }

        return md.digest().joinToString("") {
            "%02x".format(it)
        }
    }

    private fun extractCore(
        archive: File,
        out: File
    ) {
        var entries = 0
        var bytes = 0L

        GZIPInputStream(
            FileInputStream(archive),
            128 * 1024
        ).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break

                    require(++entries <= MAX_ARCHIVE_ENTRIES) {
                        "Core archive contains too many entries"
                    }

                    require(
                        !e.isSymbolicLink &&
                            !e.isLink &&
                            !e.isCharacterDevice &&
                            !e.isBlockDevice &&
                            !e.isFIFO
                    ) {
                        "Unsupported Core archive entry type"
                    }

                    val path = safeArchivePath(e.name)

                    if (e.isDirectory) {
                        continue
                    }

                    val name = path.substringAfterLast('/')

                    if (name !in CORE_FILES) {
                        continue
                    }

                    val destination = safeDestination(out, name)

                    destination.parentFile?.mkdirs()

                    FileOutputStream(destination).use { output ->
                        bytes += copyLimited(
                            tar,
                            output,
                            MAX_EXTRACTED_BYTES - bytes
                        )
                    }
                }
            }
        }

        require(File(out, "stohncoind").isFile) {
            "stohncoind missing from Core archive"
        }

        require(File(out, "stohncoin-wallet").isFile) {
            "stohncoin-wallet missing from Core archive"
        }
    }

    private fun extractTarXz(
        archive: File,
        out: File
    ) {
        val temp = File(
            out.parentFile,
            out.name + ".extracting"
        )

        temp.deleteRecursively()
        temp.mkdirs()

        data class Link(
            val path: String,
            val target: String,
            val hard: Boolean
        )

        val links = mutableListOf<Link>()

        var entries = 0
        var bytes = 0L

        try {
            XZInputStream(
                FileInputStream(archive),
                128 * 1024
            ).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val e = tar.nextTarEntry ?: break

                        require(++entries <= MAX_ARCHIVE_ENTRIES) {
                            "Rootfs archive contains too many entries"
                        }

                        val path = safeArchivePath(e.name)
                        val destination = safeDestination(temp, path)

                        when {
                            e.isSymbolicLink -> {
                                links += Link(
                                    path,
                                    e.linkName,
                                    false
                                )
                            }

                            e.isLink -> {
                                links += Link(
                                    path,
                                    e.linkName,
                                    true
                                )
                            }

                            e.isCharacterDevice ||
                                e.isBlockDevice ||
                                e.isFIFO -> {
                                error(
                                    "Unsupported rootfs archive entry type"
                                )
                            }

                            e.isDirectory -> {
                                destination.mkdirs()
                            }

                            else -> {
                                destination.parentFile?.mkdirs()

                                FileOutputStream(destination).use { output ->
                                    bytes += copyLimited(
                                        tar,
                                        output,
                                        MAX_EXTRACTED_BYTES - bytes
                                    )
                                }

                                if (
                                    (e.mode and 0b001000000) != 0
                                ) {
                                    destination.setExecutable(
                                        true,
                                        false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Normalize a plain root tree or a single top-level wrapper
            // BEFORE creating links. Absolute symlink targets are guest-root-relative.
            val root =
                if (File(temp, "bin/sh").isFile) {
                    temp
                } else {
                    val candidates =
                        temp.listFiles()
                            ?.filter {
                                it.isDirectory &&
                                    File(it, "bin/sh").isFile
                            }
                            .orEmpty()

                    require(candidates.size == 1) {
                        "Invalid Debian rootfs archive layout"
                    }

                    candidates.single()
                }

            if (out.exists()) {
                out.deleteRecursively()
            }

            out.mkdirs()

            root.listFiles()?.forEach { child ->
                require(
                    child.renameTo(
                        File(out, child.name)
                    )
                ) {
                    "Unable to normalize rootfs"
                }
            }

            for (link in links) {
                val relativeLink =
                    if (root != temp) {
                        link.path.removePrefix(
                            root.name + "/"
                        )
                    } else {
                        link.path
                    }

                require(relativeLink.isNotBlank()) {
                    "Invalid rootfs link path"
                }

                val destination =
                    safeDestination(
                        out,
                        relativeLink
                    )

                destination.parentFile?.mkdirs()

                if (
                    destination.exists() ||
                    Files.isSymbolicLink(destination.toPath())
                ) {
                    destination.delete()
                }

                val target =
                    safeLinkTarget(
                        out,
                        destination.parentFile ?: out,
                        link.target
                    )

                if (link.hard) {
                    require(target.isFile) {
                        "Hardlink target missing: ${link.target}"
                    }

                    Files.createLink(
                        destination.toPath(),
                        target.toPath()
                    )
                } else {
                    val relativeTarget =
                        destination.parentFile!!
                            .toPath()
                            .relativize(target.toPath())
                            .toString()

                    Files.createSymbolicLink(
                        destination.toPath(),
                        Paths.get(relativeTarget)
                    )
                }
            }
        } finally {
            temp.deleteRecursively()
        }

        require(File(out, "bin/sh").isFile) {
            "Invalid Debian rootfs: /bin/sh missing"
        }
    }

    private fun safeLinkTarget(
        root: File,
        linkParent: File,
        target: String
    ): File {
        // Do not use String.contains()/indexOf() here.
        // The character-level check avoids Kotlin overload ambiguity.
        require(
            target.isNotBlank() &&
                !target.any { it == '\\' }
        ) {
            "Unsafe link target"
        }

        val base = root.canonicalFile

        val candidate =
            if (target.startsWith('/')) {
                File(
                    base,
                    target.removePrefix("/")
                )
            } else {
                File(
                    linkParent,
                    target
                )
            }

        val resolved = candidate.canonicalFile

        require(
            resolved.path == base.path ||
                resolved.path.startsWith(
                    base.path + File.separator
                )
        ) {
            "Link target escapes rootfs"
        }

        return resolved
    }

    private fun safeArchivePath(
    name: String
): String {
    require(
        name.isNotBlank() &&
            !name.startsWith('/') &&
            !name.startsWith('\\')
    ) {
        "Unsafe archive path"
    }

    val normalized =
        name
            .replace('\\', '/')
            .trimEnd('/')

    val parts = normalized.split('/')

    // "." is a harmless current-directory component and is common
    // in tar archives. ".." and empty components are rejected.
    require(
        parts.none {
            it.isEmpty() ||
                it == ".."
        }
    ) {
        "Unsafe archive path"
    }

    val cleaned = parts.filter { it != "." }

    require(cleaned.isNotEmpty()) {
        "Unsafe archive path"
    }

    return cleaned.joinToString("/")
}

    private fun safeDestination(
        root: File,
        relative: String
    ): File {
        val destination =
            File(root, relative).canonicalFile

        val base = root.canonicalFile

        require(
            destination.path == base.path ||
                destination.path.startsWith(
                    base.path + File.separator
                )
        ) {
            "Archive path escapes destination"
        }

        return destination
    }

    private fun copyLimited(
        input: InputStream,
        output: OutputStream,
        remaining: Long
    ): Long {
        require(remaining >= 0) {
            "Archive exceeds extraction limit"
        }

        var total = 0L
        val buffer = ByteArray(128 * 1024)

        while (true) {
            val n = input.read(buffer)

            if (n < 0) {
                break
            }

            total += n

            require(total <= remaining) {
                "Archive exceeds extraction limit"
            }

            output.write(
                buffer,
                0,
                n
            )
        }

        return total
    }

    private val CORE_FILES =
        setOf(
            "stohncoind",
            "stohncoin-wallet",
            "stohncoin-cli",
            "stohncoin-tx"
        )

    private val HEX64 =
        Regex("[0-9a-f]{64}")

    private const val MAX_DOWNLOAD_BYTES =
        512L * 1024 * 1024

    private const val MAX_EXTRACTED_BYTES =
        2L * 1024 * 1024 * 1024

    private const val MAX_ARCHIVE_ENTRIES =
        250_000
}
