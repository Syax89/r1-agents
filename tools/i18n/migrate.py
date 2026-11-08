"""Estrarre testi UI espliciti; non tradurre ID di protocollo, preferenze o contenuti dell’utente."""
from pathlib import Path
import json,re,hashlib,sys,html
sys.path.insert(0,str(Path(__file__).parent))
from kotlin_strings import strings
root=Path(__file__).resolve().parents[2]
catalog=json.loads((Path(__file__).parent/'catalog.json').read_text())
def key(value):return 'ui_'+hashlib.sha1(value.encode()).hexdigest()[:10]
def quoted(value):return json.dumps(value,ensure_ascii=False).replace('$','\\$')
def xml(value,formatted):
    # Quotes preserve leading/trailing whitespace; Android escaping is separate from XML escaping.
    value=value.replace('\\','\\\\').replace('"','\\"').replace("'","\\'").replace('\n','\\n').replace('\t','\\t')
    if formatted:value=re.sub(r'%(?!\d+\$s)', '%%', value)
    return html.escape('"'+value+'"',quote=False)
for lang in ('it','en'):
    folder=root/'app/src/main/res'/('values' if lang=='it' else 'values-en');folder.mkdir(exist_ok=True)
    lines=['<?xml version="1.0" encoding="utf-8"?>','<!-- Generated from tools/i18n/catalog.json. Keep both languages in sync. -->','<resources>']
    for source,translation in catalog.items():
        formatted=bool(re.search(r'%\d+\$s',source))
        # Keep only strings that actually change in English; identical brand names need no lookup.
        lines.append('    <string name="'+key(source)+'"'+('' if formatted else ' formatted="false"')+'>'+xml(source if lang=='it' else translation,formatted)+'</string>')
    days=['lun','mar','mer','gio','ven','sab','dom'] if lang=='it' else ['mon','tue','wed','thu','fri','sat','sun']
    lines.append('    <string-array name="weekdays_short">'+''.join('<item>'+d+'</item>' for d in days)+'</string-array>')
    languages=['inglese','spagnolo','francese','tedesco','portoghese','giapponese','cinese','russo','arabo','italiano']
    labels=languages if lang=='it' else [catalog[value] for value in languages]
    lines.append('    <string-array name="translation_language_names">'+''.join('<item>'+html.escape(value)+'</item>' for value in labels)+'</string-array>')
    lines.append('</resources>')
    (folder/'ui_strings.xml').write_text('\n'.join(lines)+'\n')
entries=[]
for source in catalog:
    entries.append('        UiEntry(R.string.'+key(source)+', '+quoted(source)+'),')
