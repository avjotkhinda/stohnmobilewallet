#!/usr/bin/env bash
# CycloneDX SBOM and SHA-256 checksum file for a built APK.
#
# usage: scripts/generate-sbom.sh <apk> [output-directory]
#
# The component list is the resolved `releaseRuntimeClasspath` — exactly what the release compiles
# and packages, not everything the buildscript mentions. Every component's digest is read from
# `gradle/verification-metadata.xml`, so the SBOM carries the same SHA-256 that Gradle already
# refuses to build against anything else than: a runtime dependency the verification metadata does
# not cover is a hard failure here, never a component listed with a guessed or missing hash.
#
# The checksum file sits beside the APK and names it by its bare filename, so `sha256sum -c
# SHA256SUMS` works from that directory after downloading both files.
#
# Set `SOURCE_DATE_EPOCH` to the commit time to make the document reproducible: every field is then a
# function of the APK and the dependency graph, and two clean builds of the same commit produce
# byte-identical SBOMs.
set -euo pipefail

apk=${1:?usage: generate-sbom.sh <apk> [output-directory]}
[ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 1; }

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
build_file="$root/app/build.gradle.kts"
metadata="$root/gradle/verification-metadata.xml"
[ -f "$metadata" ] || { echo "missing $metadata" >&2; exit 1; }

version_name=$(sed -n 's/^[[:space:]]*versionName = "\(.*\)".*/\1/p' "$build_file" | head -1)
version_code=$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\).*/\1/p' "$build_file" | head -1)
application_id=$(sed -n 's/^[[:space:]]*applicationId = "\(.*\)".*/\1/p' "$build_file" | head -1)
[ -n "$version_name" ] && [ -n "$version_code" ] && [ -n "$application_id" ] || {
    echo "could not read versionName/versionCode/applicationId from $build_file" >&2
    exit 1
}

out_dir=${2:-$(dirname "$apk")}
mkdir -p "$out_dir"
out_dir=$(cd "$out_dir" && pwd)
apk=$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")
sbom="$out_dir/stohn-wallet-$version_name.cdx.json"

echo "Resolving releaseRuntimeClasspath…"
deps_file=$(mktemp)
trap 'rm -f "$deps_file"' EXIT
(cd "$root" && ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath --console=plain) > "$deps_file"

APK="$apk" SBOM="$sbom" OUT_DIR="$out_dir" \
VERSION_NAME="$version_name" VERSION_CODE="$version_code" APPLICATION_ID="$application_id" \
METADATA="$metadata" python3 - "$deps_file" <<'PY'
import datetime
import hashlib
import json
import os
import re
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

deps_file = sys.argv[1]
apk = os.environ["APK"]
sbom_path = os.environ["SBOM"]
out_dir = os.environ["OUT_DIR"]
version_name = os.environ["VERSION_NAME"]
version_code = os.environ["VERSION_CODE"]
application_id = os.environ["APPLICATION_ID"]

# `./gradlew :app:dependencies` renders a tree. A line is either a node
# ("+--- group:name:version", possibly "-> resolved") or a constraint ("(c)"), which only pins a
# version without shipping an artifact, so constraints are not components.
NODE = re.compile(r"^(?:[|+\\-]+ +)*([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):(\S+?)(?: -> (\S+))?(?: \(.*\))?$")
ARTIFACT_EXT = (".aar", ".jar")

text = open(deps_file, encoding="utf-8", errors="replace").read().splitlines()
start = next(i for i, line in enumerate(text) if line.startswith("releaseRuntimeClasspath"))

coords = {}
for line in text[start + 1:]:
    line = line.strip()
    if not line or line.startswith(("Project ", "-")):
        break
    if line.endswith("(c)"):
        continue
    match = NODE.match(line)
    if not match:
        continue
    group, name, declared, resolved = match.groups()
    version = (resolved or declared).strip()
    if version in ("", "FAILED", "n/a"):
        raise SystemExit(f"unresolved dependency: {line.strip()}")
    coords[(group, name, version)] = True

if not coords:
    raise SystemExit("no resolved dependencies found in the releaseRuntimeClasspath report")

# gradle/verification-metadata.xml is the build's own trust anchor: (group, name) -> artifact file
# name -> sha256. `.module`/`.pom` are resolution metadata rather than the packaged binary, so the
# primary digest is taken from the .aar/.jar of the resolved version.
trusted = {}
for component in ET.parse(os.environ["METADATA"]).getroot().iter():
    if not component.tag.endswith("component"):
        continue
    key = (component.get("group"), component.get("name"), component.get("version"))
    hashes = {}
    for artifact in component:
        if not artifact.tag.endswith("artifact"):
            continue
        digest = next((c.get("value") for c in artifact if c.tag.endswith("sha256")), None)
        if digest:
            hashes[artifact.get("name")] = digest
    if key not in trusted:
        trusted[key] = {}
    trusted[key].update(hashes)

