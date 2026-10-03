# StohnWallet Full Mode v0.28

Security-focused Android ARM64 StohnCoin wallet/node prototype.

v0.28 retains the hardened runtime execution boundary: PRoot and its loader are verified from the APK native-library directory, and the glibc Stohn wallet tool is executed through PRoot rather than directly by Android.

The project is an experimental, pre-release prototype. **Do not use it to store or transfer real funds or production wallet keys.** It has not had an independent security audit, and it has not been confirmed on the target Android device.

The source pins hashes for the runtime artifacts and checks the bundled PRoot components before use. Android app backup is disabled, and Core RPC is bound to loopback and protected with Core's per-instance cookie authentication. These controls reduce specific risks; they are not a substitute for an independent review or proof that the complete application is safe.

Before any release intended for real funds, the project still needs an independent security review, successful Android build and target-device testing, and documented release-key and repository access controls. See [SECURITY.md](SECURITY.md) for the current security status and scope.
