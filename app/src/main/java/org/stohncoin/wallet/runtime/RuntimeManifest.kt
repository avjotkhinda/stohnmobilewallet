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
    const val PROOT_SHA256 = "66900497f1dd3c8e051f83aea534279c70785829bad50628c0501c3862c2cfea"
    const val PROOT_LOADER_SHA256 = "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"
    const val LIBTALLOC_SHA256 = "3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb"
    const val LIBANDROID_SHMEM_SHA256 = "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"
}
