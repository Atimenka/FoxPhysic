#!/usr/bin/env python3
"""Collect notices from the locked Cargo registry; call after dependencies build.
Uses only the Python 3.11+ standard library. No license claims are guessed.
"""
import os
from pathlib import Path
import shutil
import tomllib

root = Path(__file__).resolve().parents[1]
home = Path(os.environ.get('CARGO_HOME', Path.home()/'.cargo'))
lock = tomllib.loads((root/'native/Cargo.lock').read_text())
lines = ['# Third-party notices', '',
         'The project itself is MIT. The following is generated from the locked Cargo dependencies. '
         'Original notices are copied under `licenses/crates/`. Declared licenses are not inferred.', '',
         '| Crate | Version | Declared license | Repository |', '|---|---|---|---|']
missing = []
for dep in sorted(lock['package'], key=lambda p:(p['name'],p['version'])):
    if 'source' not in dep:
        continue
    name, version = dep['name'], dep['version']
    dirs = list((home/'registry/src').glob(f'*/{name}-{version}'))
    if not dirs:
        missing.append(f'{name}-{version}')
        continue
    crate = dirs[0]
    data = tomllib.loads((crate/'Cargo.toml').read_text())['package']
    license_name = data.get('license', 'See upstream license-file')
    repository = data.get('repository','')
    lines.append(f'| {name} | {version} | {license_name} | {repository} |')
    dest = root/'licenses/crates'/f'{name}-{version}'
    candidates = set()
    for pattern in ('LICENSE*','LICENCE*','COPYING*','COPYRIGHT*'):
        candidates.update(p for p in crate.rglob(pattern) if p.is_file())
    if data.get('license-file'):
        candidates.add(crate/data['license-file'])
    for src in sorted(candidates):
        if src.is_file():
            dst = dest/src.relative_to(crate)
            dst.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(src,dst)
    if not candidates:
        dest.mkdir(parents=True,exist_ok=True)
        (dest/'DECLARED-LICENSE.txt').write_text(f'{name} {version}\nDeclared: {license_name}\nRepository: {repository}\nNo separate license text was included in the downloaded crate.\n')
lines += ['', '## Other components', '',
          '- Rust standard library: MIT OR Apache-2.0; upstream Rust notices under `licenses/rust/`.',
          '- Gradle Wrapper: Apache-2.0; `licenses/Apache-2.0.txt`.',
          '- The prebuilt Windows GNU DLL uses MinGW-w64 runtime/linker support. Upstream notices and runtime exceptions are under `licenses/mingw-w64/`.',
          '- Minecraft, Fabric Loader and Fabric API are external runtime/build dependencies, not redistributed in this mod.',
          '- GCC runtime notices and the runtime exception for the GNU cross build: `licenses/gcc/`. This does not change the project license.',
          '- No Minecraft assets or game JARs are included.', '']
(root/'THIRD_PARTY_NOTICES.md').write_text('\n'.join(lines))
if missing:
    raise SystemExit('Dependencies not downloaded: '+', '.join(missing))
count = sum('source' in dep for dep in lock['package']) - len(missing)
print(f'Collected {count} locked dependency notices; inspect THIRD_PARTY_NOTICES.md and original texts.')
