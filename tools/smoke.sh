#!/usr/bin/env bash
# Giro di controllo sull'R1 (o su un emulatore) via adb: apre ogni schermata per nome, la fotografa, misura la fluidità
# delle transizioni e cerca crash. NON invia messaggi, NON cambia impostazioni, NON crea né cancella dati: usa solo gli
# intent di prova dell'app (--es debug_screen <nome>) e, prima di ogni tocco, controlla che l'app sia in primo piano.
#
# Uso: tools/smoke.sh [--install] [--out cartella] [--serial S] [--no-jank]
#   --install   installa prima ./r1agents.apk (adb install -r)
#   --out       dove salvare gli screenshot (default: ./smoke-out)
#   --serial    dispositivo da usare (default: l'unico collegato)
#   --no-jank   salta la misura della fluidità
# Esce con 0 se tutto bene, 1 se l'app è crashata o non è mai in primo piano, 2 se manca il dispositivo o è bloccato.
set -uo pipefail
cd "$(dirname "$0")/.."

PKG=dev.r1agents.qa; ACT=$PKG/dev.r1agents.MainActivity   # debug_screen funziona solo nelle build debuggabili (QA)
OUT=smoke-out; INSTALL=0; JANK=1; SERIAL=""
while [ $# -gt 0 ]; do case "$1" in
  --install) INSTALL=1;; --out) OUT="$2"; shift;; --serial) SERIAL="$2"; shift;; --no-jank) JANK=0;;
  -h|--help) sed -n '2,12p' "$0"; exit 0;; *) echo "opzione sconosciuta: $1"; exit 2;; esac; shift; done

command -v adb >/dev/null || { echo "adb non trovato (installa platform-tools)."; exit 2; }
[ -z "$SERIAL" ] && SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
[ -n "$SERIAL" ] || { echo "Nessun dispositivo collegato (adb devices)."; exit 2; }
A() { adb -s "$SERIAL" "$@"; }
mkdir -p "$OUT"

top() { A shell dumpsys activity activities 2>/dev/null | grep topResumedActivity | head -1 | grep -o "$PKG\|com\.[a-z0-9.]*" | head -1; }
shot() { A exec-out screencap -p > "$OUT/$1.png"; }
fails=0; note() { echo "  $*"; }

if [ "$INSTALL" = 1 ]; then
  [ -f r1agents.apk ] || { echo "r1agents.apk non trovato (esegui tools/check.sh --copy)."; exit 2; }
  echo "== installazione =="; A install -r r1agents.apk | tail -1
fi

echo "== dispositivo $SERIAL =="
A shell input keyevent 224            # sveglia lo schermo
sleep 1
if A shell dumpsys window 2>/dev/null | grep -q "isKeyguardShowing=true"; then
  echo "Lo schermo è bloccato: sbloccalo e riprova."; exit 2
fi
A logcat -c
A shell am start -n "$ACT" >/dev/null 2>&1; sleep 3
if [ "$(top)" != "$PKG" ]; then echo "L'app non è in primo piano (c'è: '$(top)')."; exit 1; fi
note "app in primo piano"

echo "== schermate =="
for n in alarms alarm_new timers routines routine_new translator notes tools look settings space edit memory manage; do
  [ "$(top)" = "$PKG" ] || { note "FUORI: l'app non è più in primo piano, mi fermo"; fails=$((fails+1)); break; }
  A shell am start -n "$ACT" --es debug_screen "$n" >/dev/null 2>&1; sleep 2.2
  shot "screen_$n"; A shell input keyevent 4; sleep 0.8
  note "$n"
done

echo "== messaggi di prova (solo disegno, nessuna risposta a Hermes) =="
for k in approval clarify; do
  [ "$(top)" = "$PKG" ] || break
  A shell am start -n "$ACT" --es debug_req "$k" >/dev/null 2>&1; sleep 2; shot "req_$k"; A shell input keyevent 4; sleep 0.8; note "$k"
done
A shell am start -n "$ACT" --ez debug_md true >/dev/null 2>&1; sleep 2; shot "markdown"; A shell input keyevent 4; sleep 0.8; note "markdown"

if [ "$JANK" = 1 ] && [ "$(top)" = "$PKG" ]; then
  echo "== fluidità (12 transizioni, scorrimento degli agenti e menu) =="
  A shell am force-stop $PKG; A shell am start -n "$ACT" >/dev/null 2>&1; sleep 2.5   # riparte dalla home: nessun tocco finisce su un'altra schermata
  A shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3 4 5 6; do
    [ "$(top)" = "$PKG" ] || { note "FUORI durante la misura"; fails=$((fails+1)); break; }
    A shell input swipe 240 470 240 250 160; sleep 0.8; A shell input swipe 240 250 240 470 160; sleep 0.8   # scorre gli agenti
    A shell input tap 440 75; sleep 0.9; A shell input keyevent 4; sleep 0.9                                  # menu e chiudi
  done
  A shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile|Number Slow UI|Number Missed Vsync" | sed 's/^/  /'
fi

echo "== crash e memoria =="
if A logcat -d 2>&1 | grep -E "FATAL EXCEPTION|ANR in $PKG|Process: $PKG" | head -3 | grep -q .; then
  A logcat -d 2>&1 | grep -E -A6 "FATAL EXCEPTION" | head -20; note "CRASH trovato"; fails=$((fails+1))
else note "nessun crash"; fi
A shell dumpsys meminfo $PKG 2>/dev/null | grep -E "TOTAL PSS" | head -1 | sed 's/^ */  /'

# Foglio di contatto, se c'è Pillow (python3 -m pip install pillow)
python3 - "$OUT" <<'PY' 2>/dev/null && echo "foglio di contatto: $OUT/sheet.png" || true
import sys, glob
from PIL import Image
files = sorted(glob.glob(sys.argv[1] + "/*.png"))
files = [f for f in files if not f.endswith("sheet.png")]
if not files: raise SystemExit
w, h, cols = 240, 320, 6
rows = (len(files) + cols - 1) // cols
sheet = Image.new("RGB", (w * cols, h * rows), (30, 30, 30))
for i, f in enumerate(files): sheet.paste(Image.open(f).convert("RGB").resize((w, h)), ((i % cols) * w, (i // cols) * h))
sheet.save(sys.argv[1] + "/sheet.png")
PY

echo "== esito =="
if [ "$fails" -eq 0 ]; then echo "OK: schermate in $OUT/"; exit 0; else echo "$fails problema/i: vedi sopra."; exit 1; fi
