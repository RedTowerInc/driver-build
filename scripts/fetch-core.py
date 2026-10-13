from pathlib import Path
import urllib.request,hashlib,tarfile,io
pairs=[('arm64-v8a','arm64','149569b5dbf01958e6598beb3a13078d6fc0e353ef06f4aff85f5f28bc23593b'),('armeabi-v7a','arm','0c0df4fbf85656896d5b604e9eb4e556c1a3f68dd615b0ece3f9f6bd5adab5d6'),('x86_64','amd64','a84a5aea86ad0588b76e23051e9e59405a7ed52358acfb9710cfaa055fe60e4a')]
for abi,arch,digest in pairs:
 cache=Path('build/native-deps')/f'sing-box-{arch}.tgz'
 if not cache.exists():
  req=urllib.request.Request(f'https://github.com/SagerNet/sing-box/releases/download/v1.14.3/sing-box-1.14.3-android-{arch}.tar.gz',headers={'User-Agent':'GeoHide-build'})
  cache.write_bytes(urllib.request.urlopen(req,timeout=120).read())
 data=cache.read_bytes()
 assert hashlib.sha256(data).hexdigest()==digest, 'Official sing-box checksum mismatch'
 with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
  members=[m for m in archive.getmembers() if m.isfile() and m.name.endswith('/sing-box')]
  assert len(members)==1
  dest=Path('app/src/main/jniLibs')/abi/'libsingbox.so';dest.parent.mkdir(parents=True,exist_ok=True)
  dest.write_bytes(archive.extractfile(members[0]).read());dest.chmod(0o755)
 print('sing-box 1.14.3 verified',abi)
