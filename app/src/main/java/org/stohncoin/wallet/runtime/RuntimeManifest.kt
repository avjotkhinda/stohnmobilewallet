package org.stohncoin.wallet.runtime

/** Immutable provenance and integrity metadata for the Full Mode runtime artifacts. */
object RuntimeManifest {
    const val APP_VERSION = "0.28.2-fullmode"
    const val CORE_VERSION = "3.2"
    const val CORE_URL = "https://github.com/StohnCoin-Projects/StohnCoin/releases/download/v3.2/stohn-3.2-aarch64-linux-gnu.tar.gz"
    const val CORE_SHA256 = "1c7fed5e4e5ef9e753146f9ada932abb20994134caaec33d1933d7f5c65531bf"
    const val DEBIAN_VERSION = "bookworm-arm64-default-20260928_05:24"
    const val DEBIAN_URL = "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20260928_05:24/rootfs.tar.xz"
    // SHA-256 published by the Linux Containers image server for this exact immutable snapshot.
    const val DEBIAN_SHA256 = "89481e6ddbc13590d1c74841a96a5e6f539a047f546993747eaab205898df43f"
    const val PROOT_SHA256 = "a7356df8dc18ca1d40bddeb85b816cb2214a45c2cb9c42df66e0473b694661fa"
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
