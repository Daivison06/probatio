"""Rerun model configurations and implementation traces as one regression suite."""
import argparse
import json
from pathlib import Path
from tlc import run
from trace import compile_trace


def evaluate(manifest, output, jar, java='java', timeout=120):
    manifest, output = Path(manifest).resolve(), Path(output).resolve()
    cases = json.loads(manifest.read_text())
    if not isinstance(cases, list) or not cases:
        raise ValueError('Regression manifest must contain cases')
    names = [c['name'] for c in cases]
    if len(names) != len(set(names)) or any(not n.replace('-', '').replace('_', '').isalnum() for n in names):
        raise ValueError('Case names must be unique safe identifiers')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'manifest.json').write_text(manifest.read_text())
    results = []
    for case in cases:
        model = (manifest.parent / case['model']).resolve()
        try:
            if 'trace' in case:
                model, config = compile_trace(model, manifest.parent / case['trace'], output / (case['name'] + '-trace'))
            else:
                config = manifest.parent / case['config']
            receipt = run(model, config, output / case['name'], jar, java, timeout, trace='trace' in case)
            actual = receipt['result']
        except (ValueError, OSError, json.JSONDecodeError) as exc:
            actual = 'invalid'
            receipt = {'error': str(exc)}
        results.append({'name': case['name'], 'expected': case['expected'], 'actual': actual,
                        'accepted': actual == case['expected'], 'evidence': receipt})
        (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest')
    parser.add_argument('--out', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--timeout', type=float, default=120)
    args = parser.parse_args()
    results = evaluate(args.manifest, args.out, args.jar, args.java, args.timeout)
    print(json.dumps([{k:v for k,v in r.items() if k != 'evidence'} for r in results], indent=2))
    raise SystemExit(0 if all(r['accepted'] for r in results) else 1)
