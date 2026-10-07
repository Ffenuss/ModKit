"""Exact-reference host patching. Never accepts or rewrites guest APKs."""
import argparse, hashlib, json, re, struct, zipfile, zlib
from pathlib import Path
from dex_inventory import Dex
REFERENCE_SHA256 = '251acbe2e3199b4a7b6454a495dcdeac0a479dfa00b14066f4615fed37bc6719'
HOST_PACKAGE = 'com.dualspace.multispace.androidx'
ADVERTISEMENT_METHODS = [
 ('Lcom/dualspace/multispace/ads/InsertAdHandlerActivity;', 'g', '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V'),
 ('Lcom/dualspace/multispace/ads/MainInsertAdHandlerActivity;', 'f', '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V'),
 ('Lcom/dualspace/multispace/ads/d/c;', 'c', '(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V'),
 ('Lcom/dualspace/multispace/ads/VappStateReceiver;', 'onReceive', '(Landroid/content/Context;Landroid/content/Intent;)V'),
]
ACTIVITIES = [
 ('Lcom/dualspace/multispace/ads/InsertAdHandlerActivity;', 'Landroid/app/Activity;'),
 ('Lcom/dualspace/multispace/ads/MainInsertAdHandlerActivity;', 'Landroidx/appcompat/app/AppCompatActivity;'),
]
def sha(data): return hashlib.sha256(data).hexdigest()
def verify_reference(path):
 data=Path(path).read_bytes()
 if sha(data)!=REFERENCE_SHA256: raise ValueError('Unsupported host APK: exact Launcher MLBB V2.3 reference required')
 with zipfile.ZipFile(path) as z:
  names=z.namelist()
  if len(set(names))!=len(names): raise ValueError('Duplicate ZIP entries')
  if 'classes4.dex' in names: raise ValueError('Host already contains classes4.dex; do not stack patches')
  if not z.read('lib/arm64-v8a/libCEZ.so').startswith(b'\x7fELF'): raise ValueError('Virtual engine is not ELF')
 return data

def patch_ads(data):
 dex=Dex(data);out=bytearray(data);report=[]
 targets=[(*x,'return') for x in ADVERTISEMENT_METHODS]+[(c,'onCreate','(Landroid/os/Bundle;)V',p) for c,p in ACTIVITIES]
 for cls,name,proto,kind in targets:
  found=[(flags,off) for idx,flags,off in dex.defined if dex.methods[idx]==(cls,name,proto)]
  if len(found)!=1 or not found[0][1]: raise ValueError('Missing/ambiguous ad method: '+cls+'->'+name+proto)
  flags,off=found[0];registers,ins,outs,tries,debug,size=struct.unpack_from('<HHHHII',data,off)
  old=data[off+16:off+16+size*2]
  if kind=='return': replacement=[0x000e]
  else:
   this_reg=registers-ins
   if flags&8 or ins!=2 or this_reg+1>15: raise ValueError('Unsupported Activity ABI')
   def index(c,n,p):
    v=[i for i,m in enumerate(dex.methods) if m==(c,n,p)]
    if len(v)!=1: raise ValueError('Missing method reference '+c+'->'+n+p)
    return v[0]
   super_idx=index(kind,'onCreate','(Landroid/os/Bundle;)V')
   finish_idx=index('Landroid/app/Activity;','finish','()V')
   prefix=[]
   if kind=='Landroidx/appcompat/app/AppCompatActivity;':
    # Preserve its original setTheme before AppCompat's onCreate.
    prefix=list(struct.unpack_from('<6H',old))
    if prefix[0]&255!=0x14 or prefix[3]&255!=0x6e:raise ValueError('Unexpected advertising Activity theme setup')
   replacement=prefix+[0x206f,super_idx,this_reg|((this_reg+1)<<4),0x106e,finish_idx,this_reg,0x000e]
  if len(replacement)>size: raise ValueError('Insufficient code space')
  # Remove references to old catch handlers and debug address ranges; retain code length.
  struct.pack_into('<H',out,off+6,0);struct.pack_into('<I',out,off+8,0)
  struct.pack_into('<'+'H'*size,out,off+16,*(replacement+[0]*(size-len(replacement))))
  report.append(dict(method=cls+'->'+name+proto,code_sha256=sha(old),offset=off,code_units=size))
 out[12:32]=hashlib.sha1(out[32:]).digest();struct.pack_into('<I',out,8,zlib.adler32(out[12:])&0xffffffff)
 return bytes(out),report

