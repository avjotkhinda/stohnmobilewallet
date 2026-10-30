package org.stohncoin.wallet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class KeyExportGuardTest {
    private fun rejection(block: () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return error
        }
        fail("Expected the key export guard to reject this call")
        throw AssertionError("unreachable")
    }

    @Test fun allowsWalletToolCommandsTheAppUses() {
        for (command in listOf("info", "dump", "createfromdump")) {
            assertEquals(command, KeyExportGuard.requireWalletToolCommand(command))
        }
    }

    @Test fun trimsWalletToolCommand() {
        assertEquals("dump", KeyExportGuard.requireWalletToolCommand("  dump  "))
    }

    @Test fun rejectsDumpwalletSubcommand() {
        val error = rejection { KeyExportGuard.requireWalletToolCommand("dumpwallet") }
        assertEquals(
            "Wallet tool command 'dumpwallet' is not permitted: the app never exports plaintext keys",
            error.message
        )
    }

    @Test fun rejectsTrailingWhitespaceDumpwallet() {
        rejection { KeyExportGuard.requireWalletToolCommand(" dumpwallet ") }
    }

    @Test fun rejectsEveryOtherWalletToolSubcommand() {
        for (command in listOf("dumpprivkey", "dumpwalletfile", "exportwallet", "importprivkey", "")) {
            rejection { KeyExportGuard.requireWalletToolCommand(command) }
        }
    }

    @Test fun allowlistDoesNotContainPlaintextExport() {
        assertFalse("dumpwallet" in KeyExportGuard.ALLOWED_WALLET_TOOL_COMMANDS)
        assertFalse("dumpprivkey" in KeyExportGuard.ALLOWED_WALLET_TOOL_COMMANDS)
    }

    @Test fun allowsRpcMethodsTheAppUses() {
        for (method in listOf(
            "getblockchaininfo",
            "getnetworkinfo",
            "estimatesmartfee",
            "getnewaddress",
            "listtransactions",
            "sendtoaddress",
            "signrawtransactionwithwallet",
            "backupwallet",
            "walletlock"
        )) {
            assertEquals(method, KeyExportGuard.requireRpcMethod(method))
        }
    }

    @Test fun rejectsPlaintextKeyRpcMethods() {
        for (method in KeyExportGuard.FORBIDDEN_RPC_METHODS) {
            rejection { KeyExportGuard.requireRpcMethod(method) }
        }
        rejection { KeyExportGuard.requireRpcMethod("DumpWallet") }
        rejection { KeyExportGuard.requireRpcMethod(" dumpprivkey ") }
    }

    @Test fun rejectionNamesTheRpcMethod() {
        val error = rejection { KeyExportGuard.requireRpcMethod("dumphdinfo") }
        assertEquals(
            "RPC method 'dumphdinfo' is not permitted: the app never exports plaintext keys",
            error.message
        )
    }

    @Test fun trimsAllowedRpcMethod() {
        assertEquals("getblockchaininfo", KeyExportGuard.requireRpcMethod(" getblockchaininfo "))
    }
}
