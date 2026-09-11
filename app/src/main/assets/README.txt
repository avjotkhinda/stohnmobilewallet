Full Mode runtime asset
=======================

Place the verified Android arm64-v8a PRoot executable at:

    assets/fullmode/proot

The APK does not ship a second Termux/Debian app. The app owns the runtime directory and downloads
only the verified Debian ARM64 rootfs and official Stohn Core v3.2 archive on first launch.

The Core release is verified against:
1c7fed5e4e5ef9e753146f9ada932abb20994134caaec33d1933d7f5c65531bf

The Debian bootstrap archive is verified against the exact SHA-256 published in the snapshot directory
SHA256SUMS file. CI resolves that digest before the release preflight and verifies the downloaded archive
against it. Do not replace the snapshot URL without reviewing and updating the release manifest.
