#!/usr/bin/env python3
"""Misura aperture e ritorni su un'app già installata e sbloccata; non registra né invia messaggi."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--runs', type=int, default=3)
parser.add_argument('--package', default='dev.r1agents')
parser.add_argument('--adb', default=os.environ.get('ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb')))
parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'work/ui-profile')
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]

def call(*arguments):
    return subprocess.run(adb + list(arguments), check=True, capture_output=True, text=True).stdout

if 'mWakefulness=Awake' not in call('shell', 'dumpsys', 'power'):
    parser.exit(2, 'Riattiva e sblocca il Rabbit prima della prova.\n')
args.output.mkdir(parents=True, exist_ok=True)

def screen(name):
    call('shell', 'am', 'start', '-W', '-n', args.package + '/dev.r1agents.MainActivity', '--es', 'debug_screen', name)
    time.sleep(.65)

def back():
    call('shell', 'input', 'keyevent', '4')
    time.sleep(.65)

# Prerequisito: anche il PIN dell'app deve essere già sbloccato. Le richieste debug_screen rispettano il blocco.
for name in ['settings', 'tools', 'translator']:
    screen(name)
    back()
if args.runs < 1:
    parser.error('--runs deve essere positivo')
labels = ['Total frames rendered', 'Janky frames', '50th percentile', '90th percentile', '95th percentile', '99th percentile']
results = []
for sample in range(1, args.runs + 1):
    call('shell', 'dumpsys', 'gfxinfo', args.package, 'reset')
    for name in ['settings', 'tools', 'translator'] * 2:
        screen(name)
        back()
    report = call('shell', 'dumpsys', 'gfxinfo', args.package, 'framestats')
    (args.output / f'gfxinfo-{sample}.txt').write_text(report)
    metrics = {}
    for label in labels:
        match = re.search(r'^' + re.escape(label) + r': (.+)$', report, re.MULTILINE)
        if match: metrics[label] = match.group(1)
    if not metrics or metrics.get('Total frames rendered') == '0':
        parser.exit(3, 'Nessun frame misurato: controlla sblocco e schermata aperta.\n')
    results.append(metrics)
    print('Campione', sample, json.dumps(metrics), flush=True)
(args.output / 'summary.json').write_text(json.dumps({'package': args.package, 'case': 'navigation', 'runs': results}, indent=2))
