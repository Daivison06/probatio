# TLA+ modeling and TLC execution

TLA+ modeling and TLC execution are the core of this workflow. A state diagram, source review, or passing test alone does not complete formal verification. Choose a coherent finite abstraction for the user's question; record aspects that cannot be modeled faithfully as coverage gaps.

Locate a working Java runtime and genuine `tla2tools.jar`; verify TLC starts and record versions. If installation is needed and authorized, use official TLA+ release artifacts and record the release and checksum. Java requirements depend on the chosen release. This skill requires no Specula installation, MCP integration, or Maven build. If TLC is unavailable, continue useful modeling work but report formal verification as blocked. Respect offline constraints.

Create `models/modeling.md` plus purpose-specific modules where useful: `base.tla` for shared source-faithful actions; `MC.tla`/`MC.cfg` for the baseline properties; `MC_hunt_<scenario>.cfg` (or a small wrapper module) for focused assumptions and bounds; and `Trace.tla`/`Trace.cfg` for trace replay against those same base actions. Add `instrumentation.md` to map model actions to source capture points. Keep files consolidated for a genuinely small scope, but document which role each module/configuration serves. Make state variables, initial states, transitions, and enabled properties explicit. Split configurations when scenarios require different assumptions.

For standard TLC releases, these are illustrative invocations; consult the installed release's help, choose resources for the host, and follow its command-prefix rules:

```sh
java -cp /absolute/path/tla2tools.jar tla2sany.SANY models/MC.tla
java -Xmx2g -cp /absolute/path/tla2tools.jar tlc2.TLC -workers 2 -config models/MC.cfg models/MC.tla
```

Run from the investigation directory and retain output and exit status. The heap and worker values are examples. Use available process controls for execution budgets. Inspect both termination and TLC output to distinguish completion, violations, parser errors, timeout, OOM, and interruption.

## Model contract

Record in `models/modeling.md`:

- The open question, expected property, and its contract source.
- State variables and their correspondence to implementation fields or observations.
- Actions with source anchors and actual atomicity boundaries.
- Local snapshots, shared state, and durable state where their distinction matters.
- Initial states, admissible faults, bounds, fairness assumptions, and exclusions.
- Mapping from each scoped hypothesis to properties and configurations.

Model what the implementation does, including missing guards if present. Encode expected correctness as a property that can fail, not an assumption that makes a violation unreachable. Preserve interleaving windows rather than collapsing them to reduce state space. Faults must be possible in the stated environment; injecting a missing check that exists in the source manufactures a different program.

Before accepting the model, include an atomicity audit table in `modeling.md`: source operation, reads/writes/callbacks, actual synchronization or transaction boundary, model actions, and excluded interleavings. Multi-command methods are not atomic merely because they have one method name. Preserve local values between split read/write steps. Review exception boundaries around callbacks and persistence separately. A documented exclusion remains a coverage gap and cannot support a claim that the corresponding scoped behavior was exhaustively checked.

## Checks and evidence

Maintain a coverage matrix: scoped question, source anchors, property, configuration, result, and limitation. Unsupported abstractions and exclusions are gaps, not passing properties. Preserve the exact model/configuration version for each run, including superseded runs.

Parse/type-check first, then run finite configurations sized for the machine and question. Record exact tool/version, config, seed when relevant, resource limits, command, exit status, and output. Distinguish exhaustive exploration of a finite model from simulation, and both from an interrupted run.

Check that substantive properties are enabled, reachable, and capable of failing. Type predicates or vacuously false antecedents do not establish behavioral correctness. Record liveness assumptions explicitly. Prefer separate focused configurations per bug mechanism so a counterexample identifies the scenario and does not inherit unrelated fault assumptions. If exhaustive BFS reaches a shallow diameter and deeper behavior matters, use a bounded simulation strategy as complementary evidence; always label simulation separately from exhaustive exploration.

For each counterexample, follow the state changes back to the source and classify:

1. **Property mismatch:** the expected property was unsupported or expressed incorrectly. Cite the actual contract before revising it; preserve valid requirements.
2. **Model mismatch:** an action, initial state, fault, or abstraction does not represent reachable code. Correct the model and rerun affected configurations.
3. **Candidate implementation defect:** both the contract and source correspondence hold. Attempt code-level reproduction using the normal verification phase.

Preserve counterexamples and model revisions. An error-free finite run supports only its recorded model and bounds. State whether implementation trace validation was performed; without it, describe the source-to-model audit and its remaining limitations.
