#!/usr/bin/env bash
# Registra lo schermo del Rabbit mentre si esegue un comando adb e ne fa un foglio di fotogrammi (per vedere le transizioni a meta' corsa).
# Uso: tools/record-transition.sh nome 'comandi adb in una stringa' [secondi]   (richiede ffmpeg)
set -euo pipefail
cd "$(dirname "$0")/.."
name="$1"; actions="$2"; secs="${3:-3}"
mkdir -p smoke-out/transitions
adb shell rm -f /sdcard/t.mp4 >/dev/null
( adb shell screenrecord --time-limit "$secs" --bit-rate 6000000 /sdcard/t.mp4 >/dev/null 2>&1 & ) ; sleep 0.8
eval "$actions"
sleep "$((secs))" ; sleep 1
adb pull /sdcard/t.mp4 "smoke-out/transitions/$name.mp4" >/dev/null
ffmpeg -loglevel error -y -i "smoke-out/transitions/$name.mp4" -vf "fps=12,scale=192:-1,tile=8x3" -frames:v 1 "smoke-out/transitions/$name.png"
echo "smoke-out/transitions/$name.png"
