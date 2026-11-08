#!/usr/bin/env bash
# Genera un QR di collegamento offline; richiede lo stesso JDK usato per compilare l’app.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3"
jar=$(find "$cache" -name '*.jar' -type f -print -quit 2>/dev/null || true)
[ -n "$jar" ] || { echo "ZXing non trovato: esegui prima tools/check.sh." >&2; exit 2; }
exec "$java_bin" -Djava.awt.headless=true --class-path "$jar" tools/PairQr.java "$@"
