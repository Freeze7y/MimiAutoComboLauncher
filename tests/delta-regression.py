"""Cross-language tests: Python patch publisher -> production Java patch reader."""
from pathlib import Path
import importlib.util, subprocess, tempfile, gzip, struct, random, hashlib, argparse
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('publisher',ROOT/'scripts/make-update.py')
publisher=importlib.util.module_from_spec(spec);spec.loader.exec_module(publisher)
JAVA='''package dev.local.nativemacrohelper;
import java.io.*;
public class PatchTest {
 public static void main(String[] args) throws Exception {
  try { DeltaPatch.apply(new File(args[0]),new File(args[1]),new File(args[2]));
   if(args[3].equals("fail"))throw new AssertionError("accepted invalid patch");
  } catch (IOException e) { if(!args[3].equals("fail"))throw e;
   if(new File(args[2]).exists())throw new AssertionError("failed output retained"); }
 }
}'''
parser=argparse.ArgumentParser()
parser.add_argument('--base', action='append', type=Path)
parser.add_argument('--target', type=Path, default=ROOT/'dist/MimiAutoComboLauncher-1.4.0.apk')
args=parser.parse_args()
count=0
with tempfile.TemporaryDirectory(dir=ROOT/'build',prefix='delta-tests-') as temp:
 d=Path(temp);src=d/'PatchTest.java';src.write_text(JAVA,encoding='utf-8')
 subprocess.run(['javac','-encoding','UTF-8','-d',str(d),str(ROOT/'app/src/main/java/dev/local/nativemacrohelper/DeltaPatch.java'),str(src)],check=True)
 def run(base,patch,target=None):
  global count
  (d/'old.apk').write_bytes(base);(d/'patch').write_bytes(patch);(d/'new.apk').unlink(missing_ok=True)
  subprocess.run(['java','-cp',str(d),'dev.local.nativemacrohelper.PatchTest',str(d/'old.apk'),str(d/'patch'),str(d/'new.apk'),'fail' if target is None else 'ok'],check=True)
  if target is not None: assert (d/'new.apk').read_bytes()==target
  count+=1
 rng=random.Random(77);base=rng.randbytes(24000)
 for target in [base,b'x',b'prefix'+base,base[:4500]+b'changed'*90+base[4800:],rng.randbytes(15000),base[9000:]+base[:9000]]:
  run(base,publisher.make_patch(base,target),target)
 target=b'new'+base;valid=publisher.make_patch(base,target)
 run(b'wrong base',valid)
 run(base,valid[:-12])
 raw=bytearray(gzip.decompress(valid));raw[44]^=1;run(base,gzip.compress(raw))
 header=b'MMD1'+struct.pack('>q',10)+hashlib.sha256(base).digest()+bytes(32)
 for commands in [b'\x01'+struct.pack('>qi',-1,10)+b'\x00',b'\x02'+struct.pack('>i',11)+bytes(11)+b'\x00',b'\xff',b'\x00']:
  run(base,gzip.compress(header+commands))
 run(base,gzip.compress(b'MMD1'+struct.pack('>q',publisher.MAX+1)+bytes(64)))
 for old in args.base or [ROOT/'dist/MimiAutoComboLauncher-1.3.0.apk']:
  new=args.target
  if old.exists() and new.exists():
   before=old.read_bytes();after=new.read_bytes();patch=publisher.make_patch(before,after)
   run(before,patch,after)
   print(f'Real signed APK: {len(patch):,} patch bytes / {len(after):,} full bytes ({len(patch)/len(after):.2%})')
print(f'PASS: {count} delta cases (exact output, wrong base, truncation, hash, bounds, invalid commands)')
