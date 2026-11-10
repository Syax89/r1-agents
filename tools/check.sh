#!/usr/bin/env bash
# Controlli locali senza dispositivo: test unitari, lint e APK di release.
# Uso: tools/check.sh [--copy]   (--copy lascia l'APK in ./r1agents.apk)
set -euo pipefail
cd "$(dirname "$0")/.."

# JDK 17 e SDK Android: usa quelli già impostati, altrimenti i percorsi comuni di Homebrew e Android Studio.
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk-amd64; do [ -d "$j" ] && export JAVA_HOME="$j" && break; done
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -x "./gradlew" ] || { echo "gradlew non trovato: esegui lo script dalla cartella del progetto."; exit 2; }

echo "== traduzioni =="
python3 tools/i18n/audit.py >/dev/null || { echo "Audit i18n non superato: python3 tools/i18n/audit.py"; exit 1; }
echo "ok"

# Anche il QA: device-check installa quello, e prima un QA vecchio veniva certificato al posto del nuovo.
echo "== test unitari, lint, APK di rilascio e QA =="
set +e
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease assembleQa assembleQaAndroidTest 2>&1 | grep -E "^e: |BUILD|FAILED|Lint found|FAIL"
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
  echo "Gradle non riuscito (codice $status)."
  exit "$status"
fi

# Riassunto dei test dai report XML
if ls app/build/test-results/testDebugUnitTest/*.xml >/dev/null 2>&1; then
  grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml |
    awk -F'"' '{t+=$2; f+=$6; e+=$8} END {printf "test: %d eseguiti, %d falliti, %d con errore\n", t, f, e; exit (f+e>0)}'
fi
apk=app/build/outputs/apk/release/app-release.apk
[ -f "$apk" ] || { echo "APK non prodotto."; exit 1; }
echo "APK: $apk ($(du -k "$apk" | cut -f1) KB)"
[ "${1:-}" = "--copy" ] && cp "$apk" r1agents.apk && echo "copiato in r1agents.apk"
exit 0
