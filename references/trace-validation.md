# Semantic trace validation

Trace validation asks whether **at least one** model execution agrees with the observed events and state. It is distinct from checking whether every model execution satisfies a safety property. A real buggy trace can match a faithful model while violating a correctness property.

## Model interface for bundled tools

Provide a TLA+ module with:

- `Init`: legal initial states, with observed initialization constraints where necessary.
- `Step(e)`: an action dispatching on `e.action`, `e.actor`, and `e.data`. It must call the same base actions used by model checking and assign all model variables.
- `Observe`: a nonempty record of the observations captured in `e.state`.

The module must be self-contained with sibling TLA+ dependencies and standard modules; the bundled compiler does not supply external constant assignments or community modules. Use a small wrapper module to instantiate a parameterized base specification with explicit constants. The compiler refuses a model directory whose `.tla` files do not define `Init`, `Step(e)`, and `Observe`. Unknown actions should evaluate to `FALSE`. The default adapter supports one base action per observation and no silent actions; finer-grained instrumentation or a separately justified custom wrapper is needed for opaque intermediate steps.

Compile and execute:

```sh
~/.probatio/java SKILL_DIR/scripts/Probatio.java trace models/Model.tla traces/scenario.ndjson --out checks/scenario-input
~/.probatio/java SKILL_DIR/scripts/Probatio.java tlc checks/scenario-input/TraceCheck.tla checks/scenario-input/TraceCheck.cfg --trace --out checks/scenario-result
```

Replace `SKILL_DIR` and obey host command-prefix requirements. `--jar` defaults to `~/.probatio/tla2tools.jar` (override with `--jar` or `PROBATIO_HOME`). Use fresh output paths for each invocation.

## Acceptance semantics

The wrapper consumes events whose causal predecessors are already consumed. It calls `Step(e)` and requires `Observe' = e.state`. It explores admissible orders of overlapping events. It checks the deliberately negated completion invariant `TraceIncomplete`.

- TLC **violates `TraceIncomplete`**: a complete matching execution was reached; this is a trace **match**, not an implementation bug.
- TLC exhausts the finite search without reaching completion: **mismatch**.
- Timeout, parse/evaluation error, memory failure, or interruption: **inconclusive/error**, never mismatch or match.

Deadlock checking is disabled only for this reachability query: unmatched branches are expected to terminate. This is not permission to disable deadlock checks in ordinary model checking. No unrelated safety invariants belong in this generated query; check them separately on the shared model.

For models with multiple possible initial states or interleavings, universal eventual-consumption properties can reject an otherwise matchable trace. Preserve the existential semantics above when replacing the adapter. A clean generic TLC exit alone never demonstrates a trace match.

## Diagnose a mismatch

Inspect the last compatible prefix/branch and compare the next event with the action guard and observed values. Classify the cause as capture timing/order, identifier/state mapping, model behavior, or an unsupported abstraction. Re-read source before changing base actions. Preserve requirements independently of implementation behavior. Never bypass guards, make observation checks `TRUE`, or hide events to force acceptance.

Run a negative control that changes an actually checked state field. It must fail to match. Also reject empty/malformed traces and test unknown actions. For partial-order handling, test a trace with overlapping events and at least one valid ordering, plus an impossible case. Synthetic controls test the validator; label them separately from implementation evidence.
