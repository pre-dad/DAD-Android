#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TEST_DATABASE_URL:?Set TEST_DATABASE_URL to a migrated, disposable synthetic donor_go database}"
backend="${DAD_MAIN_DIR:-../DAD-Main}"
backend="$(cd "$backend" && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/dad-android-native.XXXXXX")"
server=""
cleanup() {
  touch "$work/done"
  if [ -n "$server" ]; then wait "$server" || cat "$work/server.log"; fi
  rm -rf "$work"
}
trap cleanup EXIT
(
  cd "$backend/apps/donor-api"
  DONOR_NATIVE_ACCEPTANCE_DIR="$work" go test -race ./internal/httpapi -run '^TestAppSessionLifecycle$' -count=1 -timeout=10m
) > "$work/server.log" 2>&1 &
server=$!
ready=false
for _ in $(seq 1 120); do
  if [ -f "$work/ready" ]; then ready=true; break; fi
  if ! kill -0 "$server" 2>/dev/null; then cat "$work/server.log"; exit 1; fi
  sleep 1
done
if [ "$ready" != true ]; then echo "Native test API did not start" >&2; cat "$work/server.log"; exit 1; fi
./gradlew :app:testDebugUnitTest -PdadIntegrationFixture="$work/android.json" \
  --tests org.dad.foundation.LiveAPIIntegrationTest --warning-mode all
touch "$work/done"
wait "$server"
server=""
echo "Android passed the real HTTPS API session lifecycle."
