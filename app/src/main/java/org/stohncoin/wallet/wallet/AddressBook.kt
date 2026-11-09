package org.stohncoin.wallet.wallet

import java.io.File

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

        fun load(dir: File): AddressBook {
            val file = File(dir, FILENAME)
            if (!file.isFile) return AddressBook(emptyList())
            val text = file.readText()
            return if (text.isBlank()) AddressBook(emptyList()) else parse(text)
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
            check(i < n && text[i] == ']' && text[i + 1] == '}') { "Address book is corrupted" }
            i += 2 // skip ]}
            return AddressBook(list)
        }

        fun save(dir: File, labels: List<AddressBookLabel>): AddressBook {
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
            File(dir, FILENAME).writeText(sb.toString())
            return AddressBook(labels)
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
