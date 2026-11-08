"""Generate real traces and run the lease example's positive and negative controls."""
import argparse
import copy
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / 'scripts'))
from regress import evaluate, report  # noqa: E402
from tracecheck import write_events  # noqa: E402
from implementation import scenario  # noqa: E402

MODEL = str(HERE / 'Lease.tla')
EXPECTED_OWNERS = {'Buggy': '', 'Fixed': 'B'}
TRACE_EXPECTATIONS = [('Buggy', 'match'), ('Fixed', 'match'), ('badstate', 'mismatch'),
                      ('unknown', 'mismatch'), ('empty', 'invalid'), ('malformed', 'invalid')]


def write_controls(output):
    """Derive synthetic negative controls from the real guarded trace."""
    events = [json.loads(line) for line in (output / 'Fixed.ndjson').read_text().splitlines()]
    badstate, unknown = copy.deepcopy(events), copy.deepcopy(events)
    badstate[-1]['state']['owner'] = ''
    unknown[-1]['action'] = 'unknown_action'
    write_events(output / 'badstate.ndjson', badstate)
    write_events(output / 'unknown.ndjson', unknown)
    (output / 'empty.ndjson').write_text('')
    (output / 'malformed.ndjson').write_text('{')


def suite():
    cases = [{'name': 'mc-' + variant, 'model': MODEL, 'config': str(HERE / (variant + '.cfg')),
              'expected': 'violation' if variant == 'Buggy' else 'pass'} for variant in EXPECTED_OWNERS]
    cases += [{'name': 'trace-' + name, 'model': MODEL, 'trace': name + '.ndjson', 'expected': expected}
              for name, expected in TRACE_EXPECTATIONS]
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    output = Path(args.out).resolve()
    output.mkdir(parents=True, exist_ok=False)
    owners = {variant: scenario(output / (variant + '.ndjson'), variant == 'Fixed') for variant in EXPECTED_OWNERS}
    if owners != EXPECTED_OWNERS:
        raise SystemExit(f'Implementation did not reproduce the expected owners: {owners}')
    write_controls(output)
    (output / 'suite.json').write_text(json.dumps(suite(), indent=2))
    controls = {'real_traces': ['Buggy.ndjson', 'Fixed.ndjson'],
                'synthetic_negative_controls': ['badstate.ndjson', 'unknown.ndjson', 'empty.ndjson', 'malformed.ndjson'],
                'implementation_results': owners}
    (output / 'controls.json').write_text(json.dumps(controls, indent=2))
    return report(evaluate(output / 'suite.json', output / 'regression', args.jar, args.java))


if __name__ == '__main__':
    raise SystemExit(main())
