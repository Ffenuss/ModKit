import json, os, struct, sys, tempfile, unittest, zipfile, hashlib, zlib
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from patch_host import patch_bootstrap, patch_ads, verify_reference, ADVERTISEMENT_METHODS, ACTIVITIES
from dex_inventory import Dex
ROOT=Path(__file__).resolve().parents[1]

class BootstrapTests(unittest.TestCase):
 def fixtures(self,root):
  a=root/'smali_classes2/com/dualspace/multispace/application/MultiSpaceApplication.smali'
  r=root/'smali_classes2/com/dualspace/multispace/va/f.smali'
  a.parent.mkdir(parents=True);r.parent.mkdir(parents=True)
  a.write_text('.class public Lcom/dualspace/multispace/application/MultiSpaceApplication;\n.method public onCreate()V\n .locals 7\n invoke-super {p0}, Landroid/app/Application;->onCreate()V\n return-void\n.end method\n')
  r.write_text('.class public Lcom/dualspace/multispace/va/f;\n.method public run()V\n .locals 6\n return-void\n.end method\n')
  return a,r
 def test_bootstrap_preserves_original_logic_and_registers(self):
  with tempfile.TemporaryDirectory() as td:
   a,r=self.fixtures(Path(td));self.assertEqual(patch_bootstrap(td),['classes2.dex'])
   self.assertIn('invoke-super {p0}',a.read_text());self.assertIn('.locals 7',a.read_text())
   self.assertIn('SpaceHost;->start',a.read_text());self.assertIn('SpaceHost;->beforeLaunch',r.read_text())
   self.assertEqual(r.read_text().count('return-void'),1)
 def test_rejects_duplicate_patch(self):
  with tempfile.TemporaryDirectory() as td:
   self.fixtures(Path(td));patch_bootstrap(td)
   with self.assertRaises(ValueError):patch_bootstrap(td)
 def test_rejects_unknown_abi(self):
  with tempfile.TemporaryDirectory() as td:
   a,r=self.fixtures(Path(td));a.write_text(a.read_text().replace('invoke-super','invoke-virtual'))
   with self.assertRaises(ValueError):patch_bootstrap(td)
 def test_rejects_wrong_apk_before_opening_archive(self):
  with tempfile.TemporaryDirectory() as td:
   p=Path(td)/'wrong.apk';p.write_bytes(b'guest or unknown host')
   with self.assertRaises(ValueError):verify_reference(p)

class ReferenceTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.reference=os.environ.get('MODKIT_SPACE_REFERENCE')
  if not cls.reference: raise unittest.SkipTest('Exact host APK not supplied; reference tests require MODKIT_SPACE_REFERENCE')
  verify_reference(cls.reference)
  with zipfile.ZipFile(cls.reference) as z:cls.original=z.read('classes2.dex')
 def test_ads_patch_is_limited_to_six_proven_methods(self):
  b,report=patch_ads(self.original);self.assertEqual(len(report),6)
  d=Dex(b)
  for c,n,p in ADVERTISEMENT_METHODS:
   off=[o for i,f,o in d.defined if d.methods[i]==(c,n,p)][0]
   self.assertEqual(d.code(off)[0],0x000e)
  self.assertEqual(b[12:32],hashlib.sha1(b[32:]).digest())
  self.assertEqual(struct.unpack_from('<I',b,8)[0],zlib.adler32(b[12:])&0xffffffff)
  original=Dex(self.original)
  selected=set(ADVERTISEMENT_METHODS)|{(c,'onCreate','(Landroid/os/Bundle;)V') for c,_ in ACTIVITIES}
  for i,f,o in original.defined:
   if o and original.methods[i] not in selected:self.assertEqual(original.code(o),d.code(o),original.methods[i])
 def test_google_and_virtual_launch_abi_remain_identical(self):
  b,_=patch_ads(self.original);old=Dex(self.original);new=Dex(b)
  signatures=[('Lcom/lody/virtual/client/core/VirtualCore;','ck','(Ljava/lang/String;I)Lcom/lody/virtual/remote/InstalledAppInfo;'),('Lcom/lody/virtual/remote/InstalledAppInfo;','f','(I)Landroid/content/pm/ApplicationInfo;'),('Lcom/lody/virtual/client/core/VirtualCore;','cp','(ILjava/lang/String;)Z'),('Lcom/lody/virtual/client/h/i;','as','(ILjava/lang/String;Z)Z'),('Lcom/lody/virtual/client/core/VirtualCore;','i','()Lcom/lody/virtual/client/core/VirtualCore;')]
  for sig in signatures:
   found=[o for i,f,o in old.defined if old.methods[i]==sig];self.assertEqual(len(found),1);self.assertEqual(old.code(found[0]),new.code(found[0]))

if __name__=='__main__':unittest.main()
