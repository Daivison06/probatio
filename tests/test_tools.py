import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
sys.path.insert(0,str(ROOT/'examples/lease'))
from implementation import scenario
from trace import compile_trace, read_events
from tlc import run
from regress import evaluate


class ToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.jar = os.environ['TLA_JAR']
        self.java = os.environ.get('JAVA','java')
        self.model = ROOT/'examples/lease/Lease.tla'

    def tearDown(self):
        self.temp.cleanup()

    def execute(self, model, config, name, **kwargs):
        return run(model,config,self.root/name,self.jar,self.java,**kwargs)

    def test_partial_order_search_accepts_one_valid_branch(self):
        # Synthetic validator control, not a trace claimed as implementation evidence.
        events = [
            dict(actor='B',seq=1,start_ns=1,end_ns=8,action='acquire',data={'key':'x'},state={'owner':'B','guarded':True}),
            dict(actor='A',seq=1,start_ns=2,end_ns=3,action='acquire',data={'key':'x'},state={'owner':'A','guarded':True}),
            dict(actor='A',seq=2,start_ns=4,end_ns=5,action='release',data={'key':'x'},state={'owner':'','guarded':True}),
        ]
        path=self.root/'overlap.ndjson'
        path.write_text(''.join(json.dumps(e)+'\n' for e in events))
        model,cfg=compile_trace(self.model,path,self.root/'compiled')
        self.assertEqual(self.execute(model,cfg,'valid',trace=True)['result'],'match')
        events[0]['state']['owner']='C'
        path.write_text(''.join(json.dumps(e)+'\n' for e in events))
        model,cfg=compile_trace(self.model,path,self.root/'impossible')
        self.assertEqual(self.execute(model,cfg,'invalid-order',trace=True)['result'],'mismatch')

    def test_no_false_pass_on_timeout_parse_error_or_disabled_check(self):
        result=self.execute(self.model,ROOT/'examples/lease/Fixed.cfg','timeout',timeout=0.000001)
        self.assertEqual(result['result'],'timeout')
        model=self.root/'Broken.tla'; model.write_text('---- MODULE Broken ----\nTHIS IS NOT VALID\n====')
        cfg=self.root/'Broken.cfg'; cfg.write_text('INIT Init\nNEXT Next\nINVARIANT Safe\n')
        self.assertEqual(self.execute(model,cfg,'parse')['result'],'error')
        cfg.write_text('INIT Init\nNEXT Next\n\\* INVARIANT Safe\n')
        with self.assertRaisesRegex(ValueError,'No correctness'):
            self.execute(model,cfg,'disabled')

    def test_deadlock_and_liveness_are_not_tool_errors(self):
        model=self.root/'Progress.tla'
        model.write_text('---- MODULE Progress ----\nEXTENDS Naturals\nVARIABLE x\nInit == x=0\nStop == FALSE /\\ x\'=x\nLoop == x\'=x\nSafe == x=0\nEventually == <>(x=1)\n====\n')
        cfg=self.root/'Progress.cfg'
        cfg.write_text('INIT Init\nNEXT Stop\nINVARIANT Safe\n')
        self.assertEqual(self.execute(model,cfg,'deadlock')['result'],'deadlock')
        cfg.write_text('INIT Init\nNEXT Loop\nPROPERTY Eventually\n')
        self.assertEqual(self.execute(model,cfg,'liveness')['result'],'violation')

    def test_empty_initial_state_space_is_not_a_pass(self):
        model=self.root/'Empty.tla'
        model.write_text("---- MODULE Empty ----\nEXTENDS Naturals\nVARIABLE x\nInit == x=0 /\\ x=1\nNext == x'=x\nSafe == x=0\n====\n")
        cfg=self.root/'Empty.cfg';cfg.write_text('INIT Init\nNEXT Next\nINVARIANT Safe\n')
        self.assertEqual(self.execute(model,cfg,'empty-init')['result'],'error')

    def test_raw_trace_rejects_missing_fields_and_bad_sequences(self):
        p=self.root/'raw.ndjson'; scenario(p,True)
        rows=[json.loads(l) for l in p.read_text().splitlines()]
        rows[-1]['seq']=99
        p.write_text(''.join(json.dumps(e)+'\n' for e in rows))
        with self.assertRaises(ValueError): read_events(p)
        rows[-1]['seq']=2; rows[-1]['state']={}
        p.write_text(''.join(json.dumps(e)+'\n' for e in rows))
        with self.assertRaises(ValueError): read_events(p)

    def test_joint_regression_records_failure_and_preserves_inputs(self):
        p=self.root/'raw.ndjson'; scenario(p,True)
        cases=[{'name':'safety','model':str(self.model),'config':str(ROOT/'examples/lease/Fixed.cfg'),'expected':'pass'},
               {'name':'trace','model':str(self.model),'trace':str(p),'expected':'match'}]
        manifest=self.root/'suite.json';manifest.write_text(json.dumps(cases))
        first=evaluate(manifest,self.root/'round1',self.jar,self.java)
        self.assertTrue(all(r['accepted'] for r in first))
        saved=(self.root/'round1/trace/inputs/source.ndjson').read_text()
        rows=[json.loads(l) for l in p.read_text().splitlines()];rows[-1]['state']['owner']=''
        p.write_text(''.join(json.dumps(e)+'\n' for e in rows))
        second=evaluate(manifest,self.root/'round2',self.jar,self.java)
        self.assertTrue(second[0]['accepted']);self.assertFalse(second[1]['accepted'])
        self.assertEqual(second[1]['actual'],'mismatch')
        self.assertEqual((self.root/'round1/trace/inputs/source.ndjson').read_text(),saved)
        with self.assertRaises(FileExistsError):
            evaluate(manifest,self.root/'round1',self.jar,self.java)


if __name__=='__main__': unittest.main()
