package org.stohncoin.wallet.wallet

import java.io.File

/**
 * Local, on-device labels for Stohn addresses.
 *
 * Addresses are case sensitive, so a label is stored against the address exactly as the user
 * entered it; only the surrounding whitespace is trimmed. Keys are written in sorted order so
 * the file stays stable across saves. A blank label removes the entry.
 *
 * The file is a flat JSON object that this class both writes and reads with [FlatJsonReader].
 * Parsing is deliberately dependency free: a platform JSON implementation is a stub in local
 * unit tests, which would make an unreadable file indistinguishable from an empty address book.
 */
class AddressBook(val labelsRoot: File) {

    fun labels(): Map<String, String> = readLabels()

    suspend fun setLabel(address: String, label: String) {
        require(address.isNotBlank()) { "Address is required" }
        val key = keyOf(address)
        val next = readLabels().toMutableMap()
        if (label.isBlank()) next.remove(key) else next[key] = label.trim()
        writeLabels(next)
    }

    suspend fun clearLabel(address: String) {
        require(address.isNotBlank()) { "Address is required" }
        val next = readLabels().toMutableMap()
        next.remove(keyOf(address))
        writeLabels(next)
    }

    suspend fun labelFor(address: String): String = readLabels()[keyOf(address)] ?: ""

    companion object {
        const val LABELS_FILE = "labels.json"

        @Volatile private var cached: AddressBook? = null
        @Volatile private var cachedRoot: File? = null

        fun initialize(labelsRoot: File): AddressBook =
            cached ?: synchronized(this) {
                if (cached != null && cachedRoot == labelsRoot) cached!!
                else AddressBook(labelsRoot).also { cached = it; cachedRoot = labelsRoot }
            }

        fun reset() {
            cached = null
            cachedRoot = null
        }
    }

    private fun readLabels(): Map<String, String> {
        val file = File(labelsRoot, LABELS_FILE)
        return try {
            if (!file.isFile) return emptyMap()
            FlatJsonReader(file.readText(Charsets.UTF_8)).parse()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeLabels(labels: Map<String, String>) {
        val file = File(labelsRoot, LABELS_FILE)
        labelsRoot.mkdirs()
        require(labelsRoot.isDirectory) { "Could not create the address book directory: $labelsRoot" }
        file.outputStream().use { it.write(serializeLabels(labels).toByteArray(Charsets.UTF_8)) }
    }
}

/** Reads the flat `{"address":"label"}` object written by [serializeLabels]. Malformed input is empty. */
private class FlatJsonReader(private val text: String) {
    private var index = 0

    fun parse(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        skipSpace()
        if (!consume('{')) return emptyMap()
        skipSpace()
        if (consume('}')) return emptyMap()
        while (true) {
            skipSpace()
            val key = readString() ?: return emptyMap()
            skipSpace()
            if (!consume(':')) return emptyMap()
            skipSpace()
            val value = readString() ?: return emptyMap()
            value.trim().takeIf { it.isNotEmpty() }?.let { out[key] = it }
            skipSpace()
            when {
                consume(',') -> continue
                consume('}') -> return out
                else -> return emptyMap()
            }
        }
    }

    private fun skipSpace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun consume(expected: Char): Boolean {
        if (index < text.length && text[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun readString(): String? {
        if (!consume('"')) return null
        val out = StringBuilder()
        while (index < text.length) {
            when (val c = text[index++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) return null
                    when (val escape = text[index++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (index + 4 > text.length) return null
                            val code = text.substring(index, index + 4).toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                }
                else -> out.append(c)
            }
        }
        return null
    }
}

private fun serializeLabels(labels: Map<String, String>): String = buildString {
    append('{')
    labels.filterValues { it.isNotEmpty() }
        .entries
        .sortedBy { it.key }
        .forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append("\"${escape(k)}\":\"${escape(v)}\"")
        }
    append('}')
}

private fun escape(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

private fun keyOf(address: String): String = address.trim()
