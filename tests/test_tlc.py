"""End-to-end checks that run TLC. Set TLA_JAR (and optionally JAVA) to enable them."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from helpers import LEASE_FIXED_CFG, LEASE_MODEL, ROOT, event
from implementation import scenario
from regress import evaluate
from tlc import run
from tracecheck import compile_trace, write_events

JAR = os.environ.get('TLA_JAR')
JAVA = os.environ.get('JAVA', 'java')


@unittest.skipUnless(JAR, 'TLA_JAR not set')
class TlcTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def execute(self, model, config, name, **kwargs):
        return run(model, config, self.root / name, JAR, JAVA, **kwargs)

    def check_trace(self, events, name):
        path = self.root / (name + '.ndjson')
        write_events(path, events)
        model, cfg = compile_trace(LEASE_MODEL, path, self.root / (name + '-compiled'))
        return self.execute(model, cfg, name, trace=True)['result']

    def write_model(self, name, body, config):
        model = self.root / (name + '.tla')
        model.write_text(f'---- MODULE {name} ----\n{body}\n====\n')
        cfg = self.root / (name + '.cfg')
        cfg.write_text(config)
        return model, cfg

    def test_partial_order_search_accepts_one_valid_branch(self):
        # Synthetic validator control, not a trace claimed as implementation evidence.
        events = [event('B', 1, 1, 8), event('A', 1, 2, 3),
                  event('A', 2, 4, 5, action='release', state={'owner': '', 'guarded': True})]
        self.assertEqual(self.check_trace(events, 'valid'), 'match')
        events[0]['state']['owner'] = 'C'
        self.assertEqual(self.check_trace(events, 'impossible'), 'mismatch')

    def test_negative_observed_integers_parse(self):
        self.assertEqual(self.check_trace([event('A', 1, 1, 2, data={'delta': -1})], 'negative'), 'match')

    def test_no_false_pass_on_timeout_or_parse_error(self):
        self.assertEqual(self.execute(LEASE_MODEL, LEASE_FIXED_CFG, 'timeout', timeout=0.000001)['result'], 'timeout')
        model, cfg = self.write_model('Broken', 'THIS IS NOT VALID', 'INIT Init\nNEXT Next\nINVARIANT Safe\n')
        self.assertEqual(self.execute(model, cfg, 'parse')['result'], 'error')

    def test_deadlock_and_liveness_are_not_tool_errors(self):
        body = "EXTENDS Naturals\nVARIABLE x\nInit == x=0\nStop == FALSE /\\ x'=x\nLoop == x'=x\nSafe == x=0\nEventually == <>(x=1)"
        model, cfg = self.write_model('Progress', body, 'INIT Init\nNEXT Stop\nINVARIANT Safe\n')
        self.assertEqual(self.execute(model, cfg, 'deadlock')['result'], 'deadlock')
        cfg.write_text('INIT Init\nNEXT Loop\nPROPERTY Eventually\n')
        self.assertEqual(self.execute(model, cfg, 'liveness')['result'], 'violation')

    def test_empty_initial_state_space_is_not_a_pass(self):
        body = "EXTENDS Naturals\nVARIABLE x\nInit == x=0 /\\ x=1\nNext == x'=x\nSafe == x=0"
        model, cfg = self.write_model('Empty', body, 'INIT Init\nNEXT Next\nINVARIANT Safe\n')
        self.assertEqual(self.execute(model, cfg, 'empty-init')['result'], 'error')

    def test_joint_regression_records_failure_and_preserves_inputs(self):
        trace = self.root / 'raw.ndjson'
        scenario(trace, True)
        cases = [{'name': 'safety', 'model': str(LEASE_MODEL), 'config': str(LEASE_FIXED_CFG), 'expected': 'pass'},
                 {'name': 'trace', 'model': str(LEASE_MODEL), 'trace': str(trace), 'expected': 'match'}]
        manifest = self.root / 'suite.json'
        manifest.write_text(json.dumps(cases))
        first = evaluate(manifest, self.root / 'round1', JAR, JAVA)
        self.assertTrue(all(r['accepted'] for r in first), first)
        snapshot = self.root / 'round1' / 'trace' / 'inputs' / 'source.ndjson'
        saved = snapshot.read_text()
        rows = [json.loads(line) for line in trace.read_text().splitlines()]
        rows[-1]['state']['owner'] = ''
        write_events(trace, rows)
        second = evaluate(manifest, self.root / 'round2', JAR, JAVA)
        self.assertEqual([r['accepted'] for r in second], [True, False])
        self.assertEqual(second[1]['actual'], 'mismatch')
        self.assertEqual(snapshot.read_text(), saved)
        with self.assertRaises(FileExistsError):
            evaluate(manifest, self.root / 'round1', JAR, JAVA)

    def test_examples_pass_their_own_controls(self):
        for example in ('lease', 'counter'):
            with self.subTest(example=example):
                command = [sys.executable, str(ROOT / 'examples' / example / 'run.py'),
                           '--out', str(self.root / example), '--jar', JAR, '--java', JAVA]
                result = subprocess.run(command, capture_output=True, text=True, timeout=600)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
