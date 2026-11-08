"""Input validation and compilation checks that need no Java or TLC."""
import json
from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import unittest

from helpers import LEASE_MODEL, ROOT, event
from implementation import scenario
from regress import evaluate
from tlc import run, strip_comments
from tracecheck import TraceRejected, compile_trace, immediate_predecessors, literal, read_events, write_events


class TempDirTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.jar = self.root / 'placeholder.jar'
        self.jar.write_text('not a real jar; these tests stop before TLC starts')

    def tearDown(self):
        self.temp.cleanup()


class ConfigGuardTest(TempDirTest):
    def model_with_config(self, config):
        (self.root / 'M.tla').write_text('---- MODULE M ----\n====\n')
        (self.root / 'M.cfg').write_text(config)
        return self.root / 'M.tla', self.root / 'M.cfg'

    def test_commented_out_properties_do_not_count(self):
        for config in ('INIT Init\nNEXT Next\n\\* INVARIANT Safe\n',
                       'INIT Init\nNEXT Next\n(* INVARIANT Safe *)\n',
                       'INIT Init\nNEXT Next\n(* outer (* inner *) INVARIANT Safe *)\n'):
            with self.subTest(config=config):
                model, cfg = self.model_with_config(config)
                with self.assertRaisesRegex(ValueError, 'No correctness property'):
                    run(model, cfg, self.root / 'out', self.jar)

    def test_comment_stripping_keeps_live_text(self):
        self.assertEqual(strip_comments('A (* x (* y *) z *) B \\* c\nC'), 'A  B \nC')


class TraceValidationTest(TempDirTest):
    def write(self, name, events):
        path = self.root / name
        write_events(path, events)
        return path

    def test_rejects_missing_fields_and_bad_sequences(self):
        path = self.root / 'raw.ndjson'
        scenario(path, True)
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows[-1]['seq'] = 99
        with self.assertRaises(TraceRejected):
            read_events(self.write('gap.ndjson', rows))
        rows[-1]['seq'] = 2
        rows[-1]['state'] = {}
        with self.assertRaises(TraceRejected):
            read_events(self.write('nostate.ndjson', rows))

    def test_malformed_and_empty_traces_are_rejections(self):
        (self.root / 'bad.ndjson').write_text('{')
        (self.root / 'empty.ndjson').write_text('\n')
        for name in ('bad.ndjson', 'empty.ndjson'):
            with self.subTest(name=name), self.assertRaises(TraceRejected):
                read_events(self.root / name)

    def test_literals(self):
        self.assertEqual(literal({'n': -5, 'ok': True, 's': [1, 'a']}), '[n |-> -5, ok |-> TRUE, s |-> <<1, "a">>]')
        for value in ({'IF': 1}, {}, 1.5, 2 ** 40, 'olá', None):
            with self.subTest(value=value), self.assertRaises(TraceRejected):
                literal(value)

    def test_negative_integers_compile_with_integers_module(self):
        trace = self.write('neg.ndjson', [event('A', 1, 1, 2, data={'delta': -1})])
        compile_trace(LEASE_MODEL, trace, self.root / 'compiled')
        self.assertIn('EXTENDS Integers', (self.root / 'compiled' / 'TraceInput.tla').read_text())

    def test_model_must_define_trace_contract(self):
        model_dir = self.root / 'model'
        model_dir.mkdir()
        (model_dir / 'Bare.tla').write_text('---- MODULE Bare ----\nInit == TRUE\n====\n')
        trace = self.write('t.ndjson', [event('A', 1, 1, 2)])
        with self.assertRaisesRegex(ValueError, r'Step\(e\), Observe'):
            compile_trace(model_dir / 'Bare.tla', trace, self.root / 'out')


def closure(direct):
    """Transitive closure of 1-based predecessor sets, as a fixed point."""
    full = [set(preds) for preds in direct]
    changed = True
    while changed:
        changed = False
        for reach in full:
            implied = set().union(*(full[p - 1] for p in reach)) - reach
            reach |= implied
            changed = changed or bool(implied)
    return full


