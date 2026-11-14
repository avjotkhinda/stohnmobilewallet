package org.stohncoin.wallet.wallet

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-level address-book labels, persisted in `filesDir/addressbook/labels.json`.
 *
 * Core's own wallet keeps its own address labels inside the wallet database. Those travel with
 * the wallet on import/migration and are intentionally separate from this file, which is the
 * app's user-facing labels that the UI can show without loading the wallet.
 */
data class AddressBookLabel(
    val raw: String,
    val label: String
)

/** Round-trips `labels.json` without depending on any JSON library. */
data class AddressBook(val labels: List<AddressBookLabel>) {
    companion object {
        private const val FILENAME = "labels.json"

        private const val IV_LEN = 12

        /**
         * Reads the book, decrypting it when [key] is given and the file is encrypted. A plaintext
         * (legacy) file is still read so an existing book is not lost, and is migrated to the
         * encrypted form when [key] is available. With no [key] the book is read/written in plain
         * text, which keeps the pure-JVM tests working and degrades gracefully on a device whose
         * keystore is unavailable.
         */
        fun load(dir: File, key: SecretKey? = null): AddressBook {
            val file = File(dir, FILENAME)
            if (!file.isFile) return AddressBook(emptyList())
            val bytes = file.readBytes()
            if (bytes.isEmpty() || bytes.all { it.toInt() and 0xff in setOf(0x20, 0x09, 0x0a, 0x0d) }) {
                return AddressBook(emptyList())
            }
            val isPlain = bytes[0] == '{'.code.toByte()
            val text = if (isPlain) {
                String(bytes, Charsets.UTF_8)
            } else {
                if (key == null) return AddressBook(emptyList())
                runCatching { decrypt(bytes, key) }.getOrNull() ?: return AddressBook(emptyList())
            }
            if (text.isBlank()) return AddressBook(emptyList())
            val parsed = runCatching { parse(text) }.getOrNull() ?: return AddressBook(emptyList())
            // Migrate a legacy plaintext book to encrypted storage on first read.
            if (key != null && isPlain) runCatching { save(dir, parsed.labels, key) }
            return parsed
        }

        internal fun parse(text: String): AddressBook {
            val list = mutableListOf<AddressBookLabel>()
            var i = 0
            val n = text.length

            // {"labels":[
            val headOk = n >= 11 &&
                text[0] == '{' && text[1] == '"' && text[2] == 'l' && text[3] == 'a' &&
                text[4] == 'b' && text[5] == 'e' && text[6] == 'l' && text[7] == 's' &&
                text[8] == '"' && text[9] == ':' && text[10] == '['
            check(headOk) { "Address book is corrupted" }
            i = 11

            while (i < n) {
                when {
                    text[i] == ']' -> break  // leave i at ']'
                    text[i] == ',' -> { i++; continue }
                    else -> {
                        check(text[i] == '{') { "Address book is corrupted" }
                        i++ // skip {
                        // "raw":"
                        check(i + 6 <= n && text[i] == '"' && text[i + 1] == 'r' && text[i + 2] == 'a' && text[i + 3] == 'w' && text[i + 4] == '"' && text[i + 5] == ':') { "Address book is corrupted" }
                        i += 6 // skip "raw":
                        check(i < n && text[i] == '"') { "Address book is corrupted" }
                        i++ // skip opening "
                        val raw = StringBuilder()
                        while (true) {
                            check(i < n) { "Address book is corrupted" }
                            val c = text[i]
                            if (c == '"') break
                            if (c == '\\') {
                                check(i + 1 < n) { "Address book is corrupted" }
                                when (text[i + 1]) {
                                    '"' -> raw.append('"')
                                    '\\' -> raw.append('\\')
                                    'n' -> raw.append('\n')
                                    'r' -> raw.append('\r')
                                    't' -> raw.append('\t')
                                    else -> error("Address book is corrupted")
                                }
                                i += 2
                            } else {
                                raw.append(c)
                                i++
                            }
                        }
                        i++ // skip closing "
                        // ,"label":"  (10 chars: comma + "label" key + : + opening quote of value)
                        val separatorOk = i + 10 <= n &&
                            text[i] == ',' && text[i + 1] == '"' && text[i + 2] == 'l' &&
                            text[i + 3] == 'a' && text[i + 4] == 'b' && text[i + 5] == 'e' &&
                            text[i + 6] == 'l' && text[i + 7] == '"' && text[i + 8] == ':' &&
                            text[i + 9] == '"'
                        check(separatorOk) { "Address book is corrupted" }
                        i += 10 // skip ,"label":"
                        // now at first char of label VALUE
                        val label = StringBuilder()
                        while (true) {
                            check(i < n) { "Address book is corrupted" }
                            val c = text[i]
                            if (c == '"') break
                            if (c == '\\') {
                                check(i + 1 < n) { "Address book is corrupted" }
                                when (text[i + 1]) {
                                    '"' -> label.append('"')
                                    '\\' -> label.append('\\')
                                    'n' -> label.append('\n')
                                    'r' -> label.append('\r')
                                    't' -> label.append('\t')
                                    else -> error("Address book is corrupted")
                                }
                                i += 2
                            } else {
                                label.append(c)
                                i++
                            }
                        }
                        i++ // skip closing "
                        check(i < n && text[i] == '}') { "Address book is corrupted" }
                        i++ // skip }
                        list.add(AddressBookLabel(raw.toString(), label.toString()))
                    }
                }
            }
            // ]}
            // Guarded like every check above it: without the bound, a file that ends at ']'
            // throws StringIndexOutOfBounds instead of the corruption this function promises.
            check(i + 1 < n && text[i] == ']' && text[i + 1] == '}') { "Address book is corrupted" }
            i += 2 // skip ]}
            return AddressBook(list)
        }

        fun save(dir: File, labels: List<AddressBookLabel>, key: SecretKey? = null): AddressBook {
            require(labels.all { it.raw.isNotBlank() && it.label.isNotBlank() }) { "Invalid address-book labels" }
            dir.mkdirs()
            val sb = StringBuilder()
            sb.append("{\"labels\":[")
            labels.forEachIndexed { idx, l ->
                if (idx > 0) sb.append(",")
                sb.append("{\"raw\":\"")
                sb.append(escape(l.raw))
                sb.append("\",\"label\":\"")
                sb.append(escape(l.label))
                sb.append("\"}")
            }
            sb.append("]}")
            val plaintext = sb.toString()
            if (key == null) {
                File(dir, FILENAME).writeText(plaintext)
            } else {
                File(dir, FILENAME).writeBytes(encrypt(plaintext, key))
            }
            return AddressBook(labels)
        }

        private fun encrypt(plaintext: String, key: SecretKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return iv + ct
        }

        private fun decrypt(data: ByteArray, key: SecretKey): String {
            require(data.size > IV_LEN) { "Address book is corrupted" }
            val iv = data.copyOfRange(0, IV_LEN)
            val ct = data.copyOfRange(IV_LEN, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return String(cipher.doFinal(ct), Charsets.UTF_8)
        }

        private fun escape(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }

    /** Returns the label for [raw], or null when the address has no saved label. */
    fun labelFor(raw: String): String? = labels.firstOrNull { it.raw == raw }?.label

    /** All saved raw addresses. */
    val addressSet: Set<String> get() = labels.map { it.raw }.toSet()
}
