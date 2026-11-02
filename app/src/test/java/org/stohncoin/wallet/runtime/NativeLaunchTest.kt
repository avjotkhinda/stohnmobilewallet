package org.stohncoin.wallet.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeLaunchTest {
    private fun tempArtifact(content: String): File {
        val file = Files.createTempFile("stohn-native", ".so").toFile()
        file.deleteOnExit()
        file.writeBytes(content.toByteArray())
        return file
    }

    private fun decision(
        core: File = File("/nonexistent/libstohncoind.so"),
        walletTool: File = File("/nonexistent/libstohncoin-wallet.so"),
        corePin: String = "",
        walletToolPin: String = ""
    ): NativeLaunch.Decision = NativeLaunch.decide(core, walletTool, corePin, walletToolPin)

    private fun refusalThrows(decision: () -> NativeLaunch.Ready): IllegalStateException {
        try {
            decision()
        } catch (error: IllegalStateException) {
            return error
        }
        fail("Expected the native backend to refuse to run")
        throw AssertionError("unreachable")
    }

    @Test fun withoutPublishedPinsTheGlibcRuntimeStaysInUse() {
        assertEquals(NativeLaunch.NoNativeBuild, decision())
        assertEquals("no native Stohn Core is pinned in this build", decision().refusal)
    }

    @Test fun pinsArePublishedAsAPair() {
        val half = decision(corePin = "0".repeat(64))
        assertTrue(half is NativeLaunch.InvalidPin)
        assertEquals("native pins are published together, not one at a time", half.refusal)
    }

    @Test fun malformedPinsAreRefused() {
        val badCore = decision(corePin = "not-a-digest", walletToolPin = "1".repeat(64))
        assertEquals("native Stohn Core pin is not a SHA-256 digest", badCore.refusal)
        val badTool = decision(corePin = "1".repeat(64), walletToolPin = "ABCDEF".repeat(11).take(64))
        assertEquals("native wallet tool pin is not a SHA-256 digest", badTool.refusal)
    }

    @Test fun pinWithoutItsBinaryIsRefused() {
        val decision = decision(corePin = "1".repeat(64), walletToolPin = "2".repeat(64))
        assertEquals(NativeLaunch.MissingBinary, decision)
        assertEquals("this build pins a native Stohn Core but does not ship it", decision.refusal)
    }

    @Test fun binaryThatDoesNotMatchItsPinIsRefused() {
        val core = tempArtifact("real native core")
        val walletTool = tempArtifact("real native wallet tool")
        val decision = NativeLaunch.decide(
            core = core,
            walletTool = walletTool,
            corePin = artifactSha256(tempArtifact("something else")),
            walletToolPin = artifactSha256(walletTool)
        )
        assertTrue(decision is NativeLaunch.Tampered)
        decision as NativeLaunch.Tampered
        assertEquals(NativeLaunch.CORE_LIB, decision.artifact)
        assertEquals(artifactSha256(core), decision.found)
        assertTrue(decision.refusal.startsWith("${NativeLaunch.CORE_LIB} failed its integrity check"))
    }

    @Test fun walletToolThatDoesNotMatchItsPinIsRefused() {
        val core = tempArtifact("real native core")
        val walletTool = tempArtifact("real native wallet tool")
        val decision = NativeLaunch.decide(
            core = core,
            walletTool = walletTool,
            corePin = artifactSha256(core),
            walletToolPin = "3".repeat(64)
        )
        assertTrue(decision is NativeLaunch.Tampered)
        assertEquals(NativeLaunch.WALLET_TOOL_LIB, (decision as NativeLaunch.Tampered).artifact)
    }

    @Test fun bothBinariesMatchingTheirPinsMeansReady() {
        val core = tempArtifact("real native core")
        val walletTool = tempArtifact("real native wallet tool")
        val ready = NativeLaunch.decide(
            core = core,
            walletTool = walletTool,
            corePin = artifactSha256(core),
            walletToolPin = artifactSha256(walletTool)
        )
        assertTrue(ready is NativeLaunch.Ready)
        assertEquals(core, (ready as NativeLaunch.Ready).core)
        assertEquals("", ready.refusal)
    }

    @Test fun requireReadyThrowsTheRefusalInsteadOfRunningUnverified() {
        val error = refusalThrows {
            NativeLaunch.requireReady(
                File("/nonexistent/libstohncoind.so"),
                File("/nonexistent/libstohncoin-wallet.so"),
                "4".repeat(64),
                "5".repeat(64)
            )
        }
        assertEquals(NativeLaunch.MissingBinary.refusal, error.message)
    }

    @Test fun onlyACompleteLowercaseDigestCountsAsAPin() {
        assertTrue(NativeLaunch.isPinned("a".repeat(64)))
        assertFalse(NativeLaunch.isPinned("A".repeat(64)))
        assertFalse(NativeLaunch.isPinned("a".repeat(63)))
        assertFalse(NativeLaunch.isPinned(""))
        assertFalse(NativeLaunch.isPinned("a".repeat(64) + "a"))
    }

    @Test fun nativeCommandLineCarriesTheHardenedFlagsAndNoChrootBindings() {
        val args = NativeLaunch.coreArguments(
            conf = "/data/user/0/app/files/fullmode/stohn-data/stohn.conf",
            dataDir = "/data/user/0/app/files/fullmode/stohn-data",
            rpcPort = 42_123,
            pruneMiB = 550
        )
        assertEquals(
            listOf(
                "-conf=/data/user/0/app/files/fullmode/stohn-data/stohn.conf",
                "-datadir=/data/user/0/app/files/fullmode/stohn-data",
                "-rpcport=42123",
                "-daemon=0",
                "-prune=550"
            ),
            args
        )
        assertFalse("PRoot path bindings have no business in a native launch",
            args.any { it == "-r" || it == "-b" || it.startsWith("-b=") })
    }

    @Test fun artifactSha256MatchesTheKnownVector() {
        val file = tempArtifact("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            artifactSha256(file)
        )
    }
}