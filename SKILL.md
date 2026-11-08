---
name: probatio
description: Formally verifies software behavior through source-grounded TLA+ models, TLC model checking, counterexample investigation, and code-level reproduction. Use when a project needs formal validation of behavioral properties or systematic bug discovery, including state machines, algorithms, permissions, resource lifecycles, concurrency, and distributed workflows.
---

# Probatio: formal verification with TLA+

Run a source-grounded formal verification pipeline. TLA+ modeling and actual TLC execution are required stages; source review and tests support them rather than replace them. Missing tooling or an unsuccessful checker run leaves formal verification incomplete. This skill is self-contained: Specula, its skills, MCP servers, and agent launchers are not dependencies. Local artifacts do not imply offline inference; the host agent's existing model provider still applies.

## Start and resume

This is a project-independent methodology. Select scope by the behavioral property and modeling suitability, not by framework, language, or business domain. The bundled examples are illustrative only; their architecture, dependencies, contracts, and paths are never prerequisites or defaults for a target project. Read [generality.md](references/generality.md) when choosing a modeling scope or adapting a technical example.

Use the repository and priorities already supplied. Ask only for a missing target or a contract ambiguity that changes the investigation. With a broad request, choose a coherent subsystem from the source, explain the scope, and proceed. An explicitly requested analysis-only stage produces preparation artifacts, not completed formal verification. Honor offline constraints and report unavailable tooling concretely.

Read repository instructions. Record source revision, relevant uncommitted changes, and the actual test/tool environment. Create a fresh `.analysis/<run-id>/` in the target, or use the user's output directory. If artifacts cannot be written there, use an external directory and record its absolute path.

Read [artifacts.md](references/artifacts.md) and [phase-handoffs.md](references/phase-handoffs.md) for output contracts and gates. To initialize an analysis directory, choose a path that does not exist yet (never overwrite existing work), copy `SKILL_DIR/assets/workspace/` into it, and add empty `findings/`, `checks/`, `harness/`, `traces/`, and `controls/` directories. Fill every `$placeholder` in `scope.md`: run id (the directory name), UTC timestamp, absolute source path, `git rev-parse HEAD`, a working-tree summary (`clean`, or the SHA-256 of `git diff HEAD --binary`, the count of untracked files, and the SHA-256 of `git ls-files -z --others --exclude-standard | xargs -0 shasum -a 256`), `git status --short`, and the requested scope.

The bundled tools need a JDK 17+ and `tla2tools.jar`. Run `sh SKILL_DIR/scripts/install.sh` first (Windows: `powershell -NoProfile -ExecutionPolicy Bypass -File SKILL_DIR\scripts\install.ps1`). It is idempotent, reuses a usable JDK on `PATH`, and otherwise installs pinned, checksum-verified versions into `~/.probatio` without admin rights. Then run every tool through the JDK it selected, which also finds the jar by default: `~/.probatio/java SKILL_DIR/scripts/Probatio.java <tlc|trace|regress> ...` (Windows: `%USERPROFILE%\.probatio\java.cmd`). Plain `java` may be a JRE, an older JVM, or a stub. On resume, read `progress.md`, the scope, and existing findings first; compare the current source with the recorded revision and local changes. Recheck affected evidence before reusing it. For a new source revision, follow [incremental-revision.md](references/incremental-revision.md).

## Pipeline

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

- “Use $probatio to model payment retries and check duplicate-processing scenarios with TLC.”
- “Use $probatio to model tenant access transitions and check isolation.”
- “Resume $probatio from `.analysis/payment-retries/progress.md`.”

For methodological provenance and deliberate differences from Specula, read [provenance.md](references/provenance.md).
