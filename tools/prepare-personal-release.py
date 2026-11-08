#!/usr/bin/env python3
"""Prepara due identità di firma e metadati offline; non pubblica né installa APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--personal-apk', type=Path, required=True)
parser.add_argument('--distribution-apk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--report', type=Path, required=True)
parser.add_argument('--notes', type=Path, default=Path('CHANGELOG.md'))
parser.add_argument('--apk-url', help='URL HTTPS reale del solo APK compatibile, quando disponibile')
args = parser.parse_args()
os.environ.setdefault('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
def tool(name):
    candidates = sorted(sdk.glob('build-tools/*/' + name))
    if not candidates: parser.error('Android build tool assente: ' + name)
    return str(candidates[-1])
def inspect(apk):
    badging = subprocess.check_output([tool('aapt2'), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not package or package[1] != 'dev.r1agents': parser.error('Serve un APK dev.r1agents, non QA')
    cert = subprocess.check_output([tool('apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    digest = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', cert)
    if not digest: parser.error('Firma non verificabile')
    return {'packageName': package[1], 'versionCode': int(package[2]), 'versionName': package[3], 'certificateSha256': digest[1], 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'sizeBytes': apk.stat().st_size}
personal = inspect(args.personal_apk); distribution = inspect(args.distribution_apk)
if (personal['versionCode'], personal['versionName']) != (distribution['versionCode'], distribution['versionName']): parser.error('Le due identità devono avere la stessa versione')
if personal['certificateSha256'] == distribution['certificateSha256']: parser.error('Gli APK devono distinguere firma attuale e firma privata di distribuzione')
args.output.mkdir(parents=True, exist_ok=True)
for name, apk, info in [('personal', args.personal_apk, personal), ('distribution', args.distribution_apk, distribution)]:
    dest = args.output / (name + '.apk'); shutil.copy2(apk, dest)
    manifest = {k: info[k] for k in ['packageName', 'versionCode', 'versionName', 'sha256', 'sizeBytes']}
    manifest.update(schema=1, apkUrl='__CONFIGURARE_URL_HTTPS_APK__', notes=args.notes.read_text()[:12000])
    (args.output / ('update-' + name + '.template.json')).write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
if args.apk_url:
    subprocess.run(['python3', str(Path(__file__).with_name('publish-update.py')), str(args.output / 'personal.apk'), args.apk_url, str(args.output / 'update-personal.json'), '--notes-file', str(args.notes)], check=True)
shutil.copy2(args.notes, args.output / 'CHANGELOG.md'); shutil.copy2(args.report, args.output / 'VERIFICATION.md')
(args.output / 'release.json').write_text(json.dumps({'personal': personal, 'distribution': distribution, 'published': False}, indent=2) + '\n')
(args.output / 'README.md').write_text('''# R1 Agents — rilascio personale

personal.apk aggiorna l’identità di sviluppo attuale, conservando i dati. distribution.apk usa la chiave privata definitiva e NON aggiorna direttamente una firma diversa. Non disinstallare l’app per cambiare firma senza un percorso di esportazione e ripristino concordato; chiavi API e password non sono nei backup.

release.json contiene versioni, hash, dimensioni e certificati verificati. VERIFICATION.md distingue prove riuscite e mancanti. I file *.template.json non sono manifest pubblicabili: manca l’URL reale HTTPS. Configurarlo con tools/publish-update.py e provare quel download prima di dichiarare pronto l’aggiornamento remoto.

Nessuna chiave o password privata è inclusa. Nulla è pubblicato automaticamente.
''')
print('Pacchetto offline verificato:', args.output)
