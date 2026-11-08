#!/usr/bin/env python3
"""Misura prestazioni per scenari nominati (gfxinfo framestats, avvio a freddo, memoria, thread).

Richiede il Rabbit sveglio, sbloccato e senza PIN app. Non invia messaggi né cambia impostazioni.
Esempio: tools/perf.py --serial 919129A5I160003672A7 --scenario idle,carousel,settings,cold_start --runs 3
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='dev.r1agents')
parser.add_argument('--runs', type=int, default=3)
parser.add_argument('--scenario', default='idle,nav,carousel,settings,cold_start,mem')
parser.add_argument('--idle-seconds', type=int, default=30)
parser.add_argument('--tag', default='')
parser.add_argument('--adb', default=os.environ.get('ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb')))
parser.add_argument('--output', type=Path, default=ROOT / 'work/perf')
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]
ACT = args.package + '/dev.r1agents.MainActivity'


def sh(*a, check=True):
    return subprocess.run(adb + ['shell'] + list(a), check=check, capture_output=True, text=True).stdout


def screen(name, wait=.7):
    sh('am', 'start', '-W', '-n', ACT, '--es', 'debug_screen', name)
    time.sleep(wait)


def back(wait=.7):
    sh('input', 'keyevent', '4')
    time.sleep(wait)


def swipe(x1, y1, x2, y2, ms=250):
    sh('input', 'swipe', str(x1), str(y1), str(x2), str(y2), str(ms))


def reset():
    sh('dumpsys', 'gfxinfo', args.package, 'reset')


def frames():
    """Legge framestats: durata totale per frame (ms) dai dati grezzi e riepilogo gfxinfo."""
    rep = sh('dumpsys', 'gfxinfo', args.package, 'framestats')
    durs = []
    on = False
    for line in rep.splitlines():
        if line.startswith('---PROFILEDATA---'):
            on = not on
            continue
        if on and line and line[0].isdigit():
            p = line.rstrip(',').split(',')
            try:
                if p[0] == '0':  # flags 0 = frame normale
                    durs.append((int(p[18]) - int(p[2])) / 1e6)  # colonna 18 FrameCompleted - colonna 2 IntendedVsync
            except (IndexError, ValueError):
                pass
    def pct(q):
        return round(sorted(durs)[min(len(durs) - 1, int(len(durs) * q))], 1) if durs else None
    summ = {}
    for label in ['Total frames rendered', 'Janky frames']:
        m = re.search(r'^' + re.escape(label) + r': (\d+)(?: \(([\d.]+)%\))?', rep, re.M)
        if m:
            summ[label] = int(m.group(1))
            if m.group(2):
                summ['jank_pct'] = float(m.group(2))
    summ.update(p50=pct(.5), p95=pct(.95), p99=pct(.99), max=round(max(durs), 1) if durs else None,
                over32=sum(d > 32 for d in durs), over100=sum(d > 100 for d in durs))
    return summ


def run_frames(action, label):
    out = []
    for _ in range(args.runs):
        screen('home')
        time.sleep(.5)
        reset()
        action()
        out.append(frames())
    return agg(label, out)


def agg(label, runs):
    keys = ['Total frames rendered', 'jank_pct', 'p50', 'p95', 'p99', 'max', 'over32', 'over100']
    med = {k: statistics.median([r[k] for r in runs if r.get(k) is not None] or [0]) for k in keys}
    return {'scenario': label, 'median': med, 'runs': runs}


def sc_idle():
    out = []
    for _ in range(args.runs):
        screen('home')
        reset()
        time.sleep(args.idle_seconds)
        out.append(frames())
    return agg('idle', out)


def sc_nav():
    def go():
        for n in ['settings', 'tools', 'translator'] * 2:
            screen(n)
            back()
    return run_frames(go, 'nav')


def sc_carousel():
    def go():
        for _ in range(4):
            swipe(380, 400, 100, 400, 200)
            time.sleep(.5)
        for _ in range(4):
            swipe(100, 400, 380, 400, 200)
            time.sleep(.5)
        swipe(380, 400, 100, 400, 60)  # veloce
        time.sleep(1)
    return run_frames(go, 'carousel')


def sc_settings():
    def go():
        screen('settings')
        for _ in range(3):
            swipe(240, 520, 240, 120, 250)
            time.sleep(.4)
            swipe(240, 120, 240, 520, 250)
            time.sleep(.4)
        swipe(240, 560, 240, 80, 70)  # fling
        time.sleep(1)
        back()
    return run_frames(go, 'settings_scroll')


def sc_drawer():
    def go():
        screen('apps')
        for _ in range(3):
            swipe(240, 520, 240, 120, 250)
            time.sleep(.4)
        back()
    return run_frames(go, 'drawer')


def sc_cold():
    res = []
    for _ in range(args.runs):
        sh('am', 'force-stop', args.package)
        time.sleep(1.5)
        o = sh('am', 'start', '-W', '-n', ACT)
        m = re.search(r'TotalTime: (\d+)', o)
        w = re.search(r'WaitTime: (\d+)', o)
        res.append({'TotalTime': int(m.group(1)) if m else None, 'WaitTime': int(w.group(1)) if w else None})
        time.sleep(2)
    tt = [r['TotalTime'] for r in res if r['TotalTime']]
    return {'scenario': 'cold_start', 'median_ms': statistics.median(tt) if tt else None, 'runs': res}


def sc_mem():
    screen('home')
    time.sleep(2)
    info = sh('dumpsys', 'meminfo', args.package)
    pick = lambda pat: (re.search(pat, info) or [None, None])[1]
    pid = sh('pidof', args.package).strip()
    threads = len(sh('ls', f'/proc/{pid}/task', check=False).split()) if pid else None
    return {'scenario': 'mem', 'pss_kb': pick(r'TOTAL PSS:\s+(\d+)') or pick(r'TOTAL\s+(\d+)'),
            'java_heap_kb': pick(r'Java Heap:\s+(\d+)'), 'graphics_kb': pick(r'Graphics:\s+(\d+)'),
            'threads': threads, 'views': pick(r'Views:\s+(\d+)'), 'activities': pick(r'Activities:\s+(\d+)')}


def sc_transitions():
    def go():
        for n in ['settings', 'tools', 'translator', 'apps', 'controls', 'notes'] * 2:
            screen(n, .9)
            back(.9)
    return run_frames(go, 'transitions')


SCEN = {'transitions': sc_transitions, 'idle': sc_idle, 'nav': sc_nav, 'carousel': sc_carousel, 'settings': sc_settings,
        'drawer': sc_drawer, 'cold_start': sc_cold, 'mem': sc_mem}

if 'mWakefulness=Awake' not in sh('dumpsys', 'power'):
    parser.exit(2, 'Riattiva e sblocca il Rabbit prima della prova.\n')
if args.runs < 1:
    parser.error('--runs deve essere positivo')
stamp = datetime.now().strftime('%Y%m%d-%H%M%S') + (('-' + args.tag) if args.tag else '')
out = args.output / stamp
out.mkdir(parents=True, exist_ok=True)
results = []
for name in args.scenario.split(','):
    name = name.strip()
    if name not in SCEN:
        parser.error('scenario sconosciuto: ' + name + ' (' + ', '.join(SCEN) + ')')
    r = SCEN[name]()
    results.append(r)
    print(json.dumps(r.get('median', r) if 'median' in r else {k: v for k, v in r.items() if k != 'runs'}), name, flush=True)
(out / 'perf.json').write_text(json.dumps({'package': args.package, 'serial': args.serial, 'results': results}, indent=2))
print('scritto', out / 'perf.json')
