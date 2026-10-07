"""Check payload preservation after host build/signing. Not a gameplay test."""
import argparse, hashlib, json, zipfile
from pathlib import Path
from patch_host import verify_reference, old_signature, verify_overlay_payload, verify_engine_payload, ENGINE_ASSET, ENGINE_HASH

def verify(reference,output):
 verify_reference(reference)
 with zipfile.ZipFile(reference) as src,zipfile.ZipFile(output) as dst:
  names=dst.namelist()
  if len(names)!=len(set(names)):raise ValueError('Duplicate output entries')
  before={n for n in src.namelist() if not old_signature(n)}
  after={n for n in names if not old_signature(n)}
  if after!=before|{'classes4.dex',ENGINE_ASSET,ENGINE_HASH}:raise ValueError('Unexpected output entry set')
  if dst.read('classes2.dex')==src.read('classes2.dex'):raise ValueError('Host bootstrap not changed')
  verify_overlay_payload(dst.read('classes4.dex'))
  engine_hash=verify_engine_payload(dst.read(ENGINE_ASSET))
  if dst.read(ENGINE_HASH)!=engine_hash.encode('ascii'):raise ValueError('Engine SHA asset mismatch')
  for n in before-{'classes2.dex'}:
   if dst.read(n)!=src.read(n):raise ValueError('Unexpected payload change: '+n)
 return dict(sha256=hashlib.sha256(Path(output).read_bytes()).hexdigest(),engine_sha256=engine_hash,manifest_and_resources_unchanged=True,virtual_engine_unchanged=True,google_runtime_resources_preserved=True,guest_apks_modified=0,device_validation='pending')

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('reference');p.add_argument('output');a=p.parse_args()
 print(json.dumps(verify(a.reference,a.output),indent=2))
