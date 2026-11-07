#!/usr/bin/env bash
# Fails when a release APK carries debug-only code: the scripted stub backend or the preview doubles.
# R8 renames classes, so this matches the literals those classes cannot do without (launch extras, stub origin)
# alongside their unobfuscated names.
set -euo pipefail

apk="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
[ -f "$apk" ] || { echo "release APK not found: $apk" >&2; exit 2; }

markers='stub_backend|stub_signed_in|preview_giving|preview_partner|preview_signed_out|stub\.invalid|StubInterceptor|DebugBackend\$|PreviewEngagementService|PreviewGivingService|PreviewPartnerService|PreviewEventsService|PreviewSettingsService|DebugReferralSharer'

found=$(unzip -p "$apk" 'classes*.dex' | strings -a | grep -Eo "$markers" | sort -u || true)
if [ -n "$found" ]; then
  echo "Release APK contains debug-only code:" >&2
  echo "$found" >&2
  exit 1
fi
echo "Release APK is free of debug stubs and preview doubles."
