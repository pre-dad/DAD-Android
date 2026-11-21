#!/usr/bin/env bash
# Fails when a release APK carries debug-only code: the scripted stub backend or the preview doubles.
# R8 renames classes, so this matches the literals those classes cannot do without (launch extras, stub origin)
# alongside their unobfuscated names. Only classes*.dex is scanned: those markers are code literals, so they
# cannot appear in resources or the manifest.
set -euo pipefail

apk="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
[ -f "$apk" ] || { echo "release APK not found: $apk" >&2; exit 2; }

markers='stub_backend|stub_signed_in|preview_giving|preview_partner|preview_signed_out|stub\.invalid|StubInterceptor|DebugBackend\$|PreviewEngagementService|PreviewGivingService|PreviewPartnerService|PreviewEventsService|PreviewSettingsService|DebugReferralSharer'

# Extraction must succeed before a missing marker can mean the release is clean.
unzip -tq "$apk" > /dev/null
dump=$(mktemp "${TMPDIR:-/tmp}/dad-release-strings.XXXXXX")
trap 'rm -f "$dump"' EXIT
unzip -p "$apk" 'classes*.dex' | strings -a > "$dump"
if found=$(grep -Eo "$markers" "$dump"); then
  echo "Release APK contains debug-only code:" >&2
  printf '%s\n' "$found" | sort -u >&2
  exit 1
else
  status=$?
  [ "$status" -eq 1 ] || exit "$status"
fi

# Gson fills LinkPolicy by field name, so R8 must neither rename nor strip them (either leaves the policy incomplete and
# every deep link unrouted). A kept field survives as a literal name in the DEX string table.
for field in accountPaths proofPaths partnerProofPaths claimReturnPaths notificationPaths referralPrefix companyPrefix; do
  grep -qx "$field" "$dump" || { echo "Release APK renamed or stripped LinkPolicy field: $field" >&2; exit 1; }
done
echo "Release APK is free of debug stubs and preview doubles."
