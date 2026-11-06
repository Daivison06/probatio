"""Two real threads demonstrate lost updates and an atomic corrected variant."""
import argparse
import json
from pathlib import Path
import sys
import threading
import time

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent.parent/'scripts'))
from regress import evaluate


def scenario(path, atomic):
    value=0
    events=[]
    observation=threading.Lock()
    reads_done=threading.Barrier(2,timeout=5)

    def worker(actor):
        nonlocal value
        def emit(action,seq,start):
            events.append(dict(actor=actor,seq=seq,start_ns=start,end_ns=time.monotonic_ns(),
                               action=action,data={'increment':1},state={'value':value,'atomic':atomic}))
        if atomic:
            with observation:
                start=time.monotonic_ns()
                value+=1
                emit('increment',1,start)
        else:
            with observation:
                start=time.monotonic_ns()
                cached=value
                emit('read',1,start)
            reads_done.wait()
            with observation:
                start=time.monotonic_ns()
                value=cached+1
                emit('write',2,start)
    threads=[threading.Thread(target=worker,args=(actor,)) for actor in ['A','B']]
    for t in threads:t.start()
    for t in threads:
        t.join(timeout=10)
        if t.is_alive():raise RuntimeError('Worker did not finish')
    if len(events)!=(2 if atomic else 4):raise RuntimeError('Incomplete trace')
    path.write_text(''.join(json.dumps(e)+'\n' for e in events))
    return value


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',required=True)
    parser.add_argument('--jar',required=True)
    parser.add_argument('--java',default='java')
    args=parser.parse_args()
    output=Path(args.out).resolve();output.mkdir(parents=True,exist_ok=False)
    results={}
    cases=[]
    for variant,atomic in [('Buggy',False),('Fixed',True)]:
        results[variant]=scenario(output/(variant+'.ndjson'),atomic)
        cases += [dict(name='mc-'+variant,model=str(HERE/'Counter.tla'),config=str(HERE/(variant+'.cfg')),expected='pass' if atomic else 'violation'),
                  dict(name='trace-'+variant,model=str(HERE/'Counter.tla'),trace=variant+'.ndjson',expected='match')]
    assert results=={'Buggy':1,'Fixed':2},results
    (output/'implementation-results.json').write_text(json.dumps(results,indent=2))
    (output/'suite.json').write_text(json.dumps(cases,indent=2))
    report=evaluate(output/'suite.json',output/'regression',args.jar,args.java)
    print(json.dumps([{k:v for k,v in r.items() if k!='evidence'} for r in report],indent=2))
    return 0 if all(r['accepted'] for r in report) else 1


if __name__=='__main__':raise SystemExit(main())
