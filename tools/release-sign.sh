#!/usr/bin/env bash
# Firma un APK già compilato senza sovrascrivere l’APK di prova compatibile con l’installazione esistente.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; fi
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
signer="${R1_APKSIGNER:-$(find "$sdk/build-tools" -name apksigner -type f -print | sort | tail -1)}"
store="${R1_RELEASE_KEYSTORE:-../signing-private/r1agents-release.p12}"
password_file="${R1_RELEASE_PASSWORD_FILE:-../signing-private/store-password.txt}"
input="${1:-app/build/outputs/apk/release/app-release.apk}"
output="${2:-../r1agents-release-signed.apk}"
[ -f "$input" ] && [ -f "$store" ] && [ -f "$password_file" ] && [ -x "$signer" ] || { echo "Mancano APK, chiave, password o Android build-tools." >&2; exit 2; }
python3 - "$input" "$output" <<'PY'
from pathlib import Path
import sys
if Path(sys.argv[1]).resolve() == Path(sys.argv[2]).resolve():
    raise SystemExit('Input e output devono essere diversi: preserva l’APK di prova.')
Path(sys.argv[2]).resolve().parent.mkdir(parents=True, exist_ok=True)
PY
"$signer" sign --ks "$store" --ks-key-alias r1agents --ks-pass "file:$password_file" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out "$output" "$input"
"$signer" verify --verbose --print-certs "$output"
echo "APK firmato: $output"
