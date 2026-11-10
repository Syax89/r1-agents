#!/usr/bin/env bash
# Suite isolata: non modifica il pacchetto utente dev.r1agents e non sveglia/sblocca il Rabbit.
set -euo pipefail
cd "$(dirname "$0")/.."
adb_bin="${ADB:-adb}"
serial="${1:-}"
[ -n "$serial" ] || { echo "Uso: tools/device-check.sh <serial-adb>" >&2; exit 2; }
A() { "$adb_bin" -s "$serial" "$@"; }
A shell dumpsys power | grep -q 'mWakefulness=Awake' || { echo "Riattiva e sblocca il Rabbit prima del test QA." >&2; exit 2; }
mkdir -p work
A install -r app/build/outputs/apk/qa/app-qa.apk
A install -r app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk
# Facoltativo: una copia dell'APK QA firmata con un'altra chiave (quella di rilascio, se c'e') serve a provare che l'aggiornamento con firma diversa viene rifiutato.
store="${R1_RELEASE_KEYSTORE:-../signing-private/r1agents-release.p12}"; password_file="${R1_RELEASE_PASSWORD_FILE:-../signing-private/store-password.txt}"
signer="${R1_APKSIGNER:-$(find "${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools" -name apksigner -type f -print 2>/dev/null | sort | tail -1)}"
# apksigner ha bisogno di Java: senza JAVA_HOME la firma falliva in silenzio e restava sul telefono una copia VECCHIA (altra versione).
[ -n "${JAVA_HOME:-}" ] || { [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; }
A shell rm -f /sdcard/Android/data/dev.r1agents.qa/files/update-test/resigned.apk >/dev/null 2>&1 || true
if [ -f "$store" ] && [ -f "$password_file" ] && [ -n "$signer" ] && [ -x "$signer" ]; then
  tmp_apk="$(mktemp -t r1qa-resigned).apk"
  "$signer" sign --ks "$store" --ks-key-alias r1agents --ks-pass "file:$password_file" --out "$tmp_apk" app/build/outputs/apk/qa/app-qa.apk >/dev/null 2>&1 || echo "Firma diversa: apksigner non riuscito (Java?), controllo saltato." >&2
  [ -s "$tmp_apk" ] && {
    A shell mkdir -p /sdcard/Android/data/dev.r1agents.qa/files/update-test >/dev/null 2>&1 || true
    A push "$tmp_apk" /sdcard/Android/data/dev.r1agents.qa/files/update-test/resigned.apk >/dev/null 2>&1 || echo "Firma diversa: copia non caricata, controllo saltato." >&2
  }
  rm -f "$tmp_apk"
fi
A shell am instrument -w dev.r1agents.qa.test/dev.r1agents.RuntimeInstrumentation | tee work/device-qa.log
grep -q '^QA_RESULT=PASS' work/device-qa.log || { echo "QA non superata: vedi work/device-qa.log" >&2; exit 1; }
echo "QA superata: work/device-qa.log"