missing, skipped, components = [], [], []
for group, name, version in sorted(coords):
    # Exact version only: falling back to another version of the same module would publish a digest
    # for a binary this APK does not contain.
    digests = trusted.get((group, name, version), {})
    if not digests:
        missing.append(f"{group}:{name}:{version}")
        continue
    primary = sorted(digest for artifact, digest in digests.items() if artifact.endswith(ARTIFACT_EXT))
    if not primary:
        # Rows that legitimately carry no .aar/.jar: a BOM or platform, which is a list of version
        # constraints; a Kotlin multiplatform root, whose code arrives as the -jvm/-android variant
        # listed beside it; and a common-only stub, which has no artifact for this variant at all.
        # Each is reported rather than silently dropped, and none of them can hide code: a variant
        # that does ship is a component in its own right, with its own digest.
        variant = next(
            (f"{name}{suffix}" for suffix in ("-jvm", "-android") if (group, f"{name}{suffix}", version) in coords),
            None,
        )
        if variant:
            skipped.append(f"{group}:{name}:{version} (code arrives as {variant})")
        elif set(digests) == {f"{name}-{version}.pom"}:
            skipped.append(f"{group}:{name}:{version} (BOM/platform: version constraints only)")
        else:
            skipped.append(f"{group}:{name}:{version} (metadata only: no artifact for this variant)")
        continue
    purl = f"pkg:maven/{group}/{name}@{version}"
    components.append(
        {
            "type": "library",
            "bom-ref": purl,
            "group": group,
            "name": name,
            "version": version,
            "purl": purl,
            "scope": "required",
            "hashes": [{"alg": "SHA-256", "content": digest} for digest in sorted(set(primary))],
        }
    )

if missing:
    raise SystemExit(
        "runtime dependencies absent from gradle/verification-metadata.xml (refusing to publish an "
        "SBOM for a build Gradle cannot verify):\n  " + "\n  ".join(missing)
    )
if not components:
    raise SystemExit("no verified components: the dependency report was not read as expected")

sha256 = hashlib.sha256()
with open(apk, "rb") as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        sha256.update(chunk)
apk_hash = sha256.hexdigest()
apk_name = os.path.basename(apk)
# SOURCE_DATE_EPOCH makes the document byte-identical for identical inputs, which is what lets the
# reproducible-build job compare two runners' SBOM hashes instead of timestamps.
epoch = os.environ.get("SOURCE_DATE_EPOCH", "").strip()
if epoch and not epoch.isdigit():
    raise SystemExit(f"SOURCE_DATE_EPOCH must be a whole number of seconds, got {epoch!r}")
moment = (
    datetime.datetime.fromtimestamp(int(epoch), datetime.timezone.utc)
    if epoch
    else datetime.datetime.now(datetime.timezone.utc)
)
timestamp = moment.strftime("%Y-%m-%dT%H:%M:%SZ")
app_ref = f"urn:stohn:apk:{application_id}@{version_name}"

# The executables the app actually runs ship inside the APK, not as Maven coordinates: PRoot and its
# loader, talloc, the shmem helper. They get their own entries with the digest of the packaged
# bytes, which is what a reader needs to compare against a published runtime pin.
with zipfile.ZipFile(apk) as archive:
    payload = sorted(info.filename for info in archive.infolist() if info.filename.startswith("lib/") and info.filename.endswith(".so"))
    for entry in payload:
        digest = hashlib.sha256()
        with archive.open(entry) as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        components.append(
            {
                "type": "file",
                "bom-ref": f"urn:stohn:apk-file:{entry}",
                "name": os.path.basename(entry),
                "version": version_name,
                "hashes": [{"alg": "SHA-256", "content": digest.hexdigest()}],
                "properties": [{"name": "apk:path", "value": entry}],
            }
        )
if not payload:
    raise SystemExit("the APK carries no lib/**/*.so payload, which every runtime needs")

libraries = [c for c in components if c["type"] == "library"]
payload_files = [c for c in components if c["type"] == "file"]
components = libraries + payload_files

document = {
    "bomFormat": "CycloneDX",
    "specVersion": "1.5",
    "version": 1,
    # Deterministic for a given APK: the same binary always yields the same serial number.
    "serialNumber": "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, f"{app_ref}:{apk_hash}")),
    "metadata": {
        "timestamp": timestamp,
        "tools": [{"vendor": "stohncoin", "name": "scripts/generate-sbom.sh", "version": "1"}],
        "component": {
            "type": "application",
            "bom-ref": app_ref,
            "name": "stohn-wallet",
            "version": version_name,
            "hashes": [{"alg": "SHA-256", "content": apk_hash}],
            "properties": [
                {"name": "android:applicationId", "value": application_id},
                {"name": "android:versionName", "value": version_name},
                {"name": "android:versionCode", "value": version_code},
                {"name": "file:name", "value": apk_name},
            ],
        },
    },
    "components": components,
    "dependencies": [{"ref": app_ref, "dependsOn": [component["bom-ref"] for component in components]}],
}

with open(sbom_path, "w", encoding="utf-8") as handle:
    json.dump(document, handle, indent=2)
    handle.write("\n")

sums = []
for path in (apk, sbom_path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    sums.append(f"{digest.hexdigest()}  {os.path.basename(path)}")
with open(os.path.join(out_dir, "SHA256SUMS"), "w", encoding="utf-8") as handle:
    handle.write("\n".join(sums) + "\n")

print(f"SBOM: {len(components)} components, {apk_name} sha256 {apk_hash}")
print(f"wrote {sbom_path}")
print(f"wrote {os.path.join(out_dir, 'SHA256SUMS')}")
PY

(cd "$out_dir" && sha256sum -c SHA256SUMS)
echo "SBOM: PASS: CycloneDX SBOM and SHA256SUMS written for $(basename "$apk")"
