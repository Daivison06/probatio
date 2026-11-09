---
name: probatio
description: Formally verifies software behavior through source-grounded TLA+ models, TLC model checking, counterexample investigation, and code-level reproduction. Use when a project needs formal validation of behavioral properties or systematic bug discovery, including state machines, algorithms, permissions, resource lifecycles, concurrency, and distributed workflows.
---

# Probatio: formal verification with TLA+

Run a source-grounded formal verification pipeline. TLA+ modeling and actual TLC execution are required stages; source review and tests support them rather than replace them. Missing tooling or an unsuccessful checker run leaves formal verification incomplete. This skill is self-contained: Specula, its skills, MCP servers, and agent launchers are not dependencies. Local artifacts do not imply offline inference; the host agent's existing model provider still applies.

## Tools

Every tool is a subcommand of one source file. `J` below is `~/.probatio/java` (Windows: `%USERPROFILE%\.probatio\java.cmd`), the JDK the installer selected; plain `java` may be a JRE, an older JVM, or a stub.

```sh
sh SKILL_DIR/scripts/install.sh            # once; idempotent. Windows: powershell -NoProfile -ExecutionPolicy Bypass -File SKILL_DIR\scripts\install.ps1
J SKILL_DIR/scripts/Probatio.java doctor   # first, every session: JDK, jar, TLC and Git, with a fix for each failure
J SKILL_DIR/scripts/Probatio.java init --source REPO --out REPO/.analysis/RUN_ID --scope "behavior to verify"
J SKILL_DIR/scripts/Probatio.java tlc models/M.tla models/M.cfg --out checks/RUN
J SKILL_DIR/scripts/Probatio.java trace models/M.tla traces/run.ndjson --out checks/RUN-input
J SKILL_DIR/scripts/Probatio.java tlc checks/RUN-input/TraceCheck.tla checks/RUN-input/TraceCheck.cfg --trace --out checks/RUN-trace
J SKILL_DIR/scripts/Probatio.java regress suite.json --out checks/round-NN   # model and trace cases together
```

Branch on the exit code, then read the JSON on stdout (`tlc` also writes `receipt.json`, `regress` writes `results.json`):

| Exit | Meaning | Next step |
| --- | --- | --- |
| 0 | Accepted: `pass`, `match`, or every regression case as expected | Record the receipt |
| 1 | Checked and not accepted: `violation`, `deadlock` or `mismatch` (TLC produced a verdict), `invalid` (the trace was rejected before TLC ran), or an unexpected regression result | For a TLC verdict, investigate the counterexample: this is evidence. For `invalid`, fix the instrumentation or the trace: no counterexample exists |
| 2 | Not checked: `error`, `timeout`, or a setup problem | Read the message or `tlc.log` and fix the setup; this is never evidence about the model |

`invalid` is only acceptable as an expected negative control. Exit 1 with no JSON on stdout means the tool itself did not run (for example, it failed to compile on a JVM older than 17): treat it like 2. After `regress` exits 1 or 2, read every case in `results.json`, because one unchecked case sets the exit code to 2 even when another case has a real counterexample. Setup errors say what to change. Do not edit expectations, configurations, or traces just to turn a 1 or 2 into a 0.

## Start and resume

This is a project-independent methodology. Select scope by the behavioral property and modeling suitability, not by framework, language, or business domain. The bundled examples are illustrative only; their architecture, dependencies, contracts, and paths are never prerequisites or defaults for a target project. Read [generality.md](references/generality.md) when choosing a modeling scope or adapting a technical example.

Use the repository and priorities already supplied. Ask only for a missing target or a contract ambiguity that changes the investigation. With a broad request that names no behavior to verify, run the survey (stage 0) to choose a coherent subsystem from the source, explain the scope, and proceed. An explicitly requested analysis-only stage produces preparation artifacts, not completed formal verification. Honor offline constraints and report unavailable tooling concretely.

Read repository instructions. Record source revision, relevant uncommitted changes, and the actual test/tool environment. Create a fresh `.analysis/<run-id>/` in the target, or use the user's output directory. If artifacts cannot be written there, use an external directory and record its absolute path.

Read [artifacts.md](references/artifacts.md) and [phase-handoffs.md](references/phase-handoffs.md) for output contracts and gates. Run `doctor`, then create the analysis directory with `init` (see Tools); it copies the workspace templates, stamps `scope.md` with the revision, working-tree digests, and Git status, and refuses an existing path so earlier work is never overwritten. On resume, read `progress.md`, the scope, and existing findings first; compare the current source with the recorded revision and local changes. Recheck affected evidence before reusing it. For a new source revision, follow [incremental-revision.md](references/incremental-revision.md).

## Pipeline

