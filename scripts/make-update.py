"""Generate exact signed-APK copy/literal patches and GitHub update metadata."""
import argparse, gzip, hashlib, json, struct
from pathlib import Path
BLOCK = 1024
MAX = 64 * 1024 * 1024

def digest(data): return hashlib.sha256(data).digest()
def make_patch(base, target):
    if not 0 < len(target) <= MAX: raise ValueError('target size')
    index = {}
    for p in range(0, len(base) - BLOCK + 1, BLOCK):
        index.setdefault(digest(base[p:p+BLOCK]), p)
    output = bytearray(b'MMD1' + struct.pack('>q', len(target)) + digest(base) + digest(target))
    pos = literal = 0
    while pos + BLOCK <= len(target):
        old = index.get(digest(target[pos:pos+BLOCK]))
        if old is None or base[old:old+BLOCK] != target[pos:pos+BLOCK]:
            pos += 1
            continue
        if pos > literal:
            output += b'\x02' + struct.pack('>i', pos-literal) + target[literal:pos]
        length = BLOCK
        while old+length < len(base) and pos+length < len(target) and base[old+length] == target[pos+length]: length += 1
        output += b'\x01' + struct.pack('>qi', old, length)
        pos += length
        literal = pos
    if literal < len(target): output += b'\x02' + struct.pack('>i', len(target)-literal) + target[literal:]
    output += b'\x00'
    return gzip.compress(bytes(output), compresslevel=9, mtime=0)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--target', required=True, type=Path)
    parser.add_argument('--base', action='append', default=[], type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', required=True, type=int)
    parser.add_argument('--out', type=Path, default=Path('dist'))
    args=parser.parse_args(); args.out.mkdir(parents=True, exist_ok=True)
    target=args.target.read_bytes()
    url='https://github.com/Freeze7y/MimiAutoComboLauncher/releases/download/v'+args.version+'/'
    def asset(name, data): return dict(url=url+name, size=len(data), sha256=digest(data).hex())
    manifest=dict(format=1, version=args.version, versionCode=args.code, apk=asset(args.target.name,target), patches=[])
    for path in args.base:
        base=path.read_bytes(); patch=make_patch(base,target)
        if len(patch) >= len(target): continue
        name='delta-'+digest(base).hex()+'.mmd'
        (args.out/name).write_bytes(patch)
        manifest['patches'].append(dict(baseSha256=digest(base).hex(), **asset(name,patch)))
        print(f'{path.name}: {len(patch)} / {len(target)} bytes ({len(patch)/len(target):.1%})')
    (args.out/'update.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
if __name__ == '__main__': main()
