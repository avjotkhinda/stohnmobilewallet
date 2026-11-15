#!/usr/bin/env bash
# Signs a canonical runtime-manifest payload (see SignedRuntimeManifest.canonical) and emits
# a complete signed document on stdout. The private key never enters the app or the repository.
#
# Usage:
#   tools/sign-runtime-manifest.sh <payload-file> <private-key.pem> > manifest.txt
#
# <payload-file> must contain the exact bytes produced by SignedRuntimeManifest.canonical
# (no trailing signature line). <private-key.pem> is the EC P-256 private key kept in the
# release pipeline.
set -euo pipefail
if [[ $# -ne 2 ]]; then
    echo "usage: $0 <payload-file> <private-key.pem>" >&2
    exit 2
fi
payload="$1"; key="$2"
[[ -f "$payload" ]] || { echo "payload file not found: $payload" >&2; exit 1; }
[[ -f "$key" ]] || { echo "private key not found: $key" >&2; exit 1; }
sig=$(openssl dgst -sha256 -sign "$key" "$payload" | base64 -w0)
cat "$payload"
printf 'signature %s\n' "$sig"