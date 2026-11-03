package org.stohncoin.wallet.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManifestTransportTest {
    private val endpoint = "https://releases.example.invalid/stohn/runtime/manifest.txt"

    private val keyPair: KeyPair by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        generator.generateKeyPair()
    }

    private val otherKeyPair: KeyPair by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        generator.generateKeyPair()
    }

    /** Records every URL it was asked for, so tests can prove what was and was not contacted. */
    private class Scripted(
        private val respond: (String) -> RuntimeManifestTransport.Response
    ) : RuntimeManifestTransport.Source {
        val requests = mutableListOf<String>()

        override fun fetch(url: String): RuntimeManifestTransport.Response {
            requests += url
            return respond(url)
        }
    }

    private fun ok(body: ByteArray) = RuntimeManifestTransport.Response(200, null, body)

    private fun redirect(to: String) = RuntimeManifestTransport.Response(302, to, ByteArray(0))

    private fun status(code: Int) = RuntimeManifestTransport.Response(code, null, ByteArray(0))

    private fun publicKeyOf(keys: KeyPair): String =
        Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun document(pins: RuntimePins, keys: KeyPair = keyPair): ByteArray {
        val payload = SignedRuntimeManifest.canonical(pins)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.private)
        signer.update(payload)
        val signature = Base64.getEncoder().encodeToString(signer.sign())
        return (String(payload, Charsets.US_ASCII) + "signature " + signature + "\n")
            .toByteArray(Charsets.US_ASCII)
    }

    private fun pins(version: Int = SignedRuntimeManifest.builtin().version + 1) = RuntimePins(
        version = version,
        coreVersion = "3.3",
        coreUrl = "https://example.invalid/stohn-3.3-aarch64-linux-gnu.tar.gz",
        coreSha256 = "a".repeat(64),
        debianVersion = "bookworm-arm64-default-20270101_00:00",
        debianUrl = "https://example.invalid/rootfs.tar.xz",
        debianSha256 = "b".repeat(64),
        nativeCoreSha256 = "",
        nativeWalletToolSha256 = ""
    )

    private fun updateDir(): File {
        val dir = Files.createTempDirectory("stohn-transport").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun staged(dir: File): File = File(dir, SignedRuntimeManifest.UPDATE_FILE)

    private fun refresh(dir: File, source: RuntimeManifestTransport.Source, url: String = endpoint, key: String = publicKeyOf(keyPair)) =
        RuntimeManifestTransport.refresh(dir, key, source, url)

    @Test fun aBlankEndpointMakesNoRequestAtAll() {
        val source = Scripted { throw AssertionError("no request may be made without an endpoint") }

        val outcome = refresh(updateDir(), source, url = "   ")

        assertTrue(outcome is RuntimeManifestTransport.Outcome.Declined)
        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("no runtime manifest endpoint"))
        assertTrue(source.requests.isEmpty())
    }

    @Test fun aPlainHttpEndpointIsRefusedBeforeAnyRequest() {
        val source = Scripted { throw AssertionError("a refused endpoint must never be contacted") }

        val outcome = refresh(updateDir(), source, url = "http://releases.example.invalid/manifest.txt")

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("HTTPS"))
        assertTrue(source.requests.isEmpty())
    }

    @Test fun endpointsWithCredentialsOrNoHostAreRefused() {
        assertFalse(RuntimeManifestTransport.isAllowedEndpoint("https://user@releases.example.invalid/m.txt"))
        assertFalse(RuntimeManifestTransport.isAllowedEndpoint("https://releases.example.invalid/m.txt#x"))
        assertFalse(RuntimeManifestTransport.isAllowedEndpoint("https://"))
        assertFalse(RuntimeManifestTransport.isAllowedEndpoint("ftp://releases.example.invalid/m.txt"))
        assertFalse(RuntimeManifestTransport.isAllowedEndpoint("https://releases.example.invalid/a b"))
        assertTrue(RuntimeManifestTransport.isAllowedEndpoint(endpoint))
    }

    @Test fun aVerifiedNewerDocumentIsStagedAndBecomesThePins() {
        val dir = updateDir()
        val newer = pins()
        val source = Scripted { ok(document(newer)) }

        val outcome = refresh(dir, source)

        assertEquals(newer, (outcome as RuntimeManifestTransport.Outcome.Updated).pins)
        assertEquals(newer, SignedRuntimeManifest.effective(dir, publicKeyOf(keyPair)))
        assertTrue(staged(dir).isFile)
    }

    @Test fun aRedirectChainIsFollowedOnlyWhileItStaysOnHttps() {
        val dir = updateDir()
        val middle = "https://cdn.example.invalid/runtime/manifest.txt"
        val last = "https://objects.example.invalid/runtime/manifest.txt"
        val source = Scripted { url ->
            when (url) {
                endpoint -> redirect(middle)
                middle -> redirect(last)
                else -> ok(document(pins()))
            }
        }

        val outcome = refresh(dir, source)

        assertTrue(outcome is RuntimeManifestTransport.Outcome.Updated)
        assertEquals(listOf(endpoint, middle, last), source.requests)
    }

    @Test fun aRedirectToPlainHttpIsRefusedAndNothingIsStaged() {
        val dir = updateDir()
        val source = Scripted { redirect("http://objects.example.invalid/runtime/manifest.txt") }

        val outcome = refresh(dir, source)

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("left HTTPS"))
        assertEquals(1, source.requests.size)
        assertFalse(staged(dir).exists())
    }

    @Test fun aRedirectLoopIsRefusedAfterTheHopLimit() {
        val dir = updateDir()
        val source = Scripted { redirect(endpoint) }

        val outcome = refresh(dir, source)

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("too long"))
        assertEquals(RuntimeManifestTransport.MAX_REDIRECTS + 1, source.requests.size)
        assertFalse(staged(dir).exists())
    }

    @Test fun anOversizedBodyIsRefusedBeforeVerification() {
        val dir = updateDir()
        val oversized = ByteArray(SignedRuntimeManifest.MAX_DOCUMENT_BYTES + 1) { 'a'.code.toByte() }

        val outcome = refresh(dir, Scripted { ok(oversized) })

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("oversized"))
        assertFalse(staged(dir).exists())
    }

    @Test fun anEmptyBodyIsRefused() {
        val outcome = refresh(updateDir(), Scripted { ok(ByteArray(0)) })

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("empty"))
    }

    @Test fun aNonSuccessStatusIsDeclined() {
        val outcome = refresh(updateDir(), Scripted { status(404) })

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("HTTP 404"))
    }

    @Test fun aFailedDownloadLeavesTheStagedDocumentAlone() {
        val dir = updateDir()
        val source = Scripted { throw IOException("no route to host") }

        val outcome = refresh(dir, source)

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("no route to host"))
        assertFalse(staged(dir).exists())
    }

    @Test fun aDocumentEditedInFlightIsDeclinedAndNeverOverwrites() {
        val dir = updateDir()
        val good = pins()
        assertTrue(refresh(dir, Scripted { ok(document(good)) }) is RuntimeManifestTransport.Outcome.Updated)
        val stagedBytes = staged(dir).readBytes()

        // Version bumped but the signature still covers the old payload: a classic tamper.
        val tampered = String(document(pins(version = good.version + 1)), Charsets.US_ASCII)
            .replace("version ${good.version + 1}\n", "version ${good.version + 2}\n")
            .toByteArray(Charsets.US_ASCII)
        val outcome = refresh(dir, Scripted { ok(tampered) })

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("signature"))
        assertArrayEquals(stagedBytes, staged(dir).readBytes())
        assertEquals(good, SignedRuntimeManifest.effective(dir, publicKeyOf(keyPair)))
    }

    @Test fun aDocumentSignedByAnotherKeyIsDeclined() {
        val dir = updateDir()
        val outcome = refresh(dir, Scripted { ok(document(pins(), otherKeyPair)) })

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("signature"))
        assertFalse(staged(dir).exists())
    }

    @Test fun aDocumentThatIsNotNewerIsReportedAsCurrentNotApplied() {
        val dir = updateDir()
        val builtin = SignedRuntimeManifest.builtin()

        val outcome = refresh(dir, Scripted { ok(document(pins(version = builtin.version))) })

        assertEquals(builtin, (outcome as RuntimeManifestTransport.Outcome.Current).pins)
        assertFalse(staged(dir).exists())
    }

    @Test fun anUnconfiguredReleaseKeyRefusesEverythingAndWritesNothing() {
        val dir = updateDir()

        val outcome = refresh(dir, Scripted { ok(document(pins())) }, key = "")

        assertTrue((outcome as RuntimeManifestTransport.Outcome.Declined).reason.contains("not configured"))
        assertFalse(staged(dir).exists())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
