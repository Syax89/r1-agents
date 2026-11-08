#!/usr/bin/env python3
"""Prepara offline il manifest degli aggiornamenti leggendo versione e hash dall’APK reale."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('url', help='URL HTTPS pubblico dell’APK che verrà caricato')
parser.add_argument('output', type=Path)
parser.add_argument('--notes-file', type=Path)
args = parser.parse_args()
if not os.environ.get('JAVA_HOME') and Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home').is_dir():
    os.environ['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'
uri = urlparse(args.url)
if uri.scheme != 'https' or not uri.hostname or uri.username or uri.password or uri.fragment:
    parser.error('Per il rilascio serve un URL HTTPS, senza credenziali o frammenti.')
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
def tool(name):
    values = sorted(sdk.glob('build-tools/*/' + name))
    if not values:
        raise SystemExit('Strumento Android non trovato: ' + name)
    return str(values[-1])
subprocess.run([tool('apksigner'), 'verify', str(args.apk)], check=True)
badging = subprocess.run([tool('aapt'), 'dump', 'badging', str(args.apk)], capture_output=True, text=True, check=True).stdout
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
if not package or package[1] != 'dev.r1agents':
    raise SystemExit('Il manifest di rilascio accetta solo il pacchetto dev.r1agents, mai la copia QA.')
notes = args.notes_file.read_text(encoding='utf-8') if args.notes_file else ''
if len(notes) > 12000:
    raise SystemExit('Note troppo lunghe (massimo 12000 caratteri).')
digest = hashlib.sha256()
with args.apk.open('rb') as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
manifest = {'schema': 1, 'packageName': package[1], 'versionCode': int(package[2]), 'versionName': package[3], 'apkUrl': args.url, 'sha256': digest.hexdigest(), 'sizeBytes': args.apk.stat().st_size, 'notes': notes}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
print(args.output.resolve())
