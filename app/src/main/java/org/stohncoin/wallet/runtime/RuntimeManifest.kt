package org.stohncoin.wallet.runtime

import org.stohncoin.wallet.BuildConfig

/** Immutable provenance and integrity metadata for the Full Mode runtime artifacts. */
object RuntimeManifest {
    /**
     * This release's version, for the config file, the user agent and backup manifests.
     *
     * Deliberately not a constant here: `versionName` in `app/build.gradle.kts` is the single
     * declaration, Android copies it into [BuildConfig], and a bump must not depend on a second
     * copy in this file that can silently disagree with the APK it ships in.
     */
    val APP_VERSION: String = BuildConfig.VERSION_NAME
    const val CORE_VERSION = "3.2"
    const val CORE_URL = "https://github.com/StohnCoin-Projects/StohnCoin/releases/download/v3.2/stohn-3.2-aarch64-linux-gnu.tar.gz"
    const val CORE_SHA256 = "1c7fed5e4e5ef9e753146f9ada932abb20994134caaec33d1933d7f5c65531bf"
    const val DEBIAN_VERSION = "bookworm-arm64-default-20261110_05:24"
    const val DEBIAN_URL = "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261110_05:24/rootfs.tar.xz"
    // SHA-256 published by the Linux Containers image server for this exact immutable snapshot.
    const val DEBIAN_SHA256 = "68ce225782c47e500c42ef90cf4c8a4d19ed2dc1b77004010405cc7c643820ee"
    const val PROOT_SHA256 = "fc632a66a262498517080e14930740910b7beb186e1ca613d371b3cb189f17d1"
    const val PROOT_LOADER_SHA256 = "1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5"
    const val LIBTALLOC_SHA256 = "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309"
    const val LIBANDROID_SHMEM_SHA256 = "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"
    // The NDK-native backend. Both pins stay blank until a bionic Stohn Core is actually built and
    // published: with no pin the factory keeps running the glibc runtime, and a pin that does not
    // match the shipped binary is a hard failure, never a quiet swap to another backend.
    const val NATIVE_CORE_SHA256 = ""
    const val NATIVE_WALLET_TOOL_SHA256 = ""
    // The runtime update path. Version of the pins this APK ships with; a staged manifest must be
    // strictly newer than this to be applied, so a captured old manifest can never roll pins back.
    const val BUILTIN_MANIFEST_VERSION = 1
    // The public half of the release key that signs a runtime manifest (base64 SPKI, EC P-256).
    // Blank until the project publishes one, which keeps the update path inert: with no key every
    // staged document is refused, exactly like the blank native pins above.
    const val RELEASE_PUBLIC_KEY = ""
    // Where RuntimeManifestTransport looks for a signed manifest. Blank means no transport happens:
    // no request is made and the pins stay the ones this APK was built with. Set it together with
    // RELEASE_PUBLIC_KEY, and only to a plain HTTPS URL: a document that does not verify against
    // that key is refused, so publishing the address alone changes nothing.
    const val MANIFEST_URL = ""
}
