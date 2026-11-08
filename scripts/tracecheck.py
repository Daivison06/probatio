"""Compile observed NDJSON into an existential TLC trace reachability check."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import shutil
from string import Template

IDENTIFIER = re.compile(r'[A-Za-z][A-Za-z0-9_]*\Z')
TLA_RESERVED = frozenset('''
    ACTION ASSUME ASSUMPTION AXIOM BOOLEAN BY CASE CHOOSE CONSTANT CONSTANTS COROLLARY DEF DEFINE DEFS
    DOMAIN ELSE ENABLED EXCEPT EXTENDS FALSE HAVE HIDE IF IN INSTANCE LAMBDA LEMMA LET LOCAL MODULE NEW
    OBVIOUS OMITTED ONLY OTHER PICK PROOF PROPOSITION PROVE QED RECURSIVE SF_ STATE STRING SUBSET SUFFICES
    TAKE TEMPORAL THEN THEOREM TRUE UNCHANGED UNION USE VARIABLE VARIABLES WF_ WITH WITNESS
'''.split())
TLC_INT_MAX = 2147483647
EVENT_FIELDS = frozenset({'actor', 'seq', 'start_ns', 'end_ns', 'action', 'state', 'data'})
PROJECTED_FIELDS = ('actor', 'action', 'data', 'state')
COUNTERS = ('seq', 'start_ns', 'end_ns')
RESERVED_MODULES = {'TraceCheck', 'TraceInput'}
MODEL_CONTRACT = {
    'Init': re.compile(r'^\s*Init\s*==', re.M),
    'Step(e)': re.compile(r'^\s*Step\s*\(', re.M),
    'Observe': re.compile(r'^\s*Observe\s*==', re.M),
}
EVENT_RULES = (
    (lambda e: all(type(e[k]) is int and e[k] >= 0 for k in COUNTERS), 'Invalid event counter/time'),
    (lambda e: isinstance(e['actor'], str) and e['actor'] != '' and isinstance(e['action'], str),
     'Actor must be a nonempty string and action a string'),
    (lambda e: e['start_ns'] <= e['end_ns'], 'Inverted event interval'),
    (lambda e: isinstance(e['state'], dict) and e['state'] != {}, 'Every event requires observed state'),
)
TRACE_CHECK = Template(r'''---- MODULE TraceCheck ----
EXTENDS $model, TraceInput, Sequences, FiniteSets
VARIABLE consumed
TraceInit == /\ Init /\ consumed = {}
TraceNext == \E i \in (1..Len(Events)) \ consumed :
    /\ Before[i] \subseteq consumed
    /\ Step(Events[i])
    /\ Observe' = Events[i].state
    /\ consumed' = consumed \cup {i}
TraceIncomplete == consumed /= 1..Len(Events)
====
''')


class TraceRejected(ValueError):
    """The observed trace is not admissible evidence, as opposed to a tool or setup failure."""


def compile_trace(model, trace, output):
    model, trace, output = Path(model), Path(trace), Path(output)
    check_model(model)
    events = read_events(trace)
    output.mkdir(parents=True, exist_ok=False)
    for source in model.parent.glob('*.tla'):
        shutil.copy2(source, output / source.name)
    shutil.copy2(trace, output / 'source.ndjson')
    (output / 'TraceInput.tla').write_text(trace_input_module(events))
    (output / 'TraceCheck.tla').write_text(TRACE_CHECK.substitute(model=model.stem))
    (output / 'TraceCheck.cfg').write_text('INIT TraceInit\nNEXT TraceNext\nINVARIANT TraceIncomplete\n')
    metadata = {'events': len(events), 'semantics': 'exists complete matching execution',
                'clock': 'shared monotonic clock required'}
    (output / 'trace-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return output / 'TraceCheck.tla', output / 'TraceCheck.cfg'


def read_events(path):
    try:
        events = parse_lines(path)
        if not events:
            raise TraceRejected('Empty trace is not evidence')
        for event in events:
            check_event(event)
    except RecursionError as exc:
        raise TraceRejected('Trace values are nested too deeply') from exc
    check_actor_order(events)
    return events


def tla_integer(value):
    if abs(value) > TLC_INT_MAX:
        raise TraceRejected('Observed integers must fit TLC integers; abstract large values explicitly')
    return str(value)


def tla_string(value):
    if not value.isascii() or any(ord(c) < 32 for c in value):
        raise TraceRejected('Observed strings must be printable ASCII')
    return json.dumps(value)


def tla_record(value):
    if not value or not all(IDENTIFIER.fullmatch(k) and k not in TLA_RESERVED and k[:3] not in {'WF_', 'SF_'}
                            for k in value):
        raise TraceRejected('Records need nonempty, non-reserved TLA identifier keys')
    return '[' + ', '.join(k + ' |-> ' + literal(v) for k, v in value.items()) + ']'


def literal(value):
    if isinstance(value, bool):
        return 'TRUE' if value else 'FALSE'
    if isinstance(value, int):
        return tla_integer(value)
    if isinstance(value, str):
        return tla_string(value)
    if isinstance(value, list):
        return '<<' + ', '.join(map(literal, value)) + '>>'
    if isinstance(value, dict):
        return tla_record(value)
    raise TraceRejected('Unsupported TLA literal: use nonempty records, sequences, ASCII strings, integers, booleans')


def parse_lines(path):
    try:
        lines = Path(path).read_text().splitlines()
        return [json.loads(line) for line in lines if line.strip()]
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise TraceRejected(f'Trace is not valid UTF-8 NDJSON: {exc}') from exc


def check_event(event):
    if not isinstance(event, dict) or set(event) != EVENT_FIELDS:
        raise TraceRejected('Trace event must have exactly: ' + ', '.join(sorted(EVENT_FIELDS)))
    for rule, message in EVENT_RULES:
        if not rule(event):
            raise TraceRejected(message)
    literal({k: event[k] for k in PROJECTED_FIELDS})


def check_actor_order(events):
    by_actor = defaultdict(list)
    for event in events:
        by_actor[event['actor']].append(event)
    for group in by_actor.values():
        group.sort(key=lambda e: e['seq'])
        if [e['seq'] for e in group] != list(range(1, len(group) + 1)):
            raise TraceRejected('Per-actor sequence must start at 1 and be contiguous')
        if any(a['end_ns'] > b['start_ns'] for a, b in zip(group, group[1:])):
            raise TraceRejected('Same-actor events overlap; use separate actors for concurrent operations')


def immediate_predecessors(events):
    """1-based indexes each event must follow; their transitive closure is the full causal order.

    The order is the actor's program order plus real-time precedence (ended before this started).
    Among real-time predecessors only the maximal ones are kept: an earlier event that ended before
    another predecessor started is already implied through it.
    """
    index_of = {(e['actor'], e['seq']): i for i, e in enumerate(events, 1)}
    result = []
    for event in events:
        earlier = [(i, other) for i, other in enumerate(events, 1) if other['end_ns'] < event['start_ns']]
        horizon = max((other['start_ns'] for _, other in earlier), default=0)
        before = {i for i, other in earlier if other['end_ns'] >= horizon}
        previous = index_of.get((event['actor'], event['seq'] - 1))
        result.append(before | ({previous} if previous else set()))
    return result


def check_model(model):
    if not IDENTIFIER.fullmatch(model.stem) or model.stem in RESERVED_MODULES:
        raise ValueError('Invalid or reserved module name')
    if not model.is_file():
        raise ValueError(f'Model not found: {model}')
    source = '\n'.join(p.read_text() for p in model.parent.glob('*.tla'))
    missing = [name for name, pattern in MODEL_CONTRACT.items() if not pattern.search(source)]
    if missing:
        raise ValueError('Model must define ' + ', '.join(missing) + ' for trace validation')


def trace_input_module(events):
    projected = [{k: e[k] for k in PROJECTED_FIELDS} for e in events]
    before = '<<' + ', '.join('{' + ', '.join(map(str, sorted(p))) + '}' for p in immediate_predecessors(events)) + '>>'
    return ('---- MODULE TraceInput ----\nEXTENDS Integers\n'
            f'Events == {literal(projected)}\nBefore == {before}\n====\n')


def write_events(path, events):
    Path(path).write_text(''.join(json.dumps(e) + '\n' for e in events))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model')
    parser.add_argument('trace')
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    print(compile_trace(args.model, args.trace, args.out)[0])
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
