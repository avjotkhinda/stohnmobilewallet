package org.stohncoin.wallet.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user is told after a test-restore.
 *
 * [BackupVerification] is handed straight to a card and a toast, so the two things it derives -
 * whether Core actually opened the wallet, and the one-line summary - are user-visible claims. A
 * backup made before the manifest carried wallet details has none of them, and the summary must
 * say nothing rather than invent a name for an unknown wallet.
 */
class BackupVerificationTest {

    private fun verification(
        walletFormat: String? = "sqlite",
        walletName: String? = null,
        creationHeight: Long? = null,
        appVersion: String? = null,
        notes: List<String> = emptyList()
    ) = BackupVerification(
        walletBytes = 4096L,
        walletSha256 = "a".repeat(64),
        labelCount = 3,
        walletFormat = walletFormat,
        walletEncrypted = true,
        walletName = walletName,
        creationHeight = creationHeight,
        appVersion = appVersion,
        notes = notes
    )

    @Test
    fun `a report is core-checked only when Core read the wallet`() {
        assertTrue(verification(walletFormat = "sqlite").coreChecked)
        assertTrue(verification(walletFormat = "bdb").coreChecked)
        // No runtime was up, so only the container structure was proven; saying otherwise would
        // tell a user their backup is usable when nobody has opened it.
        assertFalse(verification(walletFormat = null).coreChecked)
    }

    @Test
    fun `the summary names the wallet, its height and the build that made it`() {
        assertEquals(
            "Wallet 'stohn-wallet' · created at height 123456 · app 0.28.2",
            verification(walletName = "stohn-wallet", creationHeight = 123456L, appVersion = "0.28.2").summary
        )
    }

    @Test
    fun `the summary omits an app version it does not have`() {
        assertEquals(
            "Wallet 'stohn-wallet' · created at height 123456",
            verification(walletName = "stohn-wallet", creationHeight = 123456L).summary
        )
    }

    @Test
    fun `a backup without wallet details has no summary to give`() {
        assertNull(verification().summary)
        assertNull(verification(walletName = "stohn-wallet").summary)
        assertNull(verification(creationHeight = 123456L).summary)
    }

    @Test
    fun `notes are carried through as given`() {
        val notes = listOf("Backup was made by app 0.28.2; this device runs 0.28.3")

        assertEquals(notes, verification(notes = notes).notes)
        assertTrue(verification().notes.isEmpty())
    }

    @Test
    fun `a backup from before the manifest carried details reports only what it measured`() {
        // The five-argument form is what a container written by an older build produces: no name,
        // no height, no version. They must come back absent rather than as empty strings, which a
        // card would happily render as a wallet called "".
        val legacy = BackupVerification(
            walletBytes = 4096L,
            walletSha256 = "a".repeat(64),
            labelCount = 0,
            walletFormat = "sqlite",
            walletEncrypted = true
        )

        assertNull(legacy.walletName)
        assertNull(legacy.creationHeight)
        assertNull(legacy.appVersion)
        assertTrue(legacy.notes.isEmpty())
        assertNull(legacy.summary)
        assertTrue(legacy.coreChecked)
    }

    @Test
    fun `the report keeps every measured value`() {
        val report = verification(walletName = "stohn-wallet", creationHeight = 7L, appVersion = "0.28.2")

        assertEquals(4096L, report.walletBytes)
        assertEquals("a".repeat(64), report.walletSha256)
        assertEquals(3, report.labelCount)
        assertEquals(true, report.walletEncrypted)
        assertEquals("stohn-wallet", report.walletName)
        assertEquals(7L, report.creationHeight)
        assertEquals("0.28.2", report.appVersion)
    }
}
