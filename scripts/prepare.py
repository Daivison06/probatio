#!/usr/bin/env python3
"""Create a fresh, source-stamped Probatio workspace."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def git(source: Path, *args: str) -> str | None:
    try:
        result = subprocess.run(
            ["git", "-C", str(source), *args],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        return result.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="project repository root")
    parser.add_argument("--out", required=True, type=Path, help="new analysis directory; must not exist")
    parser.add_argument("--scope", required=True, help="behavior or property to investigate")
    args = parser.parse_args()

    source = args.source.expanduser().resolve()
    out = args.out.expanduser().resolve()
    if not source.is_dir():
        parser.error(f"source directory does not exist: {source}")
    if out.exists():
        parser.error(f"output already exists; choose a fresh path: {out}")

    revision = git(source, "rev-parse", "HEAD")
    status = git(source, "status", "--short")
    diff = git(source, "diff", "HEAD", "--binary")
    untracked = git(source, "ls-files", "--others", "--exclude-standard")
    if diff is not None:
        tracked_hash = hashlib.sha256(diff.encode()).hexdigest()
        untracked_paths = [line for line in (untracked or "").splitlines() if line]
        untracked_hash = hashlib.sha256()
        for relative in untracked_paths:
            path = source / relative
            if path.is_file():
                untracked_hash.update(relative.encode())
                untracked_hash.update(path.read_bytes())
        dirty = bool(diff) or bool(untracked_paths)
        dirty_summary = "clean" if not dirty else (
            f"dirty (tracked diff sha256: {tracked_hash}; "
            f"untracked files: {len(untracked_paths)}, content sha256: {untracked_hash.hexdigest()})"
        )
    else:
        untracked_paths = []
        dirty_summary = "not available (not a Git worktree or Git unavailable)"
    revision_text = revision or "unavailable; record a non-Git baseline description"
    status_text = "\n".join(filter(None, [status, "Untracked files:", *(untracked_paths or ["(none or unavailable)"])])) or "(clean or unavailable)"
    run_id = out.name
    timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds")

    out.mkdir(parents=True)
    for name in ("findings", "checks", "models", "harness", "traces", "controls"):
        (out / name).mkdir()

    files = {
        "scope.md": f"""# Scope\n\n- Run: `{run_id}`\n- Created (UTC): {timestamp}\n- Repository: `{source}`\n- Revision: `{revision_text}`\n- Working tree: {dirty_summary}\n- Git status:\n\n```text\n{status_text}\n```\n\n## User questions and scope\n\n{args.scope}\n\n## Contract sources\n\n- TODO\n\n## Boundaries, exclusions, assumptions\n\n- TODO\n""",
        "analysis.md": """# Analysis and modeling brief\n\n## Executive modeling brief\n\n- System category / execution model: TODO\n- Core state owners and lifecycle: TODO\n- Atomicity or linearization boundaries: TODO\n- Main contracts and observables: TODO\n- Selected scenarios: TODO\n\n## Source map and history boundary\n\nTODO\n\n## Scenario and candidate matrix\n\n| ID | Mechanism | Legal trigger | Contract | Observable | Source anchors | Property/config | Disconfirming check | Status | Evidence |\n|---|---|---|---|---|---|---|---|---|---|\n| S-001 | TODO | TODO | TODO | TODO | TODO | TODO | TODO | planned | TODO |\n\n## Coverage gaps and rejected hypotheses\n\nTODO\n""",
        "progress.md": """# Progress\n\n- Current phase: scope\n- Source baseline: see `scope.md`\n- Completed outputs: workspace initialized\n- Candidates: none assessed\n- Active processes: none\n- Blockers: none known\n- Next action: complete scope and source map\n""",
        "models/modeling.md": """# Modeling correspondence\n\n| Question/scenario | Contract | Source anchors | Variables/actions | Property | Module/config | Result/receipt | Gaps |\n|---|---|---|---|---|---|---|---|\n| TODO | TODO | TODO | TODO | TODO | TODO | not run | TODO |\n\n## Atomicity audit\n\n| Source operation | Reads/writes/effects | Actual boundary | Model actions | Excluded interleavings |\n|---|---|---|---|---|\n| TODO | TODO | TODO | TODO | TODO |\n\n## Assumptions, bounds, and fairness\n\nTODO\n\n## Completeness review\n\nTODO\n\n## Model revision history\n\nTODO\n""",
        "models/instrumentation.md": """# Instrumentation mapping\n\n| Model action | Source revision/symbol | Capture point | Actor | Captured fields | Timing/atomicity | Limits |\n|---|---|---|---|---|---|---|\n| TODO | TODO | TODO | TODO | TODO | TODO | TODO |\n""",
        "suite.json": "[]\n",
        "report.md": """# Formal verification report\n\n## Outcome\n\nTODO: state whether scope is complete, partial, or blocked.\n\n## Scope and source baseline\n\nSee `scope.md`.\n\n## Candidate dispositions\n\nTODO\n\n## Model-checking and trace results\n\nTODO\n\n## Limitations and next actions\n\nTODO\n""",
    }
    for name, content in files.items():
        (out / name).write_text(content, encoding="utf-8")
    print(f"Initialized analysis workspace: {out}")
    print("Source revision:", revision_text)
    print("Working tree:", dirty_summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
