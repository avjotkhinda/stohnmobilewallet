#!/usr/bin/env bash
#
# Resolves every configuration under strict dependency verification, riding out a flaky fetch.
#
# This is the other direction from the metadata gate: verify-metadata-minimal.sh fails when the
# committed file pins something the build no longer needs, while this run fails when a
# configuration needs an artifact the committed file does not pin, or pins with the wrong checksum.
# Gradle's own verification does the work while resolving, so a failure names the artifact.
#
# --refresh-dependencies sends every request to the network, and CI deliberately has no setup-gradle
# cache before this step, so a flaky Maven Central or Plugin Portal fetch fails a job whose commit
# builds fine - which is how the step went red twice in three runs (a missing jar, then the Kotlin
# Compose plugin marker). Two layers keep those out of main:
#   * In-process, which is why the wait is seconds rather than another six-minute resolution: Gradle
#     retries an access it classifies as a transient network issue three times and then disables that
#     repository for the rest of the build, turning a blip into "artifact not found". The two
#     properties below are Gradle's own retry knobs - read with Integer.getInteger in
#     ErrorHandlingModuleComponentRepository, so undocumented and unsupported, but still read in the
#     Gradle 9.6 this project wraps - and widen that to eight attempts from a 2s doubling backoff.
#   * Whole-run, as the backstop for when Gradle gives up anyway, normally because it disabled the
#     repository: the resolution runs up to three times, and a retry happens only when the log shows
#     the failure was the network.
#
# Neither layer touches the pin check. A verification failure is reported on the first attempt, and
# the exit status is always the last attempt's, so a retry can make a flaky fetch green but can never
# turn a real pin failure green.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

attempts=3
log="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/resolve-every-configuration.log"

# Matched against the log to decide whether a failure was the network. Only cause lines appear here,
# never the failing request: a genuine 404 prints "Could not GET '...'" too, so matching the request
# would spend three resolutions on an artifact that does not exist. This is the same distinction
# Gradle's NetworkingIssueVerifier draws when deciding to retry in-process. Kept identical to the copy
# in verify-metadata-minimal.sh, since both resolutions fail in the same ways.
network_failure='Read timed out|connect timed out|Connection (reset|refused)|Remote host terminated|SSLException|unexpected end of stream|Received status code 5[0-9][0-9]|Temporary failure in name resolution|Network is unreachable|is disabled due to earlier error'

for ((attempt = 1; attempt <= attempts; attempt++)); do
  echo "::group::resolveEveryConfiguration (attempt $attempt of $attempts)"
  set +e
  ./gradlew --no-daemon --refresh-dependencies --console=plain \
    -Dorg.gradle.internal.repository.max.tentatives=8 \
    -Dorg.gradle.internal.repository.initial.backoff=2000 \
    -I tools/resolve-all-configurations.init.gradle resolveEveryConfiguration 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  set -e
  echo "::endgroup::"

  if [[ $status -eq 0 ]]; then
    exit 0
  fi
  # A pin or checksum failure is the answer this step exists to produce: retrying it would only turn
  # a real drift into a slower red. Gradle states it in one documented line, whatever the cause
  # (unpinned artifact, wrong checksum, untrusted key), so that is the whole marker.
  if grep -q 'Dependency verification failed' "$log"; then
    echo "::error title=Dependency verification failed::An artifact this build needs is unpinned or pinned to a different checksum. Retries do not apply; see the log above."
    exit "$status"
  fi
  if [[ $attempt -ge $attempts ]] || ! grep -qE "$network_failure" "$log"; then
    exit "$status"
  fi

  backoff=$((attempt * 20))
  echo "::warning title=Transient repository failure::Attempt $attempt of $attempts failed on a network fetch; retrying in ${backoff}s."
  sleep "$backoff"
done
