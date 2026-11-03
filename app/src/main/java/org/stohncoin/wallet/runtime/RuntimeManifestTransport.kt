package org.stohncoin.wallet.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * The transport half of the runtime update path: fetch the document [SignedRuntimeManifest] already
 * knows how to verify, and hand it to [SignedRuntimeManifest.stage].
 *
 * The split is deliberate. [SignedRuntimeManifest] is the trust boundary and is fatal on anything it
 * cannot prove. Transport is the part that fails for uninteresting reasons — no network, a 404, a
 * captive portal answering with HTML — so a failure here is a declined update that leaves the staged
 * document exactly as it was. Nothing downloaded is trusted before it verifies, and nothing
 * unverified is written: the only write path is `stage`, which verifies first.
 *
 * The endpoint is [RuntimeManifest.MANIFEST_URL]. While it is blank this makes no network call at
 * all, so a checkout with no published endpoint stays inert in the same way the blank
 * [RuntimeManifest.RELEASE_PUBLIC_KEY] does.
 */
object RuntimeManifestTransport {
    /** Redirect hops allowed before the chain is refused. */
    const val MAX_REDIRECTS = 3

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 15_000

    /** A manifest URL is short; anything longer is not one. */
    const val MAX_ENDPOINT_LENGTH = 300

    sealed interface Outcome {
        /** A newer document verified and is now staged; [pins] are what the next start will use. */
        data class Updated(val pins: RuntimePins) : Outcome

        /** The fetched document verifies but is not newer than the pins already in force. */
        data class Current(val pins: RuntimePins) : Outcome

        /** Nothing changed. [reason] is safe to log: it never contains document contents. */
        data class Declined(val reason: String) : Outcome
    }

    /** One bounded HTTP response; [body] never exceeds the document limit by more than a byte. */
    class Response(val status: Int, val location: String?, val body: ByteArray)

    /** The single I/O seam, so every rule above it is testable without a network. */
    fun interface Source {
        fun fetch(url: String): Response
    }

    /**
     * Downloads [endpoint] (blank means inert), verifies the result with [publicKeyB64] and stages it
     * only if it verifies and is newer than the pins in force. Never throws for a transport or
     * verification failure: that is an [Outcome.Declined].
     */
    fun refresh(
        updateDir: File,
        publicKeyB64: String = RuntimeManifest.RELEASE_PUBLIC_KEY,
        source: Source = HttpsSource,
        endpoint: String = RuntimeManifest.MANIFEST_URL
    ): Outcome {
        val start = endpoint.trim()
        if (start.isEmpty()) return Outcome.Declined("no runtime manifest endpoint is configured")
        if (!isAllowedEndpoint(start)) {
            return Outcome.Declined("runtime manifest endpoint is not a plain HTTPS URL")
        }

        var url = start
        var hops = 0
        while (true) {
            val response = try {
                source.fetch(url)
            } catch (failed: Exception) {
                return Outcome.Declined(
                    "runtime manifest download failed: ${failed.message ?: failed.javaClass.simpleName}"
                )
            }

            if (response.status in 300..399) {
                val location = response.location
                if (location.isNullOrBlank()) {
                    return Outcome.Declined("runtime manifest redirect had no destination")
                }
                if (hops >= MAX_REDIRECTS) {
                    return Outcome.Declined("runtime manifest redirect chain is too long")
                }
                val next = try {
                    URL(URL(url), location).toString()
                } catch (malformed: Exception) {
                    return Outcome.Declined("runtime manifest redirect destination is not a URL")
                }
                // A redirect is the one place a plain-HTTP hop could hide, so it is re-checked
                // rather than followed: the platform would otherwise downgrade silently.
                if (!isAllowedEndpoint(next)) {
                    return Outcome.Declined("runtime manifest redirect left HTTPS")
                }
                url = next
                hops++
                continue
            }

            if (response.status != 200) {
                return Outcome.Declined("runtime manifest download failed: HTTP ${response.status}")
            }
            if (response.body.isEmpty()) return Outcome.Declined("runtime manifest download was empty")
            if (response.body.size > SignedRuntimeManifest.MAX_DOCUMENT_BYTES) {
                return Outcome.Declined("runtime manifest download was oversized")
            }
            return accept(updateDir, response.body, publicKeyB64)
        }
    }

    /** True only for a plain HTTPS URL with a host: no whitespace, no userinfo, no fragment. */
    fun isAllowedEndpoint(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_ENDPOINT_LENGTH) return false
        if (url.any { it.code < 0x21 || it.code > 0x7e }) return false

        val uri = try {
            URI(url)
        } catch (malformed: URISyntaxException) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawFragment == null
    }

    private fun accept(updateDir: File, document: ByteArray, publicKeyB64: String): Outcome {
        val verified = try {
            SignedRuntimeManifest.verifyDocument(document, publicKeyB64)
        } catch (refused: IllegalStateException) {
            return Outcome.Declined(refused.message ?: "runtime manifest was refused")
        }

        val current = try {
            SignedRuntimeManifest.effective(updateDir, publicKeyB64)
        } catch (refused: IllegalStateException) {
            // A staged document that does not verify is fatal where pins are resolved, not here:
            // transport must neither overwrite it nor paper over it, so it declines and leaves it.
            return Outcome.Declined(refused.message ?: "staged runtime manifest was refused")
        }

        if (verified.version <= current.version) return Outcome.Current(current)

        val staged = try {
            SignedRuntimeManifest.stage(updateDir, document, publicKeyB64)
        } catch (refused: IllegalStateException) {
            return Outcome.Declined(refused.message ?: "runtime manifest could not be installed")
        }
        return Outcome.Updated(staged)
    }

    /** The only network code in the update path. */
    object HttpsSource : Source {
        override fun fetch(url: String): Response {
            val connection = URL(url).openConnection() as HttpURLConnection
            // refresh() follows redirects itself, one hop at a time, so every destination is checked
            // for HTTPS before it is contacted; the platform's silent follow would skip that.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("Cache-Control", "no-store")
            try {
                if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                    error("runtime manifest download refused a non-HTTPS connection")
                }
                val status = connection.responseCode
                if (status != 200) {
                    return Response(status, connection.getHeaderField("Location"), ByteArray(0))
                }
                val body = connection.inputStream.use { readBounded(it) }
                return Response(status, null, body)
            } finally {
                connection.disconnect()
            }
        }

        /** Reads at most one byte past the limit: enough for the caller to see it was oversized. */
        private fun readBounded(input: InputStream): ByteArray {
            val limit = SignedRuntimeManifest.MAX_DOCUMENT_BYTES
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() <= limit) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
