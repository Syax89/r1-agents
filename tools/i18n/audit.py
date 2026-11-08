#!/usr/bin/env python3
"""Controlla catalogo bilingue e firme dei placeholder senza accedere a dati utente."""
from pathlib import Path
import hashlib,json,re,xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[2]
catalog=json.loads((Path(__file__).parent/'catalog.json').read_text())
placeholder=re.compile(r'%([0-9]+)\$s')
for source,english in catalog.items():
    assert sorted(placeholder.findall(source)) == sorted(placeholder.findall(english)), ('placeholder mismatch',source,english)
    assert english.strip(), ('empty translation',source)
for folder in ['values','values-en']:
    xml=E.parse(root/'app/src/main/res'/folder/'ui_strings.xml').getroot()
    names={element.attrib['name'] for element in xml.findall('string')}
    expected={'ui_'+hashlib.sha1(source.encode()).hexdigest()[:10] for source in catalog}
    assert names==expected, ('catalog/resource mismatch',folder)
    assert len(xml.find("string-array[@name='translation_language_names']"))==10
    assert len(xml.find("string-array[@name='weekdays_short']"))==7
print(f'Catalogo verificato: {len(catalog)} testi, due lingue, placeholder coerenti e liste localizzate.')
