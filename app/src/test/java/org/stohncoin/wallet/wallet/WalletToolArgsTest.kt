package org.stohncoin.wallet.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.stohncoin.wallet.runtime.WalletToolArgs

/**
 * The shared wallet-tool argument builder is the single place both backends apply the plaintext
 * key-export guard, so these tests are what keeps the guard from being bypassed by a launcher that
 * assembles its own command line.
 */
class WalletToolArgsTest {
    private fun rejection(block: () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return error
        }
        fail("Expected the key export guard to reject this call")
        throw AssertionError("unreachable")
    }

    @Test fun allowedCommandBecomesTheFinalArgument() {
        val args = WalletToolArgs.build(dataDir = "/workspace", walletName = "wallet.dat", command = "info")
        assertEquals(listOf("-datadir=/workspace", "-wallet=wallet.dat", "info"), args)
    }

    @Test fun everyPlaintextKeyExportCommandIsRefused() {
        for (command in listOf("dumpwallet", "dumpprivkey", "dumphdinfo", "dumpmasterprivkey", "dumpmasterkey")) {
            rejection {
                WalletToolArgs.build(dataDir = "/workspace", walletName = "wallet.dat", command = command)
            }
        }
    }

    @Test fun dumpFileIsPassedThroughTheWayCoreWillSeeIt() {
        val args = WalletToolArgs.build(
            dataDir = "/workspace",
            walletName = "wallet.dat",
            command = "dump",
            dumpFile = "/workspace/record-dump.dat"
        )
        assertTrue("-dumpfile=/workspace/record-dump.dat" in args)
        assertEquals("dump", args.last())
    }

    @Test fun formatIsOnlyAcceptedForCreatefromdump() {
        val args = WalletToolArgs.build(
            dataDir = "/workspace",
            walletName = "wallet.dat",
            command = "createfromdump",
            walletFormat = "sqlite"
        )
        assertTrue("-format=sqlite" in args)
        assertEquals("createfromdump", args.last())

        rejection {
            WalletToolArgs.build(
                dataDir = "/workspace",
                walletName = "wallet.dat",
                command = "dump",
                walletFormat = "sqlite"
            )
        }
    }

    @Test fun unknownWalletFormatIsRefused() {
        rejection {
            WalletToolArgs.build(
                dataDir = "/workspace",
                walletName = "wallet.dat",
                command = "createfromdump",
                walletFormat = "leveldb"
            )
        }
    }
}
