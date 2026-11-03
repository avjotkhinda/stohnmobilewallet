package org.stohncoin.wallet.runtime

import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SignedRuntimeManifestTest {
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

    private fun publicKeyOf(keys: KeyPair): String =
        Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun sign(payload: ByteArray, keys: KeyPair): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.private)
        signer.update(payload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** A complete document: the canonical payload plus its signature line. */
    private fun document(pins: RuntimePins, keys: KeyPair = keyPair): ByteArray {
        val payload = SignedRuntimeManifest.canonical(pins)
        return (String(payload, Charsets.US_ASCII) + "signature " + sign(payload, keys) + "\n")
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
        val dir = Files.createTempDirectory("stohn-manifest").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun refusal(block: () -> Any): String {
        try {
            block()
        } catch (rejected: IllegalStateException) {
            return rejected.message ?: ""
        }
        fail("Expected the manifest to be refused")
        throw AssertionError("unreachable")
    }

    @Test fun aSignedDocumentRoundTripsToThePinsItCarries() {
        val staged = pins()
        val verified = SignedRuntimeManifest.verifyDocument(document(staged), publicKeyOf(keyPair))
        assertEquals(staged, verified)
    }

    @Test fun theCanonicalFormIsStableAsciiWithBlanksSpelledOut() {
        val payload = String(SignedRuntimeManifest.canonical(pins(version = 7)), Charsets.US_ASCII)
        assertTrue(payload.startsWith(SignedRuntimeManifest.HEADER + "\n"))
        assertTrue(payload.endsWith("native-wallet-tool-sha256 -\n"))
        assertTrue(payload.contains("version 7\n"))
        assertTrue("canonical form must be LF-only", !payload.contains("\r"))
    }

    @Test fun aDocumentEditedAfterSigningIsRejectedAsAForgery() {
        val document = document(pins()).toString(Charsets.US_ASCII).replace("3.3", "3.4").toByteArray()
        assertEquals(
            "Runtime manifest signature check failed",
            refusal { SignedRuntimeManifest.verifyDocument(document, publicKeyOf(keyPair)) }
        )
    }

    @Test fun aSignatureFromAnotherKeyIsRejected() {
        val document = document(pins(), otherKeyPair)
        assertEquals(
            "Runtime manifest signature check failed",
            refusal { SignedRuntimeManifest.verifyDocument(document, publicKeyOf(keyPair)) }
        )
    }

    @Test fun aMissingOrUnusableSignatureIsRejected() {
        val payload = SignedRuntimeManifest.canonical(pins())
        val unsigned = String(payload, Charsets.US_ASCII) + "signature not-base64!!\n"
        assertEquals(
            "Runtime manifest signature is not valid base64",
            refusal {
                SignedRuntimeManifest.verifyDocument(unsigned.toByteArray(), publicKeyOf(keyPair))
            }
        )

        val noSignature = String(payload, Charsets.US_ASCII) + "core-version 3.3\n"
        refusal {
            SignedRuntimeManifest.verifyDocument(noSignature.toByteArray(), publicKeyOf(keyPair))
        }
    }

    @Test fun withoutAConfiguredReleaseKeyNoDocumentIsAccepted() {
        assertEquals(
            "Runtime manifest release key is not configured",
            refusal { SignedRuntimeManifest.verifyDocument(document(pins()), "") }
        )
        assertEquals(
            "Runtime manifest release key is not configured",
            refusal { SignedRuntimeManifest.verifyDocument(document(pins()), "   \n  ") }
        )
    }

    @Test fun aKeyThatIsNotECIsRejected() {
        val notAKey = Base64.getEncoder().encodeToString("definitely not a public key".toByteArray())
        assertEquals(
            "Runtime manifest release key is not an EC public key",
            refusal { SignedRuntimeManifest.verifyDocument(document(pins()), notAKey) }
        )
    }

    @Test fun aProperlySignedButMalformedPayloadIsStillRefused() {
        val payload = String(SignedRuntimeManifest.canonical(pins()), Charsets.US_ASCII)
            .replace("https://example.invalid", "http://example.invalid")
        val signed = payload + "signature " + sign(payload.toByteArray(), keyPair) + "\n"
        assertEquals(
            "Runtime manifest core-url must be a plain HTTPS URL",
            refusal { SignedRuntimeManifest.verifyDocument(signed.toByteArray(), publicKeyOf(keyPair)) }
        )
    }

    @Test fun fieldsTheFormatDoesNotNameAreRefusedEvenWhenSigned() {
        val payload = String(SignedRuntimeManifest.canonical(pins()), Charsets.US_ASCII)
            .replace("version ", "mirror https://evil.invalid\nversion ")
        val signed = payload + "signature " + sign(payload.toByteArray(), keyPair) + "\n"
        assertEquals(
            "Unknown runtime manifest field 'mirror'",
            refusal { SignedRuntimeManifest.verifyDocument(signed.toByteArray(), publicKeyOf(keyPair)) }
        )
    }

    @Test fun aSignedDocumentMissingAFieldIsRefused() {
        val payload = String(SignedRuntimeManifest.canonical(pins()), Charsets.US_ASCII)
            .replace(Regex("debian-url .*\\n"), "")
        val signed = payload + "signature " + sign(payload.toByteArray(), keyPair) + "\n"
        assertEquals(
            "Runtime manifest is missing 'debian-url'",
            refusal { SignedRuntimeManifest.verifyDocument(signed.toByteArray(), publicKeyOf(keyPair)) }
        )
    }

    @Test fun crLineEndingsAreRefused() {
        val payload = SignedRuntimeManifest.canonical(pins()).toString(Charsets.US_ASCII).replace("\n", "\r\n")
        val signed = payload + "signature " + sign(payload.toByteArray(), keyPair) + "\n"
        assertEquals(
            "Runtime manifest must be printable ASCII with LF line endings",
            refusal { SignedRuntimeManifest.verifyDocument(signed.toByteArray(), publicKeyOf(keyPair)) }
        )
    }

    @Test fun withoutAStagedDocumentTheBuiltInPinsAreUsed() {
        assertEquals(SignedRuntimeManifest.builtin(), SignedRuntimeManifest.effective(updateDir()))
    }

    @Test fun aVerifiedNewerManifestReplacesThePins() {
        val dir = updateDir()
        val staged = pins(version = SignedRuntimeManifest.builtin().version + 1)

        val installed = SignedRuntimeManifest.stage(dir, document(staged), publicKeyOf(keyPair))
        assertEquals(staged, installed)

        val effective = SignedRuntimeManifest.effective(dir, publicKeyOf(keyPair))
        assertEquals(staged, effective)
    }

    @Test fun stagingRefusesADocumentThatIsNotNewerThanTheBuiltInPins() {
        val dir = updateDir()
        val sameVersion = SignedRuntimeManifest.builtin().version
        assertEquals(
            "Runtime manifest version $sameVersion is not newer than the built-in version " +
                "$sameVersion; downgrades are refused",
            refusal { SignedRuntimeManifest.stage(dir, document(pins(sameVersion)), publicKeyOf(keyPair)) }
        )
        assertTrue(!File(dir, SignedRuntimeManifest.UPDATE_FILE).exists())
    }

    @Test fun aDocumentThatDoesNotVerifyIsFatalRatherThanSilentlyIgnored() {
        val dir = updateDir()
        val staged = document(pins())
        File(dir, SignedRuntimeManifest.UPDATE_FILE).writeBytes(staged)

        // Signed by a key this app does not know: the pins must not quietly fall back to built-in.
        val foreign = document(pins(), otherKeyPair)
        File(dir, SignedRuntimeManifest.UPDATE_FILE).writeBytes(foreign)
        assertEquals(
            "Runtime manifest signature check failed",
            refusal { SignedRuntimeManifest.effective(dir, publicKeyOf(keyPair)) }
        )

        // Half-written or truncated is fatal for the same reason.
        File(dir, SignedRuntimeManifest.UPDATE_FILE).writeBytes(foreign.copyOfRange(0, 40))
        refusal { SignedRuntimeManifest.effective(dir, publicKeyOf(keyPair)) }
    }

    @Test fun aVerifiedButStaleDocumentIsNeverApplied() {
        val dir = updateDir()
        val stale = pins(version = SignedRuntimeManifest.builtin().version)
        File(dir, SignedRuntimeManifest.UPDATE_FILE).writeBytes(document(stale))

        assertEquals(SignedRuntimeManifest.builtin(), SignedRuntimeManifest.effective(dir, publicKeyOf(keyPair)))
    }

    @Test fun anOversizedFileIsRefusedWithoutBeingRead() {
        val dir = updateDir()
        val file = File(dir, SignedRuntimeManifest.UPDATE_FILE)
        file.writeBytes(ByteArray(SignedRuntimeManifest.MAX_DOCUMENT_BYTES + 1) { 'x'.code.toByte() })
        assertEquals("Runtime manifest update is missing or oversized", refusal { SignedRuntimeManifest.effective(dir) })
    }

    @Test fun withNoReleaseKeyPublishedNothingCanBeStaged() {
        val dir = updateDir()
        assertEquals(
            "Runtime manifest release key is not configured",
            refusal { SignedRuntimeManifest.stage(dir, document(pins())) }
        )
        assertTrue(!File(dir, SignedRuntimeManifest.UPDATE_FILE).exists())
    }

    @Test fun builtinPinsAreExactlyWhatThisApkWasCompiledWith() {
        assertEquals(SignedRuntimeManifest.builtin(), RuntimePins(
            version = RuntimeManifest.BUILTIN_MANIFEST_VERSION,
            coreVersion = RuntimeManifest.CORE_VERSION,
            coreUrl = RuntimeManifest.CORE_URL,
            coreSha256 = RuntimeManifest.CORE_SHA256,
            debianVersion = RuntimeManifest.DEBIAN_VERSION,
            debianUrl = RuntimeManifest.DEBIAN_URL,
            debianSha256 = RuntimeManifest.DEBIAN_SHA256,
            nativeCoreSha256 = RuntimeManifest.NATIVE_CORE_SHA256,
            nativeWalletToolSha256 = RuntimeManifest.NATIVE_WALLET_TOOL_SHA256
        ))
        assertEquals("", RuntimeManifest.RELEASE_PUBLIC_KEY)
    }
}