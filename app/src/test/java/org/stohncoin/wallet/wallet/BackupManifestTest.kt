package org.stohncoin.wallet.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {
    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun manifest(
        appVersion: String = "0.28.2",
        walletName: String = "stohn-wallet",
        creationHeight: Long = 123456L,
        walletSha256: String = sha
    ) = BackupManifest(appVersion, walletName, creationHeight, walletSha256)

    @Test
    fun `app version is the first thing a manifest says`() {
        val rendered = BackupManifest.render("0.28.2", "stohn-wallet", 123456L, sha)

        assertTrue(rendered.startsWith("app_version=0.28.2\n"))
        assertEquals(
            "app_version=0.28.2\n" +
                "wallet_name=stohn-wallet\n" +
                "creation_height=123456\n" +
                "sha256=$sha\n",
            rendered
        )
    }

    @Test
    fun `a rendered manifest parses back into the same details`() {
        val parsed = BackupManifest.parse(BackupManifest.render("0.28.2", "my-wallet", 42L, sha))

        assertEquals("0.28.2", parsed.appVersion)
        assertEquals("my-wallet", parsed.walletName)
        assertEquals(42L, parsed.creationHeight)
        assertEquals(sha, parsed.walletSha256)
        assertTrue(parsed.hasWalletDetails)
    }

    @Test
    fun `the recorded version is a version number, not a build label`() {
        assertEquals("0.28.2", BackupManifest.normalizeVersion("0.28.2-fullmode"))
        assertEquals("0.1", BackupManifest.normalizeVersion("0.1"))
        assertEquals("1.0.0", BackupManifest.normalizeVersion(" 1.0.0 "))

        runCatching { BackupManifest.normalizeVersion("fullmode") }
            .onSuccess { error("A non-version was accepted as an app version") }
        runCatching { BackupManifest.normalizeVersion("1") }
            .onSuccess { error("A bare major version was accepted") }
    }

    @Test
    fun `a bare checksum from an older release still parses`() {
        val parsed = BackupManifest.parse(sha)

        assertNull(parsed.appVersion)
        assertNull(parsed.walletName)
        assertNull(parsed.creationHeight)
        assertEquals(sha, parsed.walletSha256)
        assertTrue(!parsed.hasWalletDetails)
    }

    @Test
    fun `a malformed manifest is refused rather than guessed at`() {
        val broken = listOf(
            "app_version=0.28.2\nwallet_name=stohn-wallet\nsha256=$sha",
            "app_version=0.28.2\nwallet_name=stohn-wallet\ncreation_height=abc\nsha256=$sha",
            "app_version=0.28.2\nwallet_name=stohn-wallet\ncreation_height=-4\nsha256=$sha",
            "app_version=0.28.2\nwallet_name=\ncreation_height=1\nsha256=$sha",
            "app_version=not-a-version\nwallet_name=w\ncreation_height=1\nsha256=$sha",
            "app_version=0.28.2\nwallet_name=w\ncreation_height=1\nsha256=zz",
            "app_version=0.28.2\nwallet_name=w\ncreation_height=1\nsha256=$sha\nfuture_field=1",
            "app_version=0.28.2\napp_version=0.29.0\nwallet_name=w\ncreation_height=1\nsha256=$sha",
            "app_version=0.28.2",
            "not a manifest at all"
        )
        broken.forEach { text ->
            runCatching { BackupManifest.parse(text) }
                .onSuccess { error("A malformed manifest was accepted: ${text.take(40)}") }
        }
    }

    @Test
    fun `matching this device produces no warnings`() {
        val notes = BackupManifest.notes(
            manifest = manifest(),
            currentAppVersion = "0.28.2",
            chainHeight = 500000L,
            activeWalletName = "stohn-wallet"
        )

        assertEquals(emptyList<String>(), notes)
    }

    @Test
    fun `differences worth telling the user about are collected`() {
        val notes = BackupManifest.notes(
            manifest = manifest(appVersion = "0.27.0", walletName = "old-wallet", creationHeight = 700000L),
            currentAppVersion = "0.28.2-fullmode",
            chainHeight = 500000L,
            activeWalletName = "stohn-wallet"
        )

        assertEquals(3, notes.size)
        assertTrue(notes.any { it.contains("0.27.0") && it.contains("0.28.2") })
        assertTrue(notes.any { it.contains("old-wallet") && it.contains("stohn-wallet") })
        assertTrue(notes.any { it.contains("700000") && it.contains("500000") })
    }

    @Test
    fun `a newer backup than this install is called out as newer`() {
        val notes = BackupManifest.notes(
            manifest = manifest(appVersion = "0.30.0"),
            currentAppVersion = "0.28.2",
            chainHeight = null,
            activeWalletName = null
        )

        assertEquals(1, notes.size)
        assertTrue(notes.single().contains("newer"))
    }

    @Test
    fun `an older backup says so even when nothing else differs`() {
        val notes = BackupManifest.notes(
            manifest = manifest(appVersion = "0.27.0"),
            currentAppVersion = "0.28.2",
            chainHeight = null,
            activeWalletName = null
        )

        assertEquals(1, notes.size)
        assertTrue(notes.single().contains("0.27.0"))
    }

    @Test
    fun `unknown context values are skipped instead of guessed`() {
        val notes = BackupManifest.notes(
            manifest = manifest(),
            currentAppVersion = null,
            chainHeight = null,
            activeWalletName = null
        )

        assertEquals(emptyList<String>(), notes)
    }

    @Test
    fun `a pre-manifest backup is described as such`() {
        val legacy = BackupManifest(appVersion = null, walletName = null, creationHeight = null, walletSha256 = sha)

        val notes = BackupManifest.notes(legacy, "0.28.2", 100L, "stohn-wallet")

        assertEquals(1, notes.size)
        assertTrue(notes.single().contains("before"))
    }

    @Test
    fun `rendering refuses details a manifest cannot carry`() {
        runCatching { BackupManifest.render("0.28.2", "", 1L, sha) }
            .onSuccess { error("A blank wallet name was accepted") }
        runCatching { BackupManifest.render("0.28.2", "has=equals", 1L, sha) }
            .onSuccess { error("A wallet name that breaks the format was accepted") }
        runCatching { BackupManifest.render("0.28.2", "w", -1L, sha) }
            .onSuccess { error("A negative creation height was accepted") }
        runCatching { BackupManifest.render("0.28.2", "w", 1L, "not-a-hash") }
            .onSuccess { error("A non-SHA-256 checksum was accepted") }
    }

    @Test
    fun `a blank line inside a manifest is ignored`() {
        // Manifests are written by hand sometimes, and a trailing blank line before the checksum is
        // not a different manifest.
        val text = "app_version=0.28.2\nwallet_name=stohn-wallet\n\ncreation_height=7\nsha256=$sha"
        val manifest = BackupManifest.parse(text)

        assertEquals("stohn-wallet", manifest.walletName)
        assertEquals(7L, manifest.creationHeight)
        assertEquals(sha, manifest.walletSha256)
    }

    @Test
    fun `a wallet name that cannot be shown is refused`() {
        for (name in listOf("x".repeat(65), "tab\there", "bell\u0007")) {
            val failure = runCatching {
                BackupManifest.parse("app_version=0.28.2\nwallet_name=$name\ncreation_height=1\nsha256=$sha")
            }.exceptionOrNull()

            assertTrue(
                "'$name' was accepted as a wallet name",
                failure is IllegalArgumentException && failure.message.orEmpty().contains("wallet name is not usable")
            )
        }
    }

    @Test
    fun `a creation height that is not a number is refused with that reason`() {
        val failure = runCatching {
            BackupManifest.parse("app_version=0.28.2\nwallet_name=w\ncreation_height=12x\nsha256=$sha")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("creation height is not a number"))
    }

    @Test
    fun `a creation height too large for a block number is refused`() {
        // Digits only, so it passes the number check, but no chain will ever be this tall.
        val failure = runCatching {
            BackupManifest.parse("app_version=0.28.2\nwallet_name=w\ncreation_height=99999999999999999999\nsha256=$sha")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("creation height is out of range"))
    }

    @Test
    fun `a current version that is not a version number only costs the version note`() {
        // The note is informational: an unparseable install version must not stop a restore or be
        // guessed at.
        val notes = BackupManifest.notes(
            manifest = manifest(appVersion = "0.29.0"),
            currentAppVersion = "dev-build",
            chainHeight = 123456L,
            activeWalletName = "stohn-wallet"
        )

        assertEquals(emptyList<String>(), notes)
    }
}
