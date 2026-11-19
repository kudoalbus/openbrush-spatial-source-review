#!/usr/bin/env python3
"""Verify the visionOS text snapshot and preserve the PICO review baseline."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
platform = root / 'visionos'
manifest_path = platform / 'REVIEW-MANIFEST.json'
manifest = json.loads(manifest_path.read_text())


def git(*arguments):
    return subprocess.check_output(['git', '-C', str(root), *arguments])


files = {
    Path(name).relative_to('visionos').as_posix()
    for name in git('ls-files', '--cached', '--others', '--exclude-standard', '--', 'visionos').decode().splitlines()
}
expected = set(manifest['files']) | {manifest_path.name}
assert files == expected, {'missing': sorted(expected - files), 'unexpected': sorted(files - expected)}
allowed_suffixes = {'.swift', '.json', '.xcstrings', '.py', '.sh', '.md', '.xcconfig', '.pbxproj', '.xcscheme'}
runtime_count = 0
for name, row in manifest['files'].items():
    path = platform / name
    assert not path.is_symlink(), name
    assert path.suffix in allowed_suffixes or path.name in {'.gitignore', 'NOTICE', 'OPENBRUSH-LICENSE'}, name
    contents = path.read_bytes()
    assert b'\x00' not in contents, name
    contents.decode('utf-8')
    assert hashlib.sha256(contents).hexdigest() == row['sha256'], name
    if row['kind'] == 'unchanged-runtime':
        assert row['sha256'] == row['source_sha256'], name
        runtime_count += 1
assert runtime_count == 36, runtime_count

# The reviewed PICO tree and JVM build retain the exact baseline bytes.
base = manifest['review_base']
baseline = git('ls-tree', '-r', '-z', base, '--', 'pico', 'build.gradle', 'settings.gradle', 'LICENSE')
protected = set()
for entry in baseline.split(b'\0'):
    if not entry:
        continue
    header, filename = entry.split(b'\t')
    object_id = header.split()[2].decode()
    path = filename.decode()
    protected.add(path)
    current_id = git('hash-object', '--', path).decode().strip()
    assert current_id == object_id, path
pico_files = {
    name for name in git('ls-files', '--cached', '--others', '--exclude-standard', '--', 'pico').decode().splitlines()
}
assert pico_files == {name for name in protected if name.startswith('pico/')}, 'PICO inventory changed'

project = (platform / 'OpenBrushSpatial.xcodeproj/project.pbxproj').read_text()
for record in project.splitlines():
    if 'PBXFileReference' not in record or 'sourceTree = SOURCE_ROOT' not in record:
        continue
    match = re.search(r'path = (?:"([^"]+)"|([^;]+));', record)
    assert match, record
    relative = match.group(1) or match.group(2)
    destination = (platform / relative).resolve()
    assert destination.is_relative_to(platform.resolve()) and destination.exists(), relative
print(f'PASS: {len(expected)} visionOS text files, {runtime_count} unchanged runtime sources; PICO and JVM build match {base[:7]}')
