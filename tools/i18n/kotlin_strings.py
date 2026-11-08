from pathlib import Path
import json, re

class Token:
    def __init__(self, start, end, value, args, expressions):
        self.start=start; self.end=end; self.value=value; self.args=args; self.expressions=expressions

def strings(code):
    result=[]
    def comment(i):
        if code.startswith('//',i):
            n=code.find('\n',i);return len(code) if n<0 else n
        if code.startswith('/*',i):
            depth=1;j=i+2
            while j<len(code) and depth:
                if code.startswith('/*',j):depth+=1;j+=2
                elif code.startswith('*/',j):depth-=1;j+=2
                else:j+=1
            return j
        return i
    def braces(i):
        depth=1;j=i
        while j<len(code):
            k=comment(j)
            if k!=j:j=k;continue
            if code[j]=='"':j=quoted(j);continue
            if code[j]=="'":
                j+=1
                while j<len(code):
                    if code[j]=='\\':j+=2
                    elif code[j]=="'":j+=1;break
                    else:j+=1
                continue
            if code[j]=='{':depth+=1
            elif code[j]=='}':
                depth-=1
                if depth==0:return j
            j+=1
        raise ValueError('Braces not closed')
    def quoted(i):
        raw=code.startswith('"""',i);j=i+(3 if raw else 1);out=[];args=[];exprs=[]
        while j<len(code):
            if raw and code.startswith('"""',j):end=j+3;break
            if not raw and code[j]=='"':end=j+1;break
            if not raw and code[j]=='\\':
                esc=code[j+1]
                if esc=='u':out.append(chr(int(code[j+2:j+6],16)));j+=6;continue
                out.append({'n':'\n','r':'\r','t':'\t','b':'\b','f':'\f'}.get(esc,esc));j+=2;continue
            if code.startswith('${',j):
                stop=braces(j+2);args.append(code[j+2:stop]);exprs.append((j+2,stop));out.append('%'+str(len(args))+'$s');j=stop+1;continue
            if code[j]=='$':
                match=re.match(r'\$([A-Za-z_][A-Za-z_0-9]*)',code[j:])
                if match:
                    args.append(match[1]);exprs.append((j+1,j+len(match[0])));out.append('%'+str(len(args))+'$s');j+=len(match[0]);continue
            out.append(code[j]);j+=1
        else:raise ValueError('String not closed')
        result.append(Token(i,end,''.join(out),args,exprs));return end
    i=0
    while i<len(code):
        j=comment(i)
        if j!=i:i=j;continue
        if code[i]=='"':i=quoted(i)
        elif code[i]=="'":
            i+=1
            while i<len(code):
                if code[i]=='\\':i+=2
                elif code[i]=="'":i+=1;break
                else:i+=1
        else:i+=1
    return sorted(result,key=lambda t:t.start)

if __name__=='__main__':
    data=[]
    for path in Path('app/src/main/java/dev/r1agents').glob('*.kt'):
        code=path.read_text()
        for token in strings(code):
            if not re.search('[A-Za-zÀ-ÿ]',token.value):continue
            data.append({'file':path.name,'line':code.count('\n',0,token.start)+1,'text':token.value,'args':token.args,'before':code[max(0,token.start-85):token.start],'after':code[token.end:token.end+45]})
    Path('work/i18n/inventory.json').write_text(json.dumps(data,ensure_ascii=False,indent=2))
    meaningful=[x for x in data if (' ' in x['text'] or re.search('[àèéìòù]',x['text'])) and len(x['text'])>3]
    unique={}
    for x in meaningful:unique.setdefault(x['text'],x)
    Path('work/i18n/phrases.json').write_text(json.dumps(list(unique.values()),ensure_ascii=False,indent=2))
    print('All tokens',len(data),'phrases',len(meaningful),'unique phrases',len(unique))
    for i,x in enumerate(list(unique.values())[:130]):print(i,x['file'],x['line'],json.dumps(x['text'],ensure_ascii=False))