0. **Survey (broad requests only).** Read [state-machine-survey.md](references/state-machine-survey.md). Find the implicit state machines in the source, with their states, transitions, writers, and impossible combinations, and rank them by risk without changing code. Write the ranking to `analysis.md`. Done when the top-ranked machine (or the user's pick) has a proposed scope, variables, and invariant candidates; stop here if only the survey was requested.
1. **Scope.** Write `scope.md`: the user's questions, expected contracts and their sources, selected boundaries, exclusions, and assumptions. Separate supplied priorities from agent-selected scope. Done when each priority can be investigated as a concrete trigger and observable outcome.
2. **Map and investigate.** Read [investigation.md](references/investigation.md) and [bug-hunting.md](references/bug-hunting.md). Follow relevant entry points through state changes, external effects, errors, and recovery. Build a mechanism-driven scenario matrix, investigate legal triggers and observables, and maintain a candidate ledger with disconfirming evidence. Use scoped local history to guide exploration. Write `analysis.md` with coverage and hypotheses. Done when every scoped question has source evidence or a named gap.
3. **Model incrementally.** Read [formal.md](references/formal.md) and [incremental-modeling.md](references/incremental-modeling.md). Start with the smallest source-grounded model that can express a scoped property; add one mechanism or fault at a time and keep focused configurations for distinct questions. Run a completeness review before accepting the model. Map every scoped question to properties and configurations or an explicit modeling limitation. Done when atomicity boundaries, source correspondence, assumptions, bounds, coverage, and model review are documented and the modules parse.
4. **Instrument.** Read [instrumentation.md](references/instrumentation.md). Map actions to capture points and execute an isolated harness against actual implementation paths. Reuse existing project tests when possible; otherwise specify the minimal reproducible harness and its build/run/cleanup steps. Save nonempty traces, provenance, field coverage, and observation limitations. A reimplementation or mocked critical mechanism is not evidence of correspondence with the target.
5. **Validate traces.** Read [trace-validation.md](references/trace-validation.md). Check whether the shared model admits complete observed executions, including captured state and causal order. Require a rejected negative control. Diagnose mismatches before changing artifacts; preserve raw evidence. Missing implementation traces leave this stage incomplete.
6. **Model-check and regress.** Execute TLC on recorded configurations and read [regression.md](references/regression.md) and [validation-loop.md](references/validation-loop.md). Check safety and applicable liveness, non-vacuity, and coverage. Classify counterexamples as property mismatch, model mismatch, or candidate implementation defect. Every model or observation-mapping repair triggers the focused loop, then the affected full regression. Preserve contracts and versions; record violations separately from tool failures.
7. **Investigate and reproduce candidates.** Read [verification.md](references/verification.md) and [counterexample-investigation.md](references/counterexample-investigation.md). Reduce counterexamples to their essential trigger, map each transition to reachable implementation paths, seek disconfirming mechanisms, and attempt code-level reproduction. Feed modeling errors discovered here back into stages 3–6. Done when each candidate has a supported disposition or a concrete blocker; a failed reproduction alone does not disprove it.
8. **Report.** Write `report.md` with property/configuration and trace result matrices, candidate dispositions, and regression receipts. Separate completed finite checks, trace matches, simulations, incomplete runs, and reproduced defects. Address every scoped question and report abstraction, bounds, fairness, and source-correspondence limits. A finite model-checking result is not an unbounded proof of implementation correctness.

Update `progress.md` at phase boundaries and before a handoff. Continue through the pipeline without approval between stages. Follow an explicitly requested analysis-only, single-stage, or stop-after boundary instead.

## Execution and handoffs

Use one agent by default. When the user or repository explicitly authorizes parallel work, assign independent scenarios with disjoint output files. Each handoff carries the repository/revision, scope, candidate IDs, input evidence, allowed writes, expected output, and completion criterion. The coordinating agent alone maintains the shared index and final report and checks interactions between findings.

Preserve implementation behavior during investigation. Put reproduction changes in an isolated copy/worktree when needed; include relevant local edits in that baseline. Applying product fixes is a separate task unless already requested. Use local test fixtures for external effects; publishing issues or touching production is outside this skill's default workflow.

Record the command, working directory, tool version when relevant, exit status, and log for executed checks. Observe started processes until completion or an explicit recorded interruption. Bound potentially hanging reproductions with available test-runner or process timeouts; a timeout is an observation to investigate, not proof of deadlock.

## Examples

- “Use $probatio to find the implicit state machines in this app and rank them by risk. Don't change any code yet.”
- “Use $probatio to model payment retries and check duplicate-processing scenarios with TLC.”
- “Use $probatio to model tenant access transitions and check isolation.”
- “Resume $probatio from `.analysis/payment-retries/progress.md`.”

For methodological provenance and deliberate differences from Specula, read [provenance.md](references/provenance.md).
