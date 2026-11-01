package org.stohncoin.wallet.wallet

/**
 * What a backup says about the wallet it holds, written next to the wallet checksum in the
 * container's manifest entry.
 *
 * The manifest sits inside the authenticated container, so every field here is only as trustworthy
 * as the password that sealed it; its job is not to be a second secret but to make a wrong restore
 * *visible before it happens* — a user who picks the wrong file, or a wallet made by a build that
 * can no longer open it, should be told rather than discover it afterwards.
 *
 * Format (ASCII, one `key=value` per line, app version first, trailing newline):
 * ```
 * app_version=0.28.2
 * wallet_name=stohn-wallet
 * creation_height=123456
 * sha256=<64 hex of wallet.dat>
 * ```
 * Containers written before this format carry a bare 64-hex checksum and still restore; their
 * details are simply absent ([appVersion], [walletName] and [creationHeight] are null).
 */
data class BackupManifest(
    val appVersion: String?,
    val walletName: String?,
    val creationHeight: Long?,
    val walletSha256: String
) {
    /** False for containers written before wallet details were recorded. */
    val hasWalletDetails: Boolean get() = walletName != null

    companion object {
        internal const val KEY_APP_VERSION = "app_version"
        internal const val KEY_WALLET_NAME = "wallet_name"
        internal const val KEY_CREATION_HEIGHT = "creation_height"
        internal const val KEY_SHA256 = "sha256"

        private val KEYS = listOf(KEY_APP_VERSION, KEY_WALLET_NAME, KEY_CREATION_HEIGHT, KEY_SHA256)
        private val SHA256 = Regex("[0-9a-f]{64}")
        /** A version number as every other developer writes it: MAJOR.MINOR[.PATCH][.PATCH2]. */
        private val VERSION = Regex("""\d+\.\d+(?:\.\d+)*""")

        /**
         * Strips the build suffix from the app's version name so what lands in the manifest is a
         * plain version number (`0.28.2-fullmode` → `0.28.2`), and refuses anything that is not one.
         */
        fun normalizeVersion(raw: String): String {
            val base = raw.trim().substringBefore('-').trim()
            require(VERSION.matches(base)) { "App version '$raw' is not a version number" }
            return base
        }

        /** Renders a current-format manifest. [appVersion] is normalised on the way out. */
        fun render(
            appVersion: String,
            walletName: String,
            creationHeight: Long,
            walletSha256: String
        ): String {
            val version = normalizeVersion(appVersion)
            require(walletName.isNotBlank() && walletName.length <= 64) { "Wallet name is not usable in a manifest" }
            require(walletName.none { it.isWhitespace() || it == '=' || it.code < 0x20 }) {
                "Wallet name contains characters a manifest cannot carry"
            }
            require(creationHeight >= 0L) { "Creation height cannot be negative" }
            require(SHA256.matches(walletSha256)) { "Wallet checksum is not a SHA-256 hex digest" }
            return buildString {
                append(KEY_APP_VERSION).append('=').append(version).append('\n')
                append(KEY_WALLET_NAME).append('=').append(walletName).append('\n')
                append(KEY_CREATION_HEIGHT).append('=').append(creationHeight).append('\n')
                append(KEY_SHA256).append('=').append(walletSha256).append('\n')
            }
        }

        /**
         * Parses a manifest entry: the current key/value format, or the bare 64-hex checksum used
         * before wallet details existed. Throws on anything else, so a malformed manifest stops the
         * restore instead of being silently ignored.
         */
        fun parse(text: String): BackupManifest {
            val trimmed = text.trim()
            if (SHA256.matches(trimmed)) {
                return BackupManifest(appVersion = null, walletName = null, creationHeight = null, walletSha256 = trimmed)
            }
            val values = LinkedHashMap<String, String>()
            for (rawLine in trimmed.lines()) {
                if (rawLine.isEmpty()) continue
                require(rawLine.count { it == '=' } == 1) { "Backup manifest line is not 'key=value'" }
                val separator = rawLine.indexOf('=')
                val key = rawLine.substring(0, separator)
                require(key in KEYS) { "Backup manifest field '$key' is not understood by this version" }
                require(key !in values) { "Backup manifest repeats the '$key' field" }
                values[key] = rawLine.substring(separator + 1)
            }
            val missing = KEYS.firstOrNull { it !in values }
            require(missing == null) { "Backup manifest does not record '$missing'" }

            val version = normalizeVersion(values.getValue(KEY_APP_VERSION))
            val walletName = values.getValue(KEY_WALLET_NAME)
            require(walletName.isNotBlank() && walletName.length <= 64 && walletName.none { it.code < 0x20 }) {
                "Backup manifest wallet name is not usable"
            }
            val height = values.getValue(KEY_CREATION_HEIGHT)
            require(height.isNotEmpty() && height.all { it in '0'..'9' }) {
                "Backup manifest creation height is not a number"
            }
            val creationHeight = height.toLongOrNull()
            require(creationHeight != null && creationHeight >= 0L) { "Backup manifest creation height is out of range" }
            val sha256 = values.getValue(KEY_SHA256).trim()
            require(SHA256.matches(sha256)) { "Backup manifest wallet checksum is invalid" }

            return BackupManifest(
                appVersion = version,
                walletName = walletName,
                creationHeight = creationHeight,
                walletSha256 = sha256
            )
        }

        /**
         * What a restore (or a Test Backup) should tell the user, comparing this manifest with the
         * device it is about to be restored on. Informational only — nothing here blocks a restore,
         * because a node that is still syncing or a renamed wallet are normal states, not attacks.
         * Pass null for a value the caller does not know.
         */
        fun notes(
            manifest: BackupManifest,
            currentAppVersion: String?,
            chainHeight: Long?,
            activeWalletName: String?
        ): List<String> {
            if (!manifest.hasWalletDetails) {
                return listOf("This backup was made before the manifest recorded wallet details.")
            }
            val notes = mutableListOf<String>()
            val current = currentAppVersion?.let { runCatching { normalizeVersion(it) }.getOrNull() }
            if (current != null && manifest.appVersion != null && manifest.appVersion != current) {
                notes += if (manifest.appVersion > current) {
                    "Backup was made by app ${manifest.appVersion}, which is newer than this install ($current)."
                } else {
                    "Backup was made by app ${manifest.appVersion}; this install runs $current."
                }
            }
            if (activeWalletName != null && manifest.walletName != null && manifest.walletName != activeWalletName) {
                notes += "Backup holds wallet '${manifest.walletName}', but the active wallet here is '$activeWalletName'."
            }
            val height = manifest.creationHeight
            if (chainHeight != null && height != null && height > chainHeight) {
                notes += "Wallet details say it was created at height $height while the node is at $chainHeight; it may still be syncing."
            }
            return notes
        }
    }
}