package org.stohncoin.wallet.wallet

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupCryptoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun encryptedBackupRoundTrips() {
        val plaintext = temporaryFolder.newFile("wallet.snapshot").apply {
            writeBytes("wallet backup fixture".toByteArray())
        }
        val encrypted = File(temporaryFolder.root, "wallet.backup")
        val restored = File(temporaryFolder.root, "restored.snapshot")

        BackupCrypto.encrypt(plaintext, encrypted, "correct horse battery staple".toCharArray())
        BackupCrypto.decrypt(encrypted, restored, "correct horse battery staple".toCharArray())

        assertArrayEquals(plaintext.readBytes(), restored.readBytes())
    }

    @Test
    fun authenticationFailureDoesNotInstallPlaintext() {
        val plaintext = temporaryFolder.newFile("wallet.snapshot").apply {
            writeBytes(ByteArray(4096) { it.toByte() })
        }
        val encrypted = File(temporaryFolder.root, "wallet.backup")
        val restored = File(temporaryFolder.root, "restored.snapshot")
        BackupCrypto.encrypt(plaintext, encrypted, "right password".toCharArray())

        runCatching {
            BackupCrypto.decrypt(encrypted, restored, "wrong password".toCharArray())
        }.onSuccess { error("Wrong backup password unexpectedly decrypted the wallet") }

        assertFalse("Failed decryption must not leave plaintext at the requested path", restored.exists())
    }
}
