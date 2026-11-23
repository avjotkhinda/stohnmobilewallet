#!/usr/bin/env bash
#
# Fails when gradle/verification-metadata.xml allows artifacts this build never resolves.
#
# The metadata writer only ever appends: bump a dependency and the previous version's pins stay
# behind for good, each one an artifact the build would happily accept without anyone deciding it
# should. The only honest way to know which entries are live is to let Gradle resolve everything
# again and record what it sees - so this deletes the file, regenerates it with the task set this
# project uses, and compares the two.
#
# Two details are load-bearing, both learned by getting them wrong:
#   * --refresh-dependencies, because otherwise Gradle answers from its cached resolution and
#     artifacts reached only on a genuine re-resolution (BOM POMs, plugin-classpath parents) are
#     never recorded - which makes live pins look dead;
#   * --no-daemon, so no warm daemon can answer from an earlier resolution either.
# A third detail is not about correctness but about not failing on the weather: this resolution is a
# full network re-fetch from an empty CI cache, exactly like resolve-every-configuration.sh, so it
# gets the same two layers - Gradle's repository retries widened from its 3-attempt default, and up to
# three resolution attempts that only repeat a network failure. A missing or wrong pin is answered on
# the first attempt; the comparison below is untouched by any of it.
# The committed file is restored on every exit path, including a failed or interrupted run.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

metadata=gradle/verification-metadata.xml
[[ -f $metadata ]] || { echo "METADATA MINIMALITY: FAIL: $metadata is missing" >&2; exit 1; }

work=$(mktemp -d)
committed="$work/committed.xml"
regenerated="$work/regenerated.xml"

restore() {
  [[ -f $committed ]] && cp "$committed" "$metadata"
  rm -rf "$work"
}
trap restore EXIT

cp "$metadata" "$committed"

# assembleRelease, lintRelease and bundleRelease refuse to run without a signing configuration. A
# throwaway key is enough: the APK and bundle built here are compared for pins, never shipped.
if [[ -z ${STOHN_RELEASE_STORE_FILE:-} ]]; then
  keystore="$work/metadata-check.jks"
  password=metadata-check
  if ! keytool -genkeypair -keystore "$keystore" -storetype PKCS12 \
    -storepass "$password" -keypass "$password" -alias metadata-check \
    -keyalg RSA -keysize 2048 -validity 1 -dname "CN=metadata-check" >/dev/null 2>&1; then
    echo "METADATA MINIMALITY: FAIL: could not create a throwaway signing key" >&2
    exit 1
  fi
  export STOHN_RELEASE_STORE_FILE="$keystore"
  export STOHN_RELEASE_STORE_PASSWORD="$password"
  export STOHN_RELEASE_KEY_ALIAS=metadata-check
  export STOHN_RELEASE_KEY_PASSWORD="$password"
fi

# The task set CI resolves; override only to exercise this script cheaply while editing it.
tasks=(help assembleDebug assembleRelease test :app:jacocoDebugUnitTestCoverage detekt
  :app:lintDebug :app:lintRelease :app:bundleRelease
  :app:dependencies --configuration releaseRuntimeClasspath)
if [[ -n ${METADATA_MINIMAL_TASKS:-} ]]; then
  read -r -a tasks <<< "$METADATA_MINIMAL_TASKS"
fi

# Gradle retries a failed repository access three times from a 1s backoff and then disables that
# repository for the rest of the build, which is what turns a transient error into "artifact not
# found". These are Gradle's own (internal) knobs, still read in 9.6; the values match
# resolve-every-configuration.sh so both resolutions survive the same flaky window.
gradle_retry_args=(-Dorg.gradle.internal.repository.max.tentatives=8
  -Dorg.gradle.internal.repository.initial.backoff=2000)
# Cause lines only: a genuine 404 also prints "Could not GET '...'", so matching the request would
# retry an artifact that does not exist. Identical to the copy in resolve-every-configuration.sh.
transient='Read timed out|connect timed out|Connection (reset|refused)|Remote host terminated|SSLException|unexpected end of stream|is disabled due to earlier error|Received status code 5[0-9][0-9]|Temporary failure in name resolution|Network is unreachable'
resolution_log="$work/resolution.log"

status=1
for attempt in 1 2 3; do
  # Deleting is the point: a writer run against an existing file merges into it, so anything stale
  # would survive and be reported as live. That applies to a partial file a failed attempt left
  # behind, so every attempt starts from nothing.
  rm -f "$metadata"
  echo "resolving (attempt $attempt of 3)"
  set +e
  ./gradlew --no-daemon --refresh-dependencies --console=plain \
    "${gradle_retry_args[@]}" --write-verification-metadata sha256 "${tasks[@]}" 2>&1 \
    | tee "$resolution_log"
  status=${PIPESTATUS[0]}
  set -e
  [[ $status -eq 0 ]] && break
  # Only a network failure earns another attempt. A missing artifact, an unpinned one, or a task
  # that fails to run is the answer this gate exists to give, and repeating it changes nothing.
  if [[ $attempt -lt 3 ]] && grep -qE "$transient" "$resolution_log"; then
    backoff=$((attempt * 20))
    echo "attempt $attempt of 3 failed on a network fetch; retrying in ${backoff}s" >&2
    sleep "$backoff"
    continue
  fi
  break
