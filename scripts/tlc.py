"""Execute TLC with an immutable input snapshot and an explicit result receipt."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time

SNAPSHOT_SUFFIXES = {'.tla', '.cfg', '.json', '.ndjson'}
HEAP = re.compile(r'[1-9][0-9]*[mMgG]')
CFG_KEYWORDS = frozenset('''
    INIT NEXT SPECIFICATION CONSTANT CONSTANTS INVARIANT INVARIANTS PROPERTY PROPERTIES CONSTRAINT CONSTRAINTS
    ACTION_CONSTRAINT ACTION_CONSTRAINTS SYMMETRY VIEW CHECK_DEADLOCK POSTCONDITION ALIAS
'''.split())
INVARIANT_SECTIONS = {'INVARIANT', 'INVARIANTS'}
PROPERTY_SECTIONS = INVARIANT_SECTIONS | {'PROPERTY', 'PROPERTIES'}
CFG_NAME = re.compile(r'[A-Za-z_][A-Za-z0-9_]*\Z')
INVARIANT_VIOLATED = re.compile(r'Invariant (\w+) is violated')
INITIAL_STATES = re.compile(r'Finished computing initial states:\s+([0-9,]+) distinct state')
TEMPORAL_VIOLATED = re.compile(r'Temporal propert(?:y .+ was|ies were) violated')
NO_ERROR = 'Model checking completed. No error has been found.'
PROCESS_GROUP = ({'start_new_session': True} if os.name == 'posix'
                 else {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP})


def run(model, config, output, jar, java='java', timeout=120, workers=2, heap='1g', trace=False):
    model, config, output, jar = (Path(p).resolve() for p in (model, config, output, jar))
    check_arguments(model, config, jar, timeout, workers, heap)
    check_config(config.read_text(), trace)
    output.mkdir(parents=True, exist_ok=False)
    inputs = snapshot(model.parent, output / 'inputs')
    command = tlc_command(java, jar, heap, workers, config.name, output / 'states', model.name, trace)
    log_path = output / 'tlc.log'
    start = time.monotonic()
    code, timed_out = execute(command, output / 'inputs', log_path, timeout)
    outcome = classify(code, log_path.read_text(errors='replace'), trace)
    if timed_out:
        outcome['result'] = 'timeout'
    receipt = {**outcome, 'exit_code': code, 'seconds': round(time.monotonic() - start, 3),
               'command': command, 'cwd': str(output / 'inputs'), 'inputs_sha256': inputs,
               'jar_sha256': sha256_file(jar), 'log': str(log_path)}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def classify(code, log, trace):
    """Map a TLC exit code to a result only when the log confirms it; anything else is an error."""
    initial = INITIAL_STATES.search(log)
    initial_states = int(initial[1].replace(',', '')) if initial else None
    violated = INVARIANT_VIOLATED.search(log)
    invariant = violated[1] if violated else None
    outcomes = {
        0: (bool(initial_states) and NO_ERROR in log, 'mismatch' if trace else 'pass'),
        11: ('Deadlock reached' in log, 'deadlock'),
        12: (invariant is not None, 'match' if trace and invariant == 'TraceIncomplete' else 'violation'),
        13: (TEMPORAL_VIOLATED.search(log) is not None, 'violation'),
    }
    confirmed, result = outcomes.get(code, (False, 'error'))
    return {'result': result if confirmed else 'error', 'invariant': invariant, 'initial_states': initial_states}


def execute(command, cwd, log_path, timeout):
    """Return (exit code or None when TLC could not start, whether it timed out)."""
    with log_path.open('w') as log:
        try:
            process = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, **PROCESS_GROUP)
        except OSError as exc:
            log.write(str(exc))
            return None, False
        try:
            return process.wait(timeout=timeout), False
        except subprocess.TimeoutExpired:
            stop(process)
            return process.returncode, True


def strip_comments(text):
    """Remove TLA+ line comments and nested block comments."""
    kept, depth, i = [], 0, 0
    while i < len(text):
        pair = text[i:i + 2]
        if pair == '(*':
            depth, i = depth + 1, i + 2
        elif pair == '*)' and depth:
            depth, i = depth - 1, i + 2
        elif depth:
            i += 1
        elif pair == '\\*':
            end = text.find('\n', i)
            i = len(text) if end < 0 else end
        else:
            kept.append(text[i])
            i += 1
    return ''.join(kept)


def configured_properties(text):
    """(section, name) pairs for each property section followed by a real name, not an empty section."""
    words = strip_comments(text).split()
    return {(section, name) for section, name in zip(words, words[1:])
            if section in PROPERTY_SECTIONS and CFG_NAME.fullmatch(name) and name not in CFG_KEYWORDS}


def check_config(text, trace):
    properties = configured_properties(text)
    if trace and not any(s in INVARIANT_SECTIONS and n == 'TraceIncomplete' for s, n in properties):
        raise ValueError('Trace reachability invariant is not configured')
    if not trace and not properties:
        raise ValueError('No correctness property configured')


def check_arguments(model, config, jar, timeout, workers, heap):
    if timeout <= 0 or workers < 1 or not HEAP.fullmatch(heap):
        raise ValueError('Positive timeout/workers and heap such as 512m or 1g required')
    if model.parent != config.parent:
        raise ValueError('Model and configuration must share a directory')
    if not jar.is_file():
        raise ValueError('TLC jar not found')
    if not model.is_file() or not config.is_file():
        raise ValueError('Model or configuration not found')


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def snapshot(source_dir, destination):
    destination.mkdir()
    digests = {}
    for path in sorted(source_dir.iterdir()):
        if path.is_file() and path.suffix in SNAPSHOT_SUFFIXES:
            shutil.copy2(path, destination / path.name)
            digests[path.name] = sha256_file(path)
    return digests


def tlc_command(java, jar, heap, workers, config, metadir, model, trace):
    command = [java, '-XX:+UseParallelGC', '-Xmx' + heap, '-cp', str(jar), 'tlc2.TLC',
               '-workers', str(workers), '-config', config, '-metadir', str(metadir)]
    if trace:
        command.append('-deadlock')  # Failed trace branches are expected terminal states.
    return command + [model]


def stop(process):
    if os.name != 'posix':
        process.kill()
        process.wait()
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model')
    parser.add_argument('config')
    parser.add_argument('--out', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--timeout', type=float, default=120)
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--heap', default='1g')
    parser.add_argument('--trace', action='store_true')
    args = parser.parse_args()
    receipt = run(args.model, args.config, args.out, args.jar, args.java, args.timeout,
                  args.workers, args.heap, args.trace)
    print(json.dumps(receipt))
    return 0 if receipt['result'] in {'pass', 'match'} else 1


if __name__ == '__main__':
    raise SystemExit(main())
