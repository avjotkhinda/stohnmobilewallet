# Embedded Linux runtime (prototype path)

This directory is reserved for the prototype single-APK Linux userspace path.

The official Stohn v3.2 ARM64 Linux release is a glibc Linux executable, while Android uses bionic. Therefore the production APK must either:

1. ship a controlled Linux userspace/proot runtime and the official ARM64 Core binary, or
2. preferably, build Stohn Core against the Android NDK/bionic and remove the Linux userspace dependency.

The project targets option 2 for production. No fake Core executable is included.
