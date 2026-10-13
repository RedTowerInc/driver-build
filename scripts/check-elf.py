from pathlib import Path
import struct
for p in Path('app/src/main/jniLibs').rglob('*.so'):
 b=p.read_bytes();bits=b[4];endian='<' if b[5]==1 else '>';off=struct.unpack_from(endian+('Q' if bits==2 else 'I'),b,32 if bits==2 else 28)[0];size,num=struct.unpack_from(endian+'HH',b,54 if bits==2 else 42);aligns=[]
 for i in range(num):
  at=off+i*size
  if struct.unpack_from(endian+'I',b,at)[0]==1:aligns.append(struct.unpack_from(endian+('Q' if bits==2 else 'I'),b,at+(48 if bits==2 else 28))[0])
 assert aligns and all(a>=16384 for a in aligns), '16KB ELF alignment failed: '+str(p)
 print('ELF 16KB OK',p)