def patch_bootstrap(decoded):
 root=Path(decoded)
 def unique(suffix):
  matches=list(root.glob('smali*/'+suffix))
  if len(matches)!=1: raise ValueError('Missing/ambiguous host file '+suffix)
  return matches[0]
 app=unique('com/dualspace/multispace/application/MultiSpaceApplication.smali')
 launch=unique('com/dualspace/multispace/va/f.smali')
 helper='Lio/github/ffenuss/modkit/space/SpaceHost;'
 def modify(path,signature,change):
  s=path.read_text()
  if helper in s: raise ValueError('Bootstrap already present')
  pattern=r'(?ms)^\.method[^\n]*\b'+re.escape(signature)+r'\n(.*?)^\.end method'
  found=list(re.finditer(pattern,s))
  if len(found)!=1: raise ValueError('Missing/ambiguous '+signature)
  m=found[0];body=change(m.group(1));path.write_text(s[:m.start(1)]+body+s[m.end(1):])
 def app_change(body):
  needle='invoke-super {p0}, Landroid/app/Application;->onCreate()V'
  if body.count(needle)!=1: raise ValueError('Unexpected Application bootstrap')
  return body.replace(needle,needle+'\n\n    invoke-static {p0}, '+helper+'->start(Landroid/app/Application;)V')
 def launch_change(body):
  # No local registers required; the Runnable carries verified package/user fields.
  m=re.search(r'(?m)^\s*\.(?:locals|registers)\s+\d+[^\n]*\n',body)
  if not m: raise ValueError('Missing run register declaration')
  return body[:m.end()]+'\n    invoke-static {p0}, '+helper+'->beforeLaunch(Ljava/lang/Object;)V\n'+body[m.end():]
 modify(app,'onCreate()V',app_change);modify(launch,'run()V',launch_change)
 return sorted({('classes.dex' if p.parts[len(root.parts)]=='smali' else 'classes'+p.parts[len(root.parts)].removeprefix('smali_classes')+'.dex') for p in [app,launch]})

def old_signature(name):
 if not name.upper().startswith('META-INF/'):return False
 b=name.upper().rsplit('/',1)[-1]
 return b=='MANIFEST.MF' or b.endswith(('.SF','.RSA','.DSA','.EC'))

def verify_overlay_payload(payload):
 d=Dex(payload)
 expected={('Lio/github/ffenuss/modkit/space/SpaceHost;','start','(Landroid/app/Application;)V'),('Lio/github/ffenuss/modkit/space/SpaceHost;','beforeLaunch','(Ljava/lang/Object;)V')}
 defined={d.methods[i] for i,f,o in d.defined if o and f&9==9}
 if not expected<=defined:raise ValueError('Overlay bootstrap methods missing')

def pack(reference,rebuilt,dex_payload,output):
 verify_reference(reference)
 payload=Path(dex_payload).read_bytes()
 verify_overlay_payload(payload)
 with zipfile.ZipFile(reference) as src,zipfile.ZipFile(rebuilt) as mod,zipfile.ZipFile(output,'w') as dst:
  changed=set()
  for info in src.infolist():
   if old_signature(info.filename):continue
   data=src.read(info.filename)
   if info.filename=='classes2.dex':
    replacement=mod.read(info.filename)
    if replacement!=data:changed.add(info.filename)
    data=replacement
   dst.writestr(info,data)
  if changed!={'classes2.dex'}:raise ValueError('Unexpected changed host DEX set: '+str(changed))
  dst.writestr('classes4.dex',payload,compress_type=zipfile.ZIP_DEFLATED)
 with zipfile.ZipFile(reference) as src,zipfile.ZipFile(output) as dst:
  for info in src.infolist():
   n=info.filename
   if old_signature(n) or n=='classes2.dex':continue
   if dst.read(n)!=src.read(n):raise ValueError('Unrelated entry changed: '+n)
 return dict(reference_sha256=REFERENCE_SHA256,changed_dex=['classes2.dex'],added_dex=['classes4.dex'],manifest_unchanged=True,native_engine_unchanged=True,guest_apks_rewritten=0,device_verified=False)

if __name__=='__main__':
 p=argparse.ArgumentParser();sub=p.add_subparsers(dest='action',required=True)
 a=sub.add_parser('ads');a.add_argument('reference');a.add_argument('output');a.add_argument('--report',required=True)
 a=sub.add_parser('bootstrap');a.add_argument('decoded')
 a=sub.add_parser('pack');a.add_argument('reference');a.add_argument('rebuilt');a.add_argument('payload');a.add_argument('output');a.add_argument('--report',required=True)
 a=p.parse_args()
 if a.action=='ads':
  verify_reference(a.reference)
  with zipfile.ZipFile(a.reference) as src,zipfile.ZipFile(a.output,'w') as dst:
   for info in src.infolist():
    b=src.read(info.filename)
    if info.filename=='classes2.dex':b,report=patch_ads(b)
    dst.writestr(info,b)
  Path(a.report).write_text(json.dumps(report,indent=2))
 elif a.action=='bootstrap':print(json.dumps(patch_bootstrap(a.decoded)))
 else:Path(a.report).write_text(json.dumps(pack(a.reference,a.rebuilt,a.payload,a.output),indent=2))