(root/'app/src/main/java/dev/r1agents/UiCatalog.kt').write_text('''package dev.r1agents

// Generated from tools/i18n/catalog.json. Only app-owned statuses/errors use this lookup.
internal data class UiEntry(val resource: Int, val source: String) {
    val pattern by lazy { uiTemplatePattern(source) }
    val prefix by lazy { source.take(uiPlaceholder.find(source)?.range?.first ?: source.length) }
    val suffix by lazy { uiPlaceholder.findAll(source).lastOrNull()?.let { source.substring(it.range.last + 1) }.orEmpty() }
}
internal object UiCatalog {
    val entries = listOf(
'''+ '\n'.join(entries)+'''
    )
    val exact = entries.filter { !it.source.contains(Regex("%[0-9]+\\\\\\$s")) }.associateBy { it.source }
    val formatted = entries.filter { it.source.contains(Regex("%[0-9]+\\\\\\$s")) }.sortedByDescending { it.source.replace(Regex("%[0-9]+\\\\\\$s"), "").length }
}
''')
# Avoid fragile dollar escaping in generated regex code: derive from one common placeholder regex instead.
p=root/'app/src/main/java/dev/r1agents/UiCatalog.kt'
s=p.read_text();s=s[:s.index('    val exact =')]+'''    val exact = entries.filter { !uiPlaceholder.containsMatchIn(it.source) }.associateBy { it.source }
    val formatted = entries.filter { uiPlaceholder.containsMatchIn(it.source) }.sortedByDescending { uiPlaceholder.replace(it.source, "").length }
}
''';p.write_text(s)
ui_files={'MainActivity.kt','Ext.kt','BuddyStage.kt','HermesTurnService.kt','HermesRuntime.kt','Services.kt','Voice.kt','Assist.kt','Sched.kt'}
log=[];skips=[]
for path in (root/'app/src/main/java/dev/r1agents').glob('*.kt'):
    if path.name not in ui_files:continue
    code=path.read_text();tokens=strings(code)
    def ctx(at):
        if path.name=='MainActivity.kt':return 'this@MainActivity'
        if path.name in ('Ext.kt','BuddyStage.kt'):return 'context'
        if path.name in ('HermesRuntime.kt','Assist.kt'):return 'ctx'
        if path.name=='Sched.kt':return 'ctx'
        if path.name=='HermesTurnService.kt':return 'this@HermesTurnService'
        if path.name=='Services.kt':return 'this@RoutineService' if at>code.index('class RoutineService') else 'this@RingService'
        if path.name=='Voice.kt':return 'c' if at>code.index('fun askMic(') else 'act'
        raise ValueError(path)
    def selected(t):
        if t.value not in catalog:return False
        before=code[max(0,t.start-220):t.start];after=code[t.end:t.end+50].lstrip()
        if path.name=='Ext.kt' and code.index('fun activityKind(')<=t.start<code.index('private fun rest('):return False
        if path.name=='Sched.kt' and t.start>code.index('    fun next('):return False
        if re.search(r'\b(?:prefs|p)\.(?:getString|putString|getStringSet|putStringSet)\([^)]*$',before):return False
        if re.search(r'\b(?:getString|optString|put|putString|contains|startsWith|endsWith|iconButton|IconView|chipOf|tile|bigRow)\(\s*$',before):return False
        if re.search(r'\b(?:icon|kind|tag)\s*=\s*$',before):return False
        if re.search(r'(?:==|!=)\s*$',before) or re.match(r'(?:in\s|->|==|!=)',after):return False
        if after.startswith('to') and t.value in {'meteo','nota','data','note'} and not re.match(r'to\s*\{',after):
            # Caption keys in icon maps must match translated action captions; machine option keys stay stable.
            if before.rfind('listOf(')>before.rfind('mapOf('):return False
        if re.search(r'\bto\s*$',before) and t.value in {'note','data'} and before.rfind('mapOf(')>before.rfind('listOf('):return False
        return True
    selected_tokens={t.start:t for t in tokens if selected(t)}
    def render_range(start,end):
        out=[];pos=start
        for t in tokens:
            if t.start<pos or t.start>=end or t.end>end or t.start not in selected_tokens:continue
            out.append(code[pos:t.start]);out.append(replacement(t));pos=t.end
        out.append(code[pos:end]);return ''.join(out)
    def replacement(t):
        args=[render_range(a,b) for a,b in t.expressions]
        # Exceptions and summaries are app-owned; names and user text are deliberately left verbatim.
        args=[('UiText.message('+ctx(t.start)+', '+a+')') if re.fullmatch(r'\w+\.message',a) else ('UiText.owned('+ctx(t.start)+', '+a+'.toString())') if a in {'sum','exportSummary'} else a for a in args]
        return 'UiText.text('+ctx(t.start)+', R.string.'+key(t.value)+('' if not args else ', '+', '.join(args))+')'
    rewritten=render_range(0,len(code))
    for t in tokens:
        if t.value in catalog:
            (log if t.start in selected_tokens else skips).append({'file':path.name,'line':code.count('\n',0,t.start)+1,'text':t.value,'before':code[max(0,t.start-70):t.start]})
    path.write_text(rewritten)
(root/'work/i18n/migrated.json').write_text(json.dumps(log,ensure_ascii=False,indent=2))
(root/'work/i18n/skipped.json').write_text(json.dumps(skips,ensure_ascii=False,indent=2))
print('UI tokens migrated:',len(log),'skipped as data/protocol:',len(skips),'resource entries:',len(catalog))
