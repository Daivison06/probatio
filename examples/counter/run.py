"""Two real threads demonstrate lost updates and an atomic corrected variant."""
import argparse
import json
from pathlib import Path
import sys
import threading
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent / 'scripts'))
from regress import evaluate, report  # noqa: E402
from tracecheck import write_events  # noqa: E402

MODEL = str(HERE / 'Counter.tla')
VARIANTS = {'Buggy': False, 'Fixed': True}
EXPECTED_VALUES = {'Buggy': 1, 'Fixed': 2}


def scenario(path, atomic):
    value = 0
    events = []
    observation = threading.Lock()
    reads_done = threading.Barrier(2, timeout=5)

    def step(actor, action, seq, work):
        nonlocal value
        with observation:
            start = time.monotonic_ns()
            value = work(value)
            events.append(dict(actor=actor, seq=seq, start_ns=start, end_ns=time.monotonic_ns(), action=action,
                               data={'increment': 1}, state={'value': value, 'atomic': atomic}))

    def worker(actor):
        if atomic:
            step(actor, 'increment', 1, lambda v: v + 1)
            return
        cached = {}
        step(actor, 'read', 1, lambda v: cached.setdefault('value', v))
        reads_done.wait()
        step(actor, 'write', 2, lambda v: cached['value'] + 1)

    threads = [threading.Thread(target=worker, args=(actor,)) for actor in ('A', 'B')]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=10)
        if thread.is_alive():
            raise RuntimeError('Worker did not finish')
    if len(events) != (2 if atomic else 4):
        raise RuntimeError('Incomplete trace')
    write_events(path, events)
    return value


def suite():
    cases = []
    for variant, atomic in VARIANTS.items():
        cases.append(dict(name='mc-' + variant, model=MODEL, config=str(HERE / (variant + '.cfg')),
                          expected='pass' if atomic else 'violation'))
        cases.append(dict(name='trace-' + variant, model=MODEL, trace=variant + '.ndjson', expected='match'))
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    output = Path(args.out).resolve()
    output.mkdir(parents=True, exist_ok=False)
    values = {variant: scenario(output / (variant + '.ndjson'), atomic) for variant, atomic in VARIANTS.items()}
    if values != EXPECTED_VALUES:
        raise SystemExit(f'Implementation did not reproduce the expected values: {values}')
    (output / 'implementation-results.json').write_text(json.dumps(values, indent=2))
    (output / 'suite.json').write_text(json.dumps(suite(), indent=2))
    return report(evaluate(output / 'suite.json', output / 'regression', args.jar, args.java))


if __name__ == '__main__':
    raise SystemExit(main())
