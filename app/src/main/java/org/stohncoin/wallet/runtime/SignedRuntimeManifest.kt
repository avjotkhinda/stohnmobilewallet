package org.stohncoin.wallet.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * One coherent set of runtime pins: what Stohn Core and the Debian rootfs this app may download,
 * and which native binaries it may execute.
 *
 * Only two things produce a set: [SignedRuntimeManifest.builtin], which is what this APK was
 * compiled with, and a staged manifest document verified against the project's release key.
 * Nothing else can move a pin.
 */
data class RuntimePins(
    /** Manifest version. A staged document must be strictly newer than the built-in one to apply. */
    val version: Int,
    val coreVersion: String,
    val coreUrl: String,
    val coreSha256: String,
    val debianVersion: String,
    val debianUrl: String,
    val debianSha256: String,
    /** Blank means no native build is published, exactly as [RuntimeManifest] states it. */
    val nativeCoreSha256: String,
    val nativeWalletToolSha256: String,
)

/**
 * The runtime update path: new pins, shipped together with a signature check.
 *
 * The document is one file, because two files can never be swapped into place together — a crash
 * halfway through would leave a payload and a signature from different releases, and the only safe
 * reading of that is a refusal. It is:
 *
 * ```
 * stohn-runtime-manifest/1
 * version 2
 * core-version 3.3
 * core-url https://…
 * core-sha256 <64 hex>
 * debian-version …
 * debian-url https://…
 * debian-sha256 <64 hex>
 * native-core-sha256 <64 hex or ->
 * native-wallet-tool-sha256 <64 hex or ->
 * signature <base64 ECDSA P-256 signature over every byte above>
 * ```
 *
 * Every rule here exists to keep the "no silent swaps" promise:
 *
 *  - the signature is checked **before** the payload is parsed, so a tampered document is reported
 *    as a bad signature rather than as whatever field the tampering happened to touch;
 * - a document that is present but does not verify is a **hard failure** in [effective] — the app
 *    never quietly falls back to the built-in pins, which would turn a botched or hostile update
 *    into an invisible one;
 * - a document that verifies but is not newer than the built-in version is **not applied**, so a
 *    captured old manifest cannot roll pins back to a weaker runtime;
 * - the release key is compiled in blank, so until the project publishes one, no document can be
 *    staged or applied at all, exactly like the blank native pins in [RuntimeManifest].
 *
 * Release side (the private key never enters this app or this repository):
 *
 * ```
 * openssl ecparam -name prime256v1 -genkey -out release-private.pem   # stays in release CI
 * openssl ec -in release-private.pem -pubout                          # this half is RELEASE_PUBLIC_KEY
 * printf '%s' "$payload" > manifest.payload                           # bytes from canonical()
 * openssl dgst -sha256 -sign release-private.pem -out manifest.sig manifest.payload
 * { cat manifest.payload; printf 'signature %s\n' "$(base64 -w0 manifest.sig)"; } > manifest.txt
 * ```
 *
 * The assembled document is handed to [stage], which verifies it before writing it anywhere.
 */
object SignedRuntimeManifest {
    /** Format marker: changes only when the field layout itself changes. */
    const val HEADER = "stohn-runtime-manifest/1"

    /** Where a staged document lives, inside app-private storage. */
    const val UPDATE_FILE = "manifest.txt"

    /** A manifest is a handful of short lines; anything larger is not one. */
    const val MAX_DOCUMENT_BYTES = 16 * 1024

    private const val SIGNATURE_FIELD = "signature"
    private const val ECDSA = "SHA256withECDSA"

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val MANIFEST_VERSION = Regex("[1-9][0-9]{0,8}")
    private val RELEASE_LABEL = Regex("[A-Za-z0-9._:+-]{1,80}")

    private val FIELDS = listOf(
        "version",
        "core-version",
        "core-url",
        "core-sha256",
        "debian-version",
        "debian-url",
        "debian-sha256",
        "native-core-sha256",
        "native-wallet-tool-sha256"
    )

    /** The two native pins are legitimately blank while no native build is published. */
    private val BLANKABLE = setOf("native-core-sha256", "native-wallet-tool-sha256")

    /** The pins this APK was compiled with — the default answer, and the fallback after a stale read. */
    fun builtin(): RuntimePins = RuntimePins(
        version = RuntimeManifest.BUILTIN_MANIFEST_VERSION,
        coreVersion = RuntimeManifest.CORE_VERSION,
        coreUrl = RuntimeManifest.CORE_URL,
        coreSha256 = RuntimeManifest.CORE_SHA256,
        debianVersion = RuntimeManifest.DEBIAN_VERSION,
        debianUrl = RuntimeManifest.DEBIAN_URL,
        debianSha256 = RuntimeManifest.DEBIAN_SHA256,
        nativeCoreSha256 = RuntimeManifest.NATIVE_CORE_SHA256,
        nativeWalletToolSha256 = RuntimeManifest.NATIVE_WALLET_TOOL_SHA256
    )

