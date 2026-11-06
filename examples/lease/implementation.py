"""Small executable lease store; the guarded variant checks ownership on release."""
import argparse
import json
from pathlib import Path
import time


class Lease:
    def __init__(self, guarded):
        self.owner = ''
        self.deadline = 0
        self.guarded = guarded

    def acquire(self, actor, ttl):
        if self.owner:
            return False
        self.owner = actor
        self.deadline = time.monotonic_ns() + ttl
        return True

    def expire(self):
        if self.owner and time.monotonic_ns() >= self.deadline:
            self.owner = ''

    def release(self, actor):
        if not self.guarded or self.owner == actor:
            self.owner = ''


def scenario(output, guarded):
    store = Lease(guarded)
    sequence = {}
    events = []

    def observe(actor, action, work):
        start = time.monotonic_ns()
        work()
        state = {'owner': store.owner, 'guarded': store.guarded}
        end = time.monotonic_ns()
        sequence[actor] = sequence.get(actor, 0) + 1
        events.append({'actor': actor, 'seq': sequence[actor], 'start_ns': start, 'end_ns': end,
                       'action': action, 'data': {'key': 'example'}, 'state': state})

    observe('A', 'acquire', lambda: store.acquire('A', 1_000_000))
    while time.monotonic_ns() < store.deadline:
        time.sleep(0.001)
    observe('clock', 'expire', store.expire)
    observe('B', 'acquire', lambda: store.acquire('B', 60_000_000_000))
    observe('A', 'release', lambda: store.release('A'))
    Path(output).write_text(''.join(json.dumps(e) + '\n' for e in events))
    return store.owner


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True)
    parser.add_argument('--guarded', action='store_true')
    args = parser.parse_args()
    print(json.dumps({'owner_after_old_release': scenario(args.out, args.guarded)}))
