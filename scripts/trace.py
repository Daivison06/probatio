"""Compile observed NDJSON into an existential TLC trace reachability check."""
import argparse
import json
from pathlib import Path
import re
import shutil

IDENTIFIER = re.compile(r'[A-Za-z][A-Za-z0-9_]*\Z')


def literal(value):
    if isinstance(value, bool):
        return 'TRUE' if value else 'FALSE'
    if isinstance(value, int) and not isinstance(value, bool):
        if abs(value) > 2147483647:
            raise ValueError('Observed integers must fit TLC integers; abstract large values explicitly')
        return str(value)
    if isinstance(value, str) and value.isascii() and all(ord(c) >= 32 for c in value):
        return json.dumps(value)
    if isinstance(value, list):
        return '<<' + ', '.join(map(literal, value)) + '>>'
    if isinstance(value, dict) and value and all(IDENTIFIER.fullmatch(k) for k in value):
        return '[' + ', '.join(k + ' |-> ' + literal(v) for k, v in value.items()) + ']'
    raise ValueError('Unsupported TLA literal: use nonempty records, sequences, ASCII strings, integers, booleans')


def read_events(path):
    events = [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]
    if not events:
        raise ValueError('Empty trace is not evidence')
    required = {'actor', 'seq', 'start_ns', 'end_ns', 'action', 'state', 'data'}
    by_actor = {}
    for event in events:
        if not isinstance(event, dict) or set(event) != required:
            raise ValueError('Trace event must have exactly: ' + ', '.join(sorted(required)))
        for key in ('seq', 'start_ns', 'end_ns'):
            if type(event[key]) is not int or event[key] < 0:
                raise ValueError('Invalid event counter/time')
        if not isinstance(event['actor'], str) or not event['actor'] or not isinstance(event['action'], str):
            raise ValueError('Actor and action must be strings')
        if event['start_ns'] > event['end_ns']:
            raise ValueError('Inverted event interval')
        if not isinstance(event['state'], dict) or not event['state']:
            raise ValueError('Every event requires observed state')
        literal({k: event[k] for k in ('actor', 'action', 'data', 'state')})
        by_actor.setdefault(event['actor'], []).append(event)
    for group in by_actor.values():
        group.sort(key=lambda e: e['seq'])
        if [e['seq'] for e in group] != list(range(1, len(group) + 1)):
            raise ValueError('Per-actor sequence must start at 1 and be contiguous')
        if any(a['end_ns'] > b['start_ns'] for a, b in zip(group, group[1:])):
            raise ValueError('Same-actor events overlap; use separate actors for concurrent operations')
    return events


def compile_trace(model, trace, output):
    model, trace, output = map(Path, (model, trace, output))
    if not IDENTIFIER.fullmatch(model.stem) or model.stem in {'TraceCheck', 'TraceInput'}:
        raise ValueError('Invalid or reserved module name')
    events = read_events(trace)
    predecessors = []
    for index, event in enumerate(events):
        predecessors.append({j + 1 for j, other in enumerate(events) if j != index and (
            (other['actor'] == event['actor'] and other['seq'] < event['seq']) or
            other['end_ns'] < event['start_ns'])})
    remaining, consumed = set(range(1, len(events) + 1)), set()
    while remaining:
        ready = {i for i in remaining if predecessors[i - 1] <= consumed}
        if not ready:
            raise ValueError('Cyclic causal order')
        consumed |= ready
        remaining -= ready
    output.mkdir(parents=True, exist_ok=False)
    for source in model.parent.glob('*.tla'):
        shutil.copy2(source, output / source.name)
    shutil.copy2(trace, output / 'source.ndjson')
    projected = [{k: e[k] for k in ('actor', 'action', 'data', 'state')} for e in events]
    pred = '<<' + ', '.join('{' + ', '.join(map(str, sorted(p))) + '}' for p in predecessors) + '>>'
    (output / 'TraceInput.tla').write_text('---- MODULE TraceInput ----\nEvents == ' + literal(projected) + '\nBefore == ' + pred + '\n====\n')
    (output / 'TraceCheck.tla').write_text(r'''---- MODULE TraceCheck ----
EXTENDS MODEL, TraceInput, Sequences, FiniteSets
VARIABLE consumed
TraceInit == /\ Init /\ consumed = {}
TraceNext == \E i \in (1..Len(Events)) \ consumed :
    /\ Before[i] \subseteq consumed
    /\ Step(Events[i])
    /\ Observe' = Events[i].state
    /\ consumed' = consumed \cup {i}
TraceIncomplete == consumed /= 1..Len(Events)
====
'''.replace('MODEL', model.stem))
    (output / 'TraceCheck.cfg').write_text('INIT TraceInit\nNEXT TraceNext\nINVARIANT TraceIncomplete\n')
    (output / 'trace-metadata.json').write_text(json.dumps({'events': len(events), 'semantics': 'exists complete matching execution', 'clock': 'shared monotonic clock required'}, indent=2))
    return output / 'TraceCheck.tla', output / 'TraceCheck.cfg'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model')
    parser.add_argument('trace')
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    print(compile_trace(args.model, args.trace, args.out)[0])