def random_trace(rng):
    events = []
    for actor in 'ABC':
        clock = rng.randint(0, 5)
        for seq in range(1, rng.randint(1, 6) + 1):
            start = clock + rng.randint(0, 3)
            clock = start + rng.randint(0, 4)
            events.append(event(actor, seq, start, clock))
    rng.shuffle(events)
    return events


def full_order(events):
    return [{j for j, other in enumerate(events, 1) if other is not e and (
        (other['actor'] == e['actor'] and other['seq'] < e['seq']) or other['end_ns'] < e['start_ns'])}
        for e in events]


class CausalOrderTest(unittest.TestCase):
    def test_reduced_order_implies_exactly_the_full_order(self):
        rng = random.Random(7)
        for _ in range(300):
            events = random_trace(rng)
            direct = immediate_predecessors(events)
            self.assertEqual(closure(direct), full_order(events))

    def test_sequential_trace_is_linear_in_size(self):
        events = [event('A', i, 2 * i, 2 * i + 1) for i in range(1, 501)]
        self.assertEqual(sum(map(len, immediate_predecessors(events))), 499)


class RegressionManifestTest(TempDirTest):
    def manifest(self, cases):
        path = self.root / 'suite.json'
        path.write_text(json.dumps(cases))
        return path

    def test_missing_trace_file_is_an_error_not_an_accepted_control(self):
        cases = [{'name': 'ctl', 'model': str(LEASE_MODEL), 'trace': 'TYPO.ndjson', 'expected': 'invalid'}]
        [result] = evaluate(self.manifest(cases), self.root / 'out', self.jar)
        self.assertEqual(result['actual'], 'error')
        self.assertFalse(result['accepted'])

    def test_rejected_trace_is_invalid(self):
        (self.root / 'bad.ndjson').write_text('{')
        cases = [{'name': 'ctl', 'model': str(LEASE_MODEL), 'trace': 'bad.ndjson', 'expected': 'invalid'}]
        [result] = evaluate(self.manifest(cases), self.root / 'out', self.jar)
        self.assertTrue(result['accepted'])

    def test_malformed_manifests_fail_before_any_case_runs(self):
        ok = {'name': 'a', 'model': 'M.tla', 'config': 'M.cfg', 'expected': 'pass'}
        broken = [
            [],
            [{'name': 'a', 'expected': 'pass', 'config': 'M.cfg'}],
            [{**ok, 'trace': 't.ndjson'}],
            [{**ok, 'expected': 'error'}],
            [{**ok, 'name': 'café'}],
            [{**ok, 'expect': 'pass'}],
            [ok, ok],
        ]
        for index, cases in enumerate(broken):
            with self.subTest(cases=cases), self.assertRaises(ValueError):
                evaluate(self.manifest(cases), self.root / f'out{index}', self.jar)
            self.assertFalse((self.root / f'out{index}').exists())


@unittest.skipUnless(shutil.which('git'), 'git not installed')
class PrepareTest(TempDirTest):
    def git(self, *args):
        subprocess.run(['git', '-C', str(self.repo), '-c', 'user.email=t@t', '-c', 'user.name=t', *args],
                       check=True, capture_output=True)

    def prepare(self, out):
        return subprocess.run(['python3', str(ROOT / 'scripts' / 'prepare.py'), '--source', str(self.repo),
                               '--out', str(out), '--scope', 'cost $5 and 100%'], capture_output=True, text=True)

    def test_workspace_from_repo_with_non_utf8_changes(self):
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.git('init', '-q')
        (self.repo / 'a.txt').write_bytes(b'ola\n')
        self.git('add', 'a.txt')
        self.git('commit', '-qm', 'init')
        (self.repo / 'a.txt').write_bytes(b'ol\xe1\n')
        (self.repo / 'new.txt').write_text('new')
        result = self.prepare(self.root / 'ws')
        self.assertEqual(result.returncode, 0, result.stderr)
        scope = (self.root / 'ws' / 'scope.md').read_text()
        self.assertIn('untracked files: 1', scope)
        self.assertIn('cost $5 and 100%', scope)
        for name in ('findings', 'checks', 'models/modeling.md', 'suite.json', 'report.md'):
            self.assertTrue((self.root / 'ws' / name).exists(), name)
        self.assertNotEqual(self.prepare(self.root / 'ws').returncode, 0)


if __name__ == '__main__':
    unittest.main()