    /**
     * The exact bytes that get signed: LF-only, printable ASCII, one `key value` per line, in a
     * fixed order. The verifier does not re-derive this — it signs and checks the raw bytes — but
     * round-tripping through it is what [app]s the document to a signer.
     */
    fun canonical(pins: RuntimePins): ByteArray = buildString {
        append(HEADER).append('\n')
        append("version ").append(pins.version).append('\n')
        append("core-version ").append(pins.coreVersion).append('\n')
        append("core-url ").append(pins.coreUrl).append('\n')
        append("core-sha256 ").append(pins.coreSha256).append('\n')
        append("debian-version ").append(pins.debianVersion).append('\n')
        append("debian-url ").append(pins.debianUrl).append('\n')
        append("debian-sha256 ").append(pins.debianSha256).append('\n')
        append("native-core-sha256 ").append(pins.nativeCoreSha256.ifBlank { "-" }).append('\n')
        append("native-wallet-tool-sha256 ").append(pins.nativeWalletToolSha256.ifBlank { "-" }).append('\n')
    }.toByteArray(Charsets.US_ASCII)

    /**
     * Verifies a whole document and returns the pins it carries.
     *
     * Throws — never returns a partial or "probably fine" answer — on every failure: an
     * unconfigured key, an unknown signer, tampering, or a payload that is signed but does not
     * obey the format. Callers are expected to treat that as fatal.
     */
    fun verifyDocument(document: ByteArray, publicKeyB64: String): RuntimePins {
        if (document.isEmpty()) fail("Runtime manifest update is empty")
        if (document.size > MAX_DOCUMENT_BYTES) fail("Runtime manifest update is oversized")
        if (document[document.size - 1] != NEWLINE) {
            fail("Runtime manifest does not end with a signature line")
        }
        if (document.any { !isReadableAscii(it) }) {
            fail("Runtime manifest must be printable ASCII with LF line endings")
        }

        // The last line is the signature; every byte before it is what was signed.
        var lastBreak = -1
        for (i in 0 until document.size - 1) {
            if (document[i] == NEWLINE) lastBreak = i
        }
        val payloadEnd = lastBreak + 1
        val payload = document.copyOfRange(0, payloadEnd)
        val signatureLine = String(document, payloadEnd, document.size - 1 - payloadEnd, Charsets.US_ASCII)

        if (payload.isEmpty()) fail("Runtime manifest carries no signed payload")
        if (!signatureLine.startsWith("$SIGNATURE_FIELD ")) {
            fail("Runtime manifest does not end with a signature line")
        }

        val signatureText = signatureLine.substring(SIGNATURE_FIELD.length + 1)
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureText)
        } catch (invalid: IllegalArgumentException) {
            fail("Runtime manifest signature is not valid base64")
        }

        val verifier = Signature.getInstance(ECDSA)
        verifier.initVerify(decodePublicKey(publicKeyB64))
        verifier.update(payload)
        val signed = try {
            verifier.verify(signatureBytes)
        } catch (malformed: SignatureException) {
            fail("Runtime manifest signature check failed")
        }
        // Signature first, payload second: a document edited after signing is reported as the
        // forgery it is, not as whichever field the edit happened to land in.
        if (!signed) fail("Runtime manifest signature check failed")

        return parse(payload)
    }

    /**
     * The pins in force in this install: the built-in ones, unless a staged document exists.
     *
     * A staged document that does not verify is fatal rather than ignored — silently reverting to
     * the built-in pins would hide exactly the swap the signature is there to prevent. A staged
     * document that verifies but is not newer is not applied; the effective pins then simply stay
     * the ones the app already trusts, which is a refusal of the downgrade, not a swap.
     */
    fun effective(updateDir: File, publicKeyB64: String = RuntimeManifest.RELEASE_PUBLIC_KEY): RuntimePins {
        val file = File(updateDir, UPDATE_FILE)
        if (!file.exists()) return builtin()

        val size = if (file.isFile) file.length() else -1L
        if (size < 1L || size > MAX_DOCUMENT_BYTES) fail("Runtime manifest update is missing or oversized")

        val pins = verifyDocument(file.readBytes(), publicKeyB64)
        val current = builtin()
        if (pins.version <= current.version) return current
        return pins
    }

    /** [effective] for app code: the staging directory lives under this install's files directory. */
    fun effective(context: Context): RuntimePins = effective(updateDirectory(context))

    /**
     * Verifies [document] and, only if it verifies and is newer than the built-in version, puts it
     * in place atomically for the next [effective] to read. This is the one door into the update
     * directory: transport code hands over bytes it downloaded and gets either verified pins or a
     * refusal, and an unverified document never reaches disk.
     */
    fun stage(
        updateDir: File,
        document: ByteArray,
        publicKeyB64: String = RuntimeManifest.RELEASE_PUBLIC_KEY
    ): RuntimePins {
        val pins = verifyDocument(document, publicKeyB64)
        val current = builtin()
        if (pins.version <= current.version) {
            fail(
                "Runtime manifest version ${pins.version} is not newer than the built-in " +
                    "version ${current.version}; downgrades are refused"
            )
        }

        updateDir.mkdirs()
        val staging = File(updateDir, "$UPDATE_FILE.part")
        FileOutputStream(staging).use { out ->
            out.write(document)
            out.fd.sync()
        }
        if (!staging.renameTo(File(updateDir, UPDATE_FILE))) {
            staging.delete()
            fail("Unable to install the runtime manifest update")
        }
        return pins
    }

    /** [stage] for app code. */
    fun stage(context: Context, document: ByteArray): RuntimePins =
        stage(updateDirectory(context), document)

    /** The app-private directory a verified update document is staged in. */
    fun updateDirectory(context: Context): File = File(File(context.filesDir, "fullmode"), "runtime-update")

    private fun parse(payload: ByteArray): RuntimePins {
        val text = String(payload, Charsets.US_ASCII)
        if (!text.endsWith("\n")) fail("Runtime manifest payload is not newline terminated")

        val lines = text.removeSuffix("\n").split('\n')
        if (lines[0] != HEADER) fail("Unrecognised runtime manifest format")

        val values = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (SIGNATURE_FIELD == line.substringBefore(' ')) {
                fail("Runtime manifest carries more than one signature")
            }
            if (!line.contains(' ') || line.isBlank()) fail("Malformed runtime manifest line")

            val key = line.substringBefore(' ')
            val value = line.substringAfter(' ')
            if (key !in FIELDS) fail("Unknown runtime manifest field '$key'")
            if (values.containsKey(key)) fail("Duplicate runtime manifest field '$key'")
            if (value.isBlank()) fail("Runtime manifest field '$key' has no value")
            values[key] = value
        }

        val missing = FIELDS.firstOrNull { it !in values }
        if (missing != null) fail("Runtime manifest is missing '$missing'")

        val coreSha256 = values.getValue("core-sha256")
        if (!SHA256.matches(coreSha256)) fail("Runtime manifest core SHA-256 is not a digest")
        val debianSha256 = values.getValue("debian-sha256")
        if (!SHA256.matches(debianSha256)) fail("Runtime manifest rootfs SHA-256 is not a digest")

        for (field in listOf("core-url", "debian-url")) {
            val url = values.getValue(field)
            if (!url.startsWith("https://") || url.any { it.isWhitespace() } || url.length > 300) {
                fail("Runtime manifest $field must be a plain HTTPS URL")
            }
        }
        for (field in listOf("core-version", "debian-version")) {
            val label = values.getValue(field)
            if (!RELEASE_LABEL.matches(label)) fail("Runtime manifest $field is not a release label")
        }

        val nativePins = BLANKABLE.associateWith { field ->
            values.getValue(field).takeUnless { it == "-" }?.also { value ->
                if (!SHA256.matches(value)) fail("Runtime manifest $field is not a digest")
            }.orEmpty()
        }

        return RuntimePins(
            version = values.getValue("version").also { version ->
                if (!MANIFEST_VERSION.matches(version)) fail("Runtime manifest version is not a positive integer")
            }.toInt(),
            coreVersion = values.getValue("core-version"),
            coreUrl = values.getValue("core-url"),
            coreSha256 = coreSha256,
            debianVersion = values.getValue("debian-version"),
            debianUrl = values.getValue("debian-url"),
            debianSha256 = debianSha256,
            nativeCoreSha256 = nativePins.getValue("native-core-sha256"),
            nativeWalletToolSha256 = nativePins.getValue("native-wallet-tool-sha256")
        )
    }

    /**
     * The public half only: the private key belongs to the release pipeline. Blank means no key is
     * configured yet, which refuses every document instead of accepting one unchecked.
     */
    private fun decodePublicKey(publicKeyB64: String): java.security.PublicKey {
        val compact = publicKeyB64.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) fail("Runtime manifest release key is not configured")

        val encoded = try {
            Base64.getDecoder().decode(compact)
        } catch (invalid: IllegalArgumentException) {
            fail("Runtime manifest release key is not valid base64")
        }

        return try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        } catch (invalid: Exception) {
            fail("Runtime manifest release key is not an EC public key")
        }
    }

    private val NEWLINE = '\n'.code.toByte()

    private fun isReadableAscii(byte: Byte): Boolean {
        val value = byte.toInt() and 0xff
        return value == 0x0a || (value in 0x20..0x7e)
    }

    private fun fail(message: String): Nothing = error(message)
}
