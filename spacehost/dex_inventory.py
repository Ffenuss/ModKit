import struct, hashlib, zlib
class Dex:
 def __init__(self,b):
  if len(b)<112 or b[:4]!=b'dex\n' or b[7]!=0:raise ValueError('Invalid DEX header')
  if struct.unpack_from('<I',b,32)[0]!=len(b) or struct.unpack_from('<I',b,40)[0]!=0x12345678:raise ValueError('Invalid DEX size/endianness')
  if b[12:32]!=hashlib.sha1(b[32:]).digest() or struct.unpack_from('<I',b,8)[0]!=zlib.adler32(b[12:])&0xffffffff:raise ValueError('Invalid DEX checksum')
  self.b=b
  self.strings=[]
  n,o=struct.unpack_from('<II',b,56)
  for i in range(n):
   p=struct.unpack_from('<I',b,o+i*4)[0];_,p=self.uleb(p);q=b.index(0,p);self.strings.append(b[p:q].decode('utf8','replace'))
  n,o=struct.unpack_from('<II',b,64);self.types=[self.strings[struct.unpack_from('<I',b,o+4*i)[0]] for i in range(n)]
  n,o=struct.unpack_from('<II',b,72);self.protos=[]
  for i in range(n):
   _,r,p=struct.unpack_from('<III',b,o+12*i);args=[]
   if p:
    c=struct.unpack_from('<I',b,p)[0];args=[self.types[struct.unpack_from('<H',b,p+4+2*j)[0]] for j in range(c)]
   self.protos.append('('+''.join(args)+')'+self.types[r])
  n,o=struct.unpack_from('<II',b,88);self.methods=[]
  for i in range(n):
   c,p,s=struct.unpack_from('<HHI',b,o+8*i);self.methods.append((self.types[c],self.strings[s],self.protos[p]))
  self.classes=set();self.defined=[];n,o=struct.unpack_from('<II',b,96)
  for i in range(n):
   ci,_,_,_,_,_,data,_=struct.unpack_from('<8I',b,o+32*i)
   self.classes.add(self.types[ci])
   if not data:continue
   a,p=self.uleb(data);bc,p=self.uleb(p);d,p=self.uleb(p);v,p=self.uleb(p)
   for _ in range(a+bc):_,p=self.uleb(p);_,p=self.uleb(p)
   for count in [d,v]:
    idx=0
    for _ in range(count):
     delta,p=self.uleb(p);idx+=delta;flags,p=self.uleb(p);code,p=self.uleb(p);self.defined.append((idx,flags,code))
 def uleb(self,p):
  v=0;s=0
  while True:
   x=self.b[p];p+=1;v|=(x&127)<<s
   if not x&128:return v,p
   s+=7
 def code(self,p):
  if not p:return []
  n=struct.unpack_from('<I',self.b,p+12)[0];return list(struct.unpack_from('<'+str(n)+'H',self.b,p+16))
