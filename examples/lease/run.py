"""Generate real traces and run the lease example's positive and negative controls."""
import argparse
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / 'scripts'))
from regress import evaluate
from implementation import scenario


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    output = Path(args.out).resolve()
    output.mkdir(parents=True, exist_ok=False)
    outcomes = {}
    for variant in ['Buggy', 'Fixed']:
        outcomes[variant] = scenario(output / (variant + '.ndjson'), variant == 'Fixed')
    assert outcomes == {'Buggy': '', 'Fixed': 'B'}, outcomes
    events = [json.loads(line) for line in (output / 'Fixed.ndjson').read_text().splitlines()]
    badstate = json.loads(json.dumps(events))
    badstate[-1]['state']['owner'] = ''
    unknown = json.loads(json.dumps(events))
    unknown[-1]['action'] = 'unknown_action'
    for name, data in [('badstate',badstate),('unknown',unknown)]:
        (output / (name + '.ndjson')).write_text(''.join(json.dumps(e)+'\n' for e in data))
    (output / 'empty.ndjson').write_text('')
    (output / 'malformed.ndjson').write_text('{')
    cases = []
    for variant in ['Buggy', 'Fixed']:
        cases.append({'name': 'mc-' + variant, 'model': str(HERE/'Lease.tla'), 'config': str(HERE/(variant+'.cfg')),
                      'expected': 'violation' if variant == 'Buggy' else 'pass'})
    for name, expected in [('Buggy','match'),('Fixed','match'),('badstate','mismatch'),('unknown','mismatch'),('empty','invalid'),('malformed','invalid')]:
        cases.append({'name':'trace-'+name, 'model':str(HERE/'Lease.tla'), 'trace':name+'.ndjson','expected':expected})
    (output/'suite.json').write_text(json.dumps(cases,indent=2))
    (output/'controls.json').write_text(json.dumps({'real_traces':['Buggy.ndjson','Fixed.ndjson'], 'synthetic_negative_controls':['badstate.ndjson','unknown.ndjson','empty.ndjson','malformed.ndjson'], 'implementation_results':outcomes},indent=2))
    results=evaluate(output/'suite.json',output/'regression',args.jar,args.java)
    print(json.dumps([{k:v for k,v in r.items() if k!='evidence'} for r in results],indent=2))
    return 0 if all(r['accepted'] for r in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
