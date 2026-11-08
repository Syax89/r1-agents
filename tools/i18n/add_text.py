#!/usr/bin/env python3
"""Aggiunge un testo dell'app (italiano + inglese) a catalogo, risorse e UiCatalog.kt, e stampa il nome della risorsa.
Uso: tools/i18n/add_text.py "testo italiano" "english text"   (i segnaposto sono %1$s, %2$s...)
Poi: python3 tools/i18n/audit.py"""
import hashlib, json, re, sys
from pathlib import Path
root = Path(__file__).resolve().parents[2]
it, en = sys.argv[1], sys.argv[2]
name = 'ui_' + hashlib.sha1(it.encode()).hexdigest()[:10]
catalog_path = Path(__file__).parent / 'catalog.json'
catalog = json.loads(catalog_path.read_text())
if it in catalog:
    print(name + ' (gia presente)'); sys.exit(0)
catalog[it] = en
catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
formatted = '' if re.search(r'%[0-9]+\$s', it) else ' formatted="false"'
def xml_text(s): return '"' + s.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n') + '"'
for folder, text in (('values', it), ('values-en', en)):
    p = root / 'app/src/main/res' / folder / 'ui_strings.xml'
    s = p.read_text()
    line = f'    <string name="{name}"{formatted}>{xml_text(text)}</string>\n'
    at = s.index('    <string-array')
    p.write_text(s[:at] + line + s[at:])
k = root / 'app/src/main/java/dev/r1agents/UiCatalog.kt'
s = k.read_text()
kt = it.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$').replace('\n', '\\n')
at = s.index('    )\n    val exact')
k.write_text(s[:at] + f'        UiEntry(R.string.{name}, "{kt}"),\n' + s[at:])
print(name)