done

if [[ $status -ne 0 || ! -f $metadata ]]; then
  echo "METADATA MINIMALITY: FAIL: regeneration did not finish (gradle exit $status)" >&2
  exit 1
fi

cp "$metadata" "$regenerated"
cp "$committed" "$metadata"

# Keep what regeneration actually produced: a failing run is only actionable if the pins it wanted
# can be inspected without repeating a six-minute resolution.
report_dir=build/reports/metadata-minimality
mkdir -p "$report_dir"
cp "$regenerated" "$report_dir/regenerated.xml"
echo "regenerated pins: $report_dir/regenerated.xml"

python3 - "$committed" "$regenerated" <<'PY'
import sys
import xml.etree.ElementTree as ET

NS = "{https://schema.gradle.org/dependency-verification}"

def components(path):
    root = ET.parse(path).getroot()
    found = set()
    for component in root.iter(NS + "component"):
        found.add((component.get("group"), component.get("name"), component.get("version")))
    return found

def accepted_checksums(path):
    # A pin may trust more than one legitimate byte-for-byte copy of the same published artifact:
    # for example a marker POM that the plugin portal regenerates differently from Maven Central.
    # The committed file records every accepted checksum; regeneration records the one it resolved.
    root = ET.parse(path).getroot()
    found = {}
    for component in root.iter(NS + "component"):
        key = (component.get("group"), component.get("name"), component.get("version"))
        for artifact in component.iter(NS + "artifact"):
            values = set()
            for digest in artifact.iter(NS + "sha256"):
                value = digest.get("value")
                if value:
                    values.add(value)
                for alt in digest.iter(NS + "also-trust"):
                    alt_value = alt.get("value")
                    if alt_value:
                        values.add(alt_value)
            if values:
                found[(key, artifact.get("name"))] = values
    return found

def resolved_checksums(path):
    root = ET.parse(path).getroot()
    found = {}
    for component in root.iter(NS + "component"):
        key = (component.get("group"), component.get("name"), component.get("version"))
        for artifact in component.iter(NS + "artifact"):
            for digest in artifact.iter(NS + "sha256"):
                value = digest.get("value")
                if value:
                    found[(key, artifact.get("name"))] = value
    return found

committed, regenerated = components(sys.argv[1]), components(sys.argv[2])
stale = sorted(committed - regenerated)
missing = sorted(regenerated - committed)

# Matching coordinates are not enough: an entry can name the right version and the wrong bytes.
# A resolved checksum is fine when the committed pin explicitly trusts it as an alternate.
committed_hashes, regenerated_hashes = accepted_checksums(sys.argv[1]), resolved_checksums(sys.argv[2])
tampered = sorted(
    (key, name)
    for (key, name), accepted in committed_hashes.items()
    if (key, name) in regenerated_hashes and regenerated_hashes[(key, name)] not in accepted
)

print(f"committed: {len(committed)} components | regeneration resolved: {len(regenerated)}")

if stale:
    print(f"\nFAIL: {len(stale)} component(s) are pinned but never resolved by this build:")
    for group, name, version in stale[:20]:
        print(f"  {group}:{name}:{version}")
    if len(stale) > 20:
        print(f"  ... and {len(stale) - 20} more")
    print("\nEach one is an artifact the build would accept without a reason to. Remove them with:")
    print("  rm gradle/verification-metadata.xml && ./gradlew --no-daemon --refresh-dependencies \\")
    print("    --write-verification-metadata sha256 help assembleDebug assembleRelease test \\")
    print("    :app:jacocoDebugUnitTestCoverage detekt :app:lintDebug :app:lintRelease \\")
    print("    :app:bundleRelease :app:dependencies --configuration releaseRuntimeClasspath")

if missing:
    print(f"\nFAIL: {len(missing)} component(s) this build resolves are not pinned:")
    for group, name, version in missing[:20]:
        print(f"  {group}:{name}:{version}")
    if len(missing) > 20:
        print(f"  ... and {len(missing) - 20} more")

if tampered:
    print(f"\nFAIL: {len(tampered)} artifact(s) are pinned with a checksum this build does not resolve:")
    for (group, name, version), artifact in tampered[:20]:
        print(f"  {group}:{name}:{version} {artifact}")
    if len(tampered) > 20:
        print(f"  ... and {len(tampered) - 20} more")

if stale or missing or tampered:
    sys.exit(1)

print("METADATA MINIMALITY: PASS: every pinned component is one this build resolves")
PY
