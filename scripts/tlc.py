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


def run(model, config, output, jar, java='java', timeout=120, workers=2, heap='1g', trace=False):
    model, config, output, jar = map(lambda p: Path(p).resolve(), (model, config, output, jar))
    if timeout <= 0 or workers < 1 or not re.fullmatch(r'[1-9][0-9]*[mMgG]', heap):
        raise ValueError('Positive timeout/workers and heap such as 512m or 1g required')
    if model.parent != config.parent:
        raise ValueError('Model and configuration must share a directory')
    if not jar.is_file():
        raise ValueError('TLC jar not found')
    if not model.is_file() or not config.is_file():
        raise ValueError('Model or configuration not found')
    configured = '\n'.join(line.split('\\*', 1)[0] for line in config.read_text().splitlines())
    if trace and not re.search(r'\bINVARIANTS?\s+TraceIncomplete\b', configured):
        raise ValueError('Trace reachability invariant is not configured')
    if not trace and not re.search(r'\b(INVARIANTS?|PROPERT(?:Y|IES))\b', configured):
        raise ValueError('No correctness property configured')
    output.mkdir(parents=True, exist_ok=False)
    snapshot = output / 'inputs'
    snapshot.mkdir()
    inputs = {}
    for p in sorted(model.parent.iterdir()):
        if p.suffix in {'.tla', '.cfg', '.json', '.ndjson'} and p.is_file():
            shutil.copy2(p, snapshot / p.name)
            inputs[p.name] = hashlib.sha256(p.read_bytes()).hexdigest()
    command = [java, '-XX:+UseParallelGC', '-Xmx' + heap, '-cp', str(jar), 'tlc2.TLC',
               '-workers', str(workers), '-config', config.name, '-metadir', str(output / 'states')]
    if trace:
        command.append('-deadlock')  # Failed trace branches are expected terminal states.
    command.append(model.name)
    start = time.monotonic()
    result = 'error'
    code = None
    with (output / 'tlc.log').open('w') as log:
        try:
            process = subprocess.Popen(command, cwd=snapshot, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                code = process.returncode
                result = 'timeout'
        except OSError as exc:
            log.write(str(exc))
    logtext = (output / 'tlc.log').read_text()
    invariant = re.search(r'Invariant (\w+) is violated', logtext)
    initial = re.search(r'Finished computing initial states:\s+([0-9,]+) distinct state', logtext)
    initial_count = int(initial[1].replace(',', '')) if initial else None
    if result != 'timeout':
        if code == 0 and initial_count and 'Model checking completed. No error has been found.' in logtext:
            result = 'mismatch' if trace else 'pass'
        elif code == 12 and invariant:
            result = 'match' if trace and invariant[1] == 'TraceIncomplete' else 'violation'
        elif code == 11 and 'Deadlock reached' in logtext:
            result = 'deadlock'
        elif code == 13 and re.search(r'Temporal propert(?:y .+ was|ies were) violated', logtext):
            result = 'violation'
    receipt = {'result': result, 'exit_code': code, 'invariant': invariant[1] if invariant else None,
               'seconds': round(time.monotonic() - start, 3), 'initial_states': initial_count, 'command': command,
               'cwd': str(snapshot), 'inputs_sha256': inputs,
               'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'log': str(output / 'tlc.log')}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


if __name__ == '__main__':
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
    receipt = run(args.model, args.config, args.out, args.jar, args.java, args.timeout, args.workers, args.heap, args.trace)
    print(json.dumps(receipt))
    raise SystemExit(0 if receipt['result'] in {'pass', 'match'} else 1)
