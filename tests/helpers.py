from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'examples' / 'lease'))

LEASE_MODEL = ROOT / 'examples' / 'lease' / 'Lease.tla'
LEASE_FIXED_CFG = ROOT / 'examples' / 'lease' / 'Fixed.cfg'


def event(actor, seq, start, end, action='acquire', state=None, data=None):
    return dict(actor=actor, seq=seq, start_ns=start, end_ns=end, action=action,
                data=data or {'key': 'x'}, state=state or {'owner': actor, 'guarded': True})
