"""Rerun model configurations and implementation traces as one regression suite."""
import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import shutil
from typing import Optional

from tlc import run
from tracecheck import TraceRejected, compile_trace

# 'error' and 'timeout' are deliberately absent: a broken run can never satisfy an expectation.
EXPECTABLE = frozenset({'pass', 'violation', 'deadlock', 'match', 'mismatch', 'invalid'})
REQUIRED_KEYS = frozenset({'name', 'model', 'expected'})
ALLOWED_KEYS = REQUIRED_KEYS | {'config', 'trace'}
CASE_NAME = re.compile(r'[A-Za-z0-9_-]+\Z')


@dataclass(frozen=True)
class Case:
    name: str
    model: Path
    expected: str
    config: Optional[Path]
    trace: Optional[Path]


def evaluate(manifest, output, jar, java='java', timeout=120):
    manifest, output = Path(manifest).resolve(), Path(output).resolve()
    cases = load_cases(manifest)
    output.mkdir(parents=True, exist_ok=False)
    shutil.copy2(manifest, output / 'manifest.json')
    results = []
    for case in cases:
        try:
            actual, evidence = run_case(case, output, jar, java, timeout)
        except (ValueError, OSError) as exc:
            actual, evidence = 'error', {'error': f'{type(exc).__name__}: {exc}'}
        results.append({'name': case.name, 'expected': case.expected, 'actual': actual,
                        'accepted': actual == case.expected, 'evidence': evidence})
        (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    return results


def report(results):
    print(json.dumps([{k: v for k, v in r.items() if k != 'evidence'} for r in results], indent=2))
    return 0 if all(r['accepted'] for r in results) else 1


def parse_case(entry, base):
    if not isinstance(entry, dict) or not all(isinstance(v, str) for v in entry.values()):
        raise ValueError(f'Case must be an object of strings: {entry!r}')
    keys = set(entry)
    if not REQUIRED_KEYS <= keys <= ALLOWED_KEYS or len(keys & {'config', 'trace'}) != 1:
        raise ValueError(f'Case needs name, model, expected and exactly one of config/trace: {sorted(keys)}')
    if not CASE_NAME.fullmatch(entry['name']):
        raise ValueError(f'Case name must use only ASCII letters, digits, - and _: {entry["name"]!r}')
    if entry['expected'] not in EXPECTABLE:
        raise ValueError(f'Expected result must be one of {sorted(EXPECTABLE)}: {entry["expected"]!r}')
    optional = {k: base / entry[k] if k in entry else None for k in ('config', 'trace')}
    return Case(entry['name'], base / entry['model'], entry['expected'], **optional)


def load_cases(manifest):
    entries = json.loads(manifest.read_text())
    if not isinstance(entries, list) or not entries:
        raise ValueError('Regression manifest must contain cases')
    cases = [parse_case(entry, manifest.parent) for entry in entries]
    if len({c.name for c in cases}) != len(cases):
        raise ValueError('Case names must be unique')
    return cases


def run_case(case, output, jar, java, timeout):
    model, config = case.model, case.config
    if case.trace:
        try:
            model, config = compile_trace(case.model, case.trace, output / (case.name + '.trace'))
        except TraceRejected as exc:
            return 'invalid', {'rejected': str(exc)}
    receipt = run(model, config, output / case.name, jar, java, timeout, trace=case.trace is not None)
    return receipt['result'], receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest')
    parser.add_argument('--out', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--timeout', type=float, default=120)
    args = parser.parse_args()
    return report(evaluate(args.manifest, args.out, args.jar, args.java, args.timeout))


if __name__ == '__main__':
    raise SystemExit(main())
