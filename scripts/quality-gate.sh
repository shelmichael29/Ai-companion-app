#!/usr/bin/env bash
# Penelope quality gate: the same checks as GitHub Actions, run locally.
#
#   scripts/quality-gate.sh "Stage name"          # full gate
#   scripts/quality-gate.sh "Stage name" --release  # also build the release APK
#
# Writes build/quality/QUALITY_REPORT.md and exits non-zero if any critical check fails.
# Each step keeps going after a failure so the report shows everything at once.
set -u
cd "$(dirname "$0")/.."

STAGE="${1:-Local development stage}"
RELEASE=""
[ "${2:-}" = "--release" ] && RELEASE="yes"

run() { # run <name> <command...>; prints success/failure
  local name="$1"; shift
  echo "::group::$name" >&2
  if "$@" >&2; then echo "success"; else echo "failure"; fi
  echo "::endgroup::" >&2
}

BUILD=$(run "Build debug APK" ./gradlew assembleDebug)
DETEKT=$(run "Detekt" ./gradlew detekt)
if [ "$BUILD" = "success" ]; then
  TESTS=$(run "Unit, integration and regression tests" ./gradlew testDebugUnitTest :enet-core:test :app:koverXmlReportDebug :enet-core:koverXmlReport)
  LINT=$(run "Android Lint" ./gradlew lintDebug)
else
  TESTS="skipped"; LINT="skipped"
fi
SECURITY=$(run "Secret scan" bash -c "python3 scripts/test_secret_scan.py && python3 scripts/secret_scan.py --history")
RELEASE_OUT=""
[ -n "$RELEASE" ] && RELEASE_OUT=$(run "Release build" ./gradlew :app:assembleRelease)

python3 scripts/quality_report.py \
  --stage "$STAGE" \
  --branch "$(git rev-parse --abbrev-ref HEAD)" \
  --commit "$(git rev-parse HEAD)" \
  --build "$BUILD" --detekt "$DETEKT" --tests "$TESTS" --lint "$LINT" --security "$SECURITY" \
  ${RELEASE:+--release "$RELEASE_OUT"} \
  --out build/quality/QUALITY_REPORT.md
