#!/usr/bin/env python3
"""Credential-independent TG Wear repository sanity checks."""
from pathlib import Path
from zipfile import ZipFile
import json, re, sys
root=Path(__file__).resolve().parent.parent
checks=[]
def check(label, condition, detail=''):
 checks.append((label,bool(condition),detail))
vela=root/'vela/tgwear-quickapp'
android=root/'android/Nagram'
try:
 manifest=json.loads((vela/'src/manifest.json').read_text())
except Exception as e:
 manifest={}; check('Vela manifest parses',False,str(e))
else: check('Vela manifest parses',True)
gradle=(android/'TMessagesProj/build.gradle').read_text(errors='replace')
check('Android package matches Vela package',bool(re.search(r'defaultConfig\.applicationId\s*=\s*"'+re.escape(manifest.get('package',''))+r'"',gradle)),manifest.get('package',''))
check('Vela version is 0.1.4/code 5',manifest.get('versionName')=='0.1.4' and manifest.get('versionCode')==5)
check('Android TGW/2 transport exists',(android/'TMessagesProj/src/main/java/org/telegram/tgwear/ReliableWearConnection.java').is_file())
check('Vela TGW/2 framing exists',(vela/'src/utils/protocol.js').is_file())
check('Vela protocol/store test suite exists',(vela/'test/protocol.test.js').is_file() and (vela/'test/store.test.js').is_file())
protocol=(vela/'src/utils/protocol.js').read_text(errors='replace')
check('TGW/2 marker and bounded framing present','__tgWearProtocolV2' in protocol and 'MAX_LOGICAL_BYTES' in protocol and 'commitAck' in protocol)
check('Vela wire protocol does not implement SimpleFetch frames',not re.search(r'SF_(HANDSHAKE|REQUEST|RESPONSE|PING)',protocol))
rpk=root/'artifacts/vela/com.hrk.tgwear.debug.0.1.4.rpk'
try:
 with ZipFile(rpk) as z:
  rpk_manifest=json.loads(z.read('manifest.json'))
  js='\n'.join(z.read(n).decode('utf-8','replace') for n in z.namelist() if n.endswith('.js'))
 check('versioned RPK includes TGW/2',rpk_manifest.get('versionName')=='0.1.4' and '__tgWearProtocolV2' in js)
except Exception as e: check('versioned RPK opens and contains TGW/2',False,str(e))
forbidden_ext={'.jks','.keystore','.pem','.p12','.apk','.aab'}
forbidden_names={'local.properties','google-services.json'}
violations=[]
for p in root.rglob('*'):
 if not p.is_file(): continue
 if p.name in forbidden_names or p.suffix.lower() in forbidden_ext: violations.append(p.relative_to(root).as_posix())
check('no local signing/service credentials or APK baselines',not violations,', '.join(violations))
for label,ok,detail in checks: print(('PASS' if ok else 'FAIL')+'  '+label+((' ['+detail+']') if detail else ''))
print(f'{sum(ok for _,ok,_ in checks)}/{len(checks)} repository checks passed')
sys.exit(0 if all(ok for _,ok,_ in checks) else 1)
