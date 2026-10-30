package org.stohncoin.wallet.wallet

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Restore cross-check for `scripts/verify-wallet-migration.sh`: the wallet handed to the Core tool
 * after a restore must be the exact wallet that was backed up, or the migration that script proves
 * on a real `wallet.dat` would be run over different bytes.
 */
class BackupRestoreRoundTripTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val limit = 8L * 1024 * 1024

    private fun snapshot(): File = temporaryFolder.newFile("wallet.dat").apply {
        writeBytes(ByteArray(9_721) { (it * 31 % 251).toByte() })
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `restored wallet matches the backed-up snapshot byte for byte`() {
        val source = snapshot()
        val archive = File(temporaryFolder.root, "wallet.zip")
        val container = File(temporaryFolder.root, "wallet.backup")
        val decrypted = File(temporaryFolder.root, "wallet.zip.decrypted")
        val staged = File(temporaryFolder.root, "wallet.dat.staged")
        val password = "correct horse battery staple".toCharArray()

        BackupContainer.packageSnapshot(source, archive, limit)
        BackupCrypto.encrypt(archive, container, password)
        BackupCrypto.decrypt(container, decrypted, password)
        val contents = BackupContainer.restore(decrypted, staged, limit)

        assertArrayEquals(source.readBytes(), staged.readBytes())
        assertEquals(sha256(source), contents.walletSha256)
        assertFalse(
            "Backup must not carry the wallet in the clear",
            container.readBytes().contentEquals(source.readBytes())
        )
    }

    @Test
    fun `packaged archive restores through the reader`() {
        val source = snapshot()
        val archive = File(temporaryFolder.root, "wallet.zip")
        val staged = File(temporaryFolder.root, "wallet.dat.staged")

        BackupContainer.packageSnapshot(source, archive, limit)
        val contents = BackupContainer.restore(archive, staged, limit)

        assertEquals(source.length(), staged.length())
        assertEquals(contents.walletSha256, sha256(staged))
    }
}
