# StohnWallet Full Mode v0.28

Security-focused Android ARM64 StohnCoin wallet/node prototype.

v0.28 retains the hardened runtime execution boundary: PRoot and its loader are verified from the APK native-library directory, and the glibc Stohn wallet tool is executed through PRoot rather than directly by Android.

The project is **not release-ready** until a real ARM64 PRoot bundle is built, its hashes are independently pinned, the APK compiles, and the resulting APK is tested on the target Android 15 device.
