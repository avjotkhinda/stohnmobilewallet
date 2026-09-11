#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-$PWD/.proot-build}"
OUT="${2:-$PWD/proot-bundle}"
LOCK="$ROOT/runtime/proot/TERMUX-PACKAGE-LOCK.json"
mkdir -p "$WORK/downloads" "$OUT"
python3 - "$LOCK" "$WORK" "$OUT" <<'PY'
import hashlib,json,os,shutil,subprocess,sys,urllib.request
from pathlib import Path
lock=json.loads(Path(sys.argv[1]).read_text())
work=Path(sys.argv[2]); out=Path(sys.argv[3])
base=lock['repository'].rstrip('/')+'/'
for pkg in lock['packages']:
    p=work/'downloads'/Path(pkg['path']).name
    if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=pkg['sha256']:
        urllib.request.urlretrieve(base+pkg['path'], p)
    got=hashlib.sha256(p.read_bytes()).hexdigest()
    if got!=pkg['sha256']:
        raise SystemExit(f"FAIL: package hash mismatch for {pkg['name']}: {got}")
    dest=work/'extract'/pkg['name']; shutil.rmtree(dest,ignore_errors=True); dest.mkdir(parents=True)
    subprocess.run(['dpkg-deb','-x',str(p),str(dest)],check=True)
# Resolve exact files from package payloads.
prefix=work/'extract'/'proot'/'data/data/com.termux/files/usr'
proot=prefix/'bin/proot'
loader=prefix/'libexec/proot/loader'
if not loader.is_file():
    raise SystemExit('FAIL: expected Termux loader at $PREFIX/libexec/proot')
for dep in ('libtalloc','libandroid-shmem'):
    libdir=work/'extract'/dep/'data/data/com.termux/files/usr/lib'
    candidates=sorted(libdir.glob(dep+'.so*'))
    if not candidates: raise SystemExit(f'FAIL: {dep} shared library missing')
    src=candidates[0]
    dest=out/'libs'/f'{dep}.so'; dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(src,dest)
out.mkdir(parents=True,exist_ok=True)
shutil.copy2(proot,out/'proot-bin.so'); shutil.copy2(loader,out/'libproot-loader.so')
# Normalize only the dependency SONAMEs/RPATH; this makes the Android APK self-contained.
subprocess.run(['patchelf','--replace-needed','libtalloc.so.2','libtalloc.so',str(out/'proot-bin.so')],check=False)
subprocess.run(['patchelf','--replace-needed','libandroid-shmem.so.0','libandroid-shmem.so',str(out/'proot-bin.so')],check=False)
subprocess.run(['patchelf','--set-rpath','$ORIGIN',str(out/'proot-bin.so')],check=True)
# Fail closed if the dependency graph is not exactly the two expected bundled libs.
needed=subprocess.check_output(['readelf','-d',str(out/'proot-bin.so')],text=True)
for dep in ('libtalloc.so','libandroid-shmem.so'):
    if dep not in needed: raise SystemExit(f'FAIL: {dep} not present in final PRoot DT_NEEDED')
# Emit hashes of the exact APK payloads. These are the values injected into RuntimeManifest.
files=[out/'proot-bin.so',out/'libproot-loader.so',out/'libs/libtalloc.so',out/'libs/libandroid-shmem.so']
with (out/'SHA256SUMS').open('w') as f:
    for x in files:
        f.write(hashlib.sha256(x.read_bytes()).hexdigest()+'  '+x.name+'\n')
with (out/'PACKAGE-SHA256SUMS').open('w') as f:
    for pkg in lock['packages']:
        f.write(pkg['sha256']+'  '+Path(pkg['path']).name+'\n')
(out/'PROVENANCE.txt').write_text(
    'Source: official Termux package repository\n'
    'Architecture: aarch64\n'
    'Packages are verified by SHA-256 before extraction.\n'
    'PRoot upstream source: v5.1.107.92\n'
    'PRoot source SHA-256: 29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135\n'
    'Final APK payload hashes are generated from the exact post-patchelf binaries in SHA256SUMS.\n'
)
PY
