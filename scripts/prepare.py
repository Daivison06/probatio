#!/usr/bin/env python3
"""Create a fresh, source-stamped Probatio workspace."""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from string import Template

TEMPLATES = Path(__file__).resolve().parent.parent / 'assets' / 'workspace'
EMPTY_DIRS = ('findings', 'checks', 'models', 'harness', 'traces', 'controls')


def git(source: Path, *args: str) -> bytes | None:
    try:
        result = subprocess.run(['git', '-C', str(source), *args], check=True, capture_output=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    return result.stdout.strip()


def decode(raw: bytes | None) -> str | None:
    return None if raw is None else raw.decode('utf-8', errors='replace')


def untracked_digest(source: Path, paths: list[str]) -> str:
    """Length-prefix each path and content so distinct file sets cannot share a digest."""
    digest = hashlib.sha256()
    for relative in paths:
        path = source / relative
        if not path.is_file():
            continue
        name = relative.encode()
        digest.update(len(name).to_bytes(8, 'big') + name + path.stat().st_size.to_bytes(8, 'big'))
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1 << 20), b''):
                digest.update(chunk)
    return digest.hexdigest()


def working_tree(source: Path) -> str:
    diff = git(source, 'diff', 'HEAD', '--binary')
    if diff is None:
        return 'not available (not a Git worktree or Git unavailable)'
    untracked = (decode(git(source, 'ls-files', '--others', '--exclude-standard')) or '').splitlines()
    if not diff and not untracked:
        return 'clean'
    return (f'dirty (tracked diff sha256: {hashlib.sha256(diff).hexdigest()}; '
            f'untracked files: {len(untracked)}, content sha256: {untracked_digest(source, untracked)})')


def create_workspace(out: Path, fields: dict[str, str]) -> None:
    shutil.copytree(TEMPLATES, out)
    for name in EMPTY_DIRS:
        (out / name).mkdir(exist_ok=True)
    scope = out / 'scope.md'
    scope.write_text(Template(scope.read_text()).substitute(fields), encoding='utf-8')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path, help='project repository root')
    parser.add_argument('--out', required=True, type=Path, help='new analysis directory; must not exist')
    parser.add_argument('--scope', required=True, help='behavior or property to investigate')
    args = parser.parse_args()

    source = args.source.expanduser().resolve()
    out = args.out.expanduser().resolve()
    if not source.is_dir():
        parser.error(f'source directory does not exist: {source}')
    if out.exists():
        parser.error(f'output already exists; choose a fresh path: {out}')

    fields = {
        'run_id': out.name,
        'timestamp': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'source': str(source),
        'revision_text': decode(git(source, 'rev-parse', 'HEAD')) or 'unavailable; record a non-Git baseline description',
        'dirty_summary': working_tree(source),
        'status_text': decode(git(source, 'status', '--short')) or '(clean or unavailable)',
        'scope': args.scope,
    }
    create_workspace(out, fields)
    print(f'Initialized analysis workspace: {out}')
    print('Source revision:', fields['revision_text'])
    print('Working tree:', fields['dirty_summary'])
    return 0


if __name__ == '__main__':
    sys.exit(main())
