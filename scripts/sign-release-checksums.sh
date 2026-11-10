#!/usr/bin/env bash
# Signs the release SHA256SUMS with the dedicated Ed25519 release signing key and proves the result.
#
# usage: scripts/sign-release-checksums.sh <SHA256SUMS> [expected-fingerprint]
#
# The key is separate from the APK signing keystore on purpose: the keystore cannot leave its offline
# custody and must never be loaded into a CI job, while this key exists only to authenticate the
# files published beside an APK. It is Ed25519 through OpenSSL, so a downloader needs nothing that a
# normal machine does not already have:
#
#   openssl pkeyutl -verify -pubin -inkey stohn-release-signing-key.pem \
#     -rawin -in SHA256SUMS -sigfile SHA256SUMS.sig
#   sha256sum -c SHA256SUMS
#
# The expected fingerprint is the SHA-256 of the public key's DER encoding. It is checked *before*
# signing, so a key that was swapped in a secret store cannot produce a signature that looks valid:
# the release fails instead. The same fingerprint is printed here and belongs in the repository's
# public notes, which is what makes the signature verifiable by someone who did not run this job.
set -euo pipefail

sums=${1:?usage: sign-release-checksums.sh <SHA256SUMS> [expected-fingerprint]}
expect=${2:-${STOHN_RELEASE_SIGNING_KEY_FINGERPRINT:-}}
key=${STOHN_RELEASE_SIGNING_KEY:-}

[ -f "$sums" ] || { echo "no such checksum file: $sums" >&2; exit 1; }
[ -n "$key" ] || {
    echo "STOHN_RELEASE_SIGNING_KEY must point at the Ed25519 private key (PEM)" >&2
    exit 1
}
[ -f "$key" ] || { echo "no signing key at $key" >&2; exit 1; }
[ -n "$expect" ] || {
    echo "no expected fingerprint: pass it as the second argument or in" >&2
    echo "STOHN_RELEASE_SIGNING_KEY_FINGERPRINT, so a replaced key cannot sign unnoticed" >&2
    exit 1
}
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

dir=$(cd "$(dirname "$sums")" && pwd)
sums="$dir/$(basename "$sums")"
sig="$sums.sig"
publish_key="$dir/stohn-release-signing-key.pem"

# The private key is a bearer credential for the project's downloads: refuse to use one anyone else
# could read, which in practice means a secret decoded without a chmod.
mode=$(stat -c '%a' "$key" 2>/dev/null || stat -f '%Lp' "$key")
[ "$mode" = "600" ] || {
    echo "signing key $key has mode $mode; it must be 600 (chmod 600 it before signing)" >&2
    exit 1
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

openssl pkey -in "$key" -pubout -out "$work/pub.pem" 2>/dev/null || {
    echo "cannot read an Ed25519 private key from $key" >&2
    exit 1
}
openssl pkey -pubin -in "$work/pub.pem" -outform DER -out "$work/pub.der" 2>/dev/null
fingerprint=$(openssl dgst -sha256 -r "$work/pub.der" | cut -d' ' -f1)
expected=$(printf '%s' "$expect" | tr -d ' :' | tr 'A-Z' 'a-z')
[ "$fingerprint" = "$expected" ] || {
    echo "refusing to sign: key fingerprint is $fingerprint, expected $expected" >&2
    exit 1
}

openssl pkeyutl -sign -inkey "$key" -rawin -in "$sums" -out "$sig"
cp "$work/pub.pem" "$publish_key"
chmod 644 "$publish_key"

# Verified against the published copy, not the key we just used: the bytes a downloader will have are
# the bytes that must verify, and a detached signature the checksum file no longer matches fails here
# rather than on someone's machine.
openssl pkeyutl -verify -pubin -inkey "$publish_key" -rawin -in "$sums" -sigfile "$sig" >/dev/null || {
    echo "signature does not verify against $publish_key" >&2
    exit 1
}
(cd "$dir" && sha256sum -c "$(basename "$sums")" >/dev/null) || {
    echo "the checksum file does not match the files beside it" >&2
    exit 1
}

echo "checksums: $(wc -l < "$sums" | tr -d ' ') files, ed25519 signature $(stat -c '%s' "$sig" 2>/dev/null || stat -f '%z' "$sig") bytes"
echo "signing key fingerprint: $fingerprint"
echo "verify with: openssl pkeyutl -verify -pubin -inkey $(basename "$publish_key") -rawin -in $(basename "$sums") -sigfile $(basename "$sig")"
echo "RELEASE SIGNING: PASS: $(basename "$sums") signed and verified"
