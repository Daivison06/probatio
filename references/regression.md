# Joint regression and evidence

After changing source, model, properties, instrumentation, or observation mapping, rerun the affected model configurations **and** implementation trace checks. A model repair that passes one counterexample can invalidate previously matching traces. Regenerate traces if the source or capture semantics changed; old traces remain historical evidence.

Use `~/.probatio/java SKILL_DIR/scripts/Probatio.java regress` (see the toolchain note in SKILL.md) with a JSON list of cases. Paths are relative to the manifest:

```json
[
  {"name":"safety","model":"models/Model.tla","config":"models/MC.cfg","expected":"pass"},
  {"name":"observed","model":"models/TraceModel.tla","trace":"traces/run.ndjson","expected":"match"},
  {"name":"negative-state","model":"models/TraceModel.tla","trace":"controls/bad.ndjson","expected":"mismatch"}
]
```

```sh
~/.probatio/java SKILL_DIR/scripts/Probatio.java regress suite.json --out checks/round-01
```

Supported results are `pass`, `violation`, `deadlock`, `match`, `mismatch`, `timeout`, `error`, and `invalid`. `invalid` means the trace itself was rejected (empty, malformed, or outside the event contract). `error` covers every tool or setup failure, such as a missing file, an absent jar, or a TLC crash. `error` and `timeout` cannot be expected, so a broken run never satisfies a control. The whole manifest is validated before the first case runs; each case needs `name`, `model`, `expected`, and exactly one of `config` or `trace`. Expected violations and invalid inputs are for explicitly labeled controls or documented unresolved defects; a green regression suite does not imply the implementation has no bugs. Never change an expectation merely to make the suite green.

The runner snapshots sibling TLA+/cfg/JSON/NDJSON inputs, hashes them and the TLC JAR, saves command/output/exit status, and refuses an existing output directory. It covers finite breadth-first TLC runs, not proof checking or simulation. Inspect logs and coverage; the receipt classifies execution, not model adequacy. Explicitly map source revisions and trace provenance in the investigation's own manifest.

The runner rejects missing correctness-property configuration and treats an empty initial-state space as an error rather than a vacuous pass. It cannot automatically prove that a configured property is substantive: review its meaning and use discriminating controls. Test runner behavior from `SKILL_DIR` with `javac -d build/test scripts/Probatio.java examples/lease/LeaseExample.java tests/ProbatioTest.java && TLA_JAR=/path/tla2tools.jar java -cp build/test ProbatioTest`; without `TLA_JAR` only the input-validation tests run.

Run `java examples/lease/LeaseExample.java --out /fresh/path` for the portable executable example. Its actual Java implementation emits observed traces. The defective variant illustrates stale lease release; the guarded variant retains the newer owner. These are independent teaching fixtures, not copies of client code or evidence of a production defect. The example checks safety and traces together, including corrupted-state, unknown-action, empty and malformed controls.

Run `java examples/counter/CounterExample.java` with the same options for a second mechanism: two actual threads demonstrate a lost update and its atomic corrected variant. Both variants generate implementation traces and run model/trace regression. Read each example's `EXAMPLE.md` for its assumptions and coverage limits.

For efficacy comparison, freeze source, task, skill version, model identity/effort, resources and tooling per arm. Keep outputs separate and prohibit reading the other arm's findings. Record setup failures separately from completed analysis. Audit discovered candidates and reproduction evidence rather than comparing raw bug counts. A single component and single run per arm is a case study, not a statistical ranking.
