#!/usr/bin/env bash
# Compares two builds of the same commit and fails if the build is not reproducible.
#
# usage: scripts/verify-reproducible-build.sh <apk-a> <apk-b> [sbom-a sbom-b]
#
# What is compared, and why not simply `sha256sum` of the two APKs:
#
# apksigner signs each build with a fresh nonce — RSA uses PSS, EC uses ECDSA, and both are
# randomised by design — so the bytes inside the APK Signing Block differ between two builds of the
# same source even when everything that ships is identical. Comparing the whole file would therefore
# fail on every run and prove nothing. The check compares the *payload* instead: every entry the
# build produces, by name and content, with the signature files excluded, because those are the only
# entries a signature changes. A difference there is real nondeterminism — a timestamp in a dex, a
# resource ordering, a host path in a library — and fails the run.
#
# The SBOMs are compared the same way: their component list must be identical, while the APK digest
# they record is expected to move with the signature.
set -euo pipefail

apk_a=${1:?usage: verify-reproducible-build.sh <apk-a> <apk-b> [sbom-a sbom-b]}
apk_b=${2:?usage: verify-reproducible-build.sh <apk-a> <apk-b> [sbom-a sbom-b]}
sbom_a=${3:-}
sbom_b=${4:-}

for apk in "$apk_a" "$apk_b"; do
    [ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 1; }
done
if [ -n "$sbom_a" ] || [ -n "$sbom_b" ]; then
    for sbom in "$sbom_a" "$sbom_b"; do
        [ -f "$sbom" ] || { echo "no such SBOM: $sbom" >&2; exit 1; }
    done
fi

echo "APK A: $(sha256sum "$apk_a" | cut -d' ' -f1)  $apk_a"
echo "APK B: $(sha256sum "$apk_b" | cut -d' ' -f1)  $apk_b"

APK_A="$apk_a" APK_B="$apk_b" SBOM_A="$sbom_a" SBOM_B="$sbom_b" python3 - <<'PY'
import hashlib
import json
import os
import sys
import zipfile

# Entries a signature rewrites: the JAR manifest and its per-signer files, plus the v2+ signing block,
# which lives outside any entry. Everything else is build output and must match.
SIGNATURE_ENTRIES = ("META-INF/MANIFEST.MF",)
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def is_signature(name):
    return name in SIGNATURE_ENTRIES or name.upper().endswith(SIGNATURE_SUFFIXES)


def payload(apk):
    """name -> sha256 of the entry's contents, for every entry a signature does not touch."""
    entries = {}
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            if info.is_dir() or is_signature(info.filename):
                continue
            digest = hashlib.sha256()
            with archive.open(info) as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
            entries[info.filename] = digest.hexdigest()
    return entries


def payload_digest(entries):
    canonical = hashlib.sha256()
    for name in sorted(entries):
        canonical.update(name.encode())
        canonical.update(b"\0")
        canonical.update(entries[name].encode())
        canonical.update(b"\n")
    return canonical.hexdigest()


apk_a, apk_b = os.environ["APK_A"], os.environ["APK_B"]
a, b = payload(apk_a), payload(apk_b)
digest_a, digest_b = payload_digest(a), payload_digest(b)

print(f"payload A: {digest_a} ({len(a)} entries)")
print(f"payload B: {digest_b} ({len(b)} entries)")
if digest_a != digest_b:
    names = sorted(set(a) | set(b))
    changed = [n for n in names if a.get(n) != b.get(n)]
    only_a = [n for n in names if n not in b]
    only_b = [n for n in names if n not in a]
    print(f"::error title=Not reproducible::{len(changed)} entries differ between two builds of the same commit")
    for name in changed[:25]:
        state = "content differs" if name in a and name in b else "present on one side only"
        print(f"  {state}: {name}")
    if only_a:
        print(f"  only in A: {only_a[:10]}")
    if only_b:
        print(f"  only in B: {only_b[:10]}")
    sys.exit(1)

sbom_a, sbom_b = os.environ.get("SBOM_A", ""), os.environ.get("SBOM_B", "")
if sbom_a and sbom_b:
    def components(path):
        document = json.load(open(path, encoding="utf-8"))
        return hashlib.sha256(
            json.dumps(document.get("components"), sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()

    ca, cb = components(sbom_a), components(sbom_b)
    print(f"SBOM components A: {ca}")
    print(f"SBOM components B: {cb}")
    if ca != cb:
        print("::error title=SBOM not reproducible::the two builds produced different component lists")
        sys.exit(1)

print("REPRODUCIBLE BUILD: PASS: both builds produced identical payload and component lists")
print("note: the signed files themselves differ only in the APK signing block, which apksigner")
print("      randomises per signature (RSA-PSS / ECDSA nonces), so their sha256 is expected to move.")
PY
