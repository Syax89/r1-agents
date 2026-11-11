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
certs = subprocess.run([tool('apksigner'), 'verify', '--print-certs', str(args.apk)], capture_output=True, text=True, check=True).stdout
# L'impronta pubblica attesa sta in tools/release-cert.sha256: un APK firmato con un'altra chiave verrebbe rifiutato dall'Updater sul telefono.
expected_file = Path(__file__).with_name('release-cert.sha256')
if expected_file.is_file():
    expected = expected_file.read_text(encoding='utf-8').strip().lower()
    found = re.findall(r'certificate SHA-256 digest: ([0-9a-f]{64})', certs)
    if found != [expected]:
        raise SystemExit('Firma inattesa: l APK non e firmato con il certificato di rilascio (tools/release-cert.sha256).')
badging = subprocess.run([tool('aapt'), 'dump', 'badging', str(args.apk)], capture_output=True, text=True, check=True).stdout
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
if not package or package[1] != 'dev.r1agents':
    raise SystemExit('Il manifest di rilascio accetta solo il pacchetto dev.r1agents, mai la copia QA.')
if args.output.is_file():
    previous = json.loads(args.output.read_text(encoding='utf-8')).get('versionCode', 0)
    if int(package[2]) <= int(previous):
        raise SystemExit(f'versionCode {package[2]} non maggiore di quello gia pubblicato ({previous}).')
notes = args.notes_file.read_text(encoding='utf-8') if args.notes_file else ''
# Da un CHANGELOG si prende solo il paragrafo della versione che si pubblica ("0.5.35 (code 42): ...").
section = re.search(r'^' + re.escape(package[3]) + r' \(code \d+\):.*?(?=\n\s*\n|\Z)', notes, re.M | re.S)
if section:
    notes = section[0].strip()
elif args.notes_file and args.notes_file.name.upper().startswith('CHANGELOG'):
    raise SystemExit(f'Nel CHANGELOG manca la riga "{package[3]} (code {package[2]}): ...".')
if len(notes) > 12000:
    raise SystemExit('Note troppo lunghe (massimo 12000 caratteri).')
digest = hashlib.sha256()
with args.apk.open('rb') as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
signer = re.findall(r'certificate SHA-256 digest: ([0-9a-f]{64})', certs)
manifest = {'schema': 1, 'packageName': package[1], 'versionCode': int(package[2]), 'versionName': package[3], 'apkUrl': args.url, 'sha256': digest.hexdigest(), 'sizeBytes': args.apk.stat().st_size, 'notes': notes,
            'signerSha256': signer[0] if len(signer) == 1 else ''}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
print(args.output.resolve())
