# Artifact contracts

Use Markdown and ordinary logs so another agent can resume without a service or custom CLI. Create optional directories only when needed.

```text
.analysis/<run-id>/
  scope.md
  analysis.md              # source map, scenario matrix, candidate index, coverage gaps
  progress.md
  findings/F-001.md
  checks/                 # Executed commands, logs, reproduction artifacts
  models/                 # Required TLA+ modules, configs, correspondence, result matrix
  harness/                # Actual-code instrumentation and reproducible scenario commands
  traces/                 # Raw observed traces and source/capture provenance
  controls/               # Explicitly synthetic negative controls
  suite.json              # Joint model-checking and trace regression cases
  report.md
```

## scope.md

Record repository absolute path, source revision (or non-Git baseline description), relevant local changes, user questions, selected scope, expected contracts with sources, exclusions, environment assumptions, and analysis mode. Record how an isolated reproduction baseline includes local changes. Keep the output location separate from the implementation baseline.

## analysis.md

Record the source map, examined paths and flows, history search boundaries, scoped questions and evidence, scenario matrix, and candidate index. Assign stable `F-001`-style IDs. Each hypothesis links to its finding file; do not duplicate its full evidence here. Record unexamined areas, rejected hypotheses with concise reasons, and unresolved contracts.

For medium or complex scopes, include a scenario/candidate table with: ID, mechanism, legal trigger, contract, observable, source anchors, proposed property/configuration, disconfirming check, status, and evidence path. A small focused scope can keep this concise in `analysis.md` without a separate file.

## findings/F-001.md

Use these fields, with `not performed`, `unknown`, or `not applicable` where accurate:

- ID and mechanism-focused title.
- Source revision, affected locations/symbols, and candidate origin.
- Expected contract and supporting source.
- Reachable preconditions and trigger sequence.
- Observable expected and actual behavior; identify the affected consumer.
- Source evidence and compensating mechanisms investigated.
- Verification method and its scope.
- Executed command, working directory, exit status, and relative links to logs/tests/models.
- Status and rationale, using the verification reference.
- Novelty and evidence; current fix status if known.
- Impact/severity with prerequisites, confidence limits, and recovery.
- Suggested mitigation or next discriminating check.

Deduplicate by mechanism and site while retaining aliases and evidence. A file must never claim execution based only on a proposed command.

## progress.md

Maintain current phase, source baseline, completed outputs, candidate dispositions, active process/session handles, blockers, and next concrete action. On resume, observe any surviving process before launching replacements. Record changed assumptions and evidence invalidated by source changes.

## models/modeling.md

For each scoped question, link contract → source anchors → model actions/variables → property → configuration → result. Include the atomicity audit, model assumptions/bounds, completeness audit outcomes, known abstraction gaps, and model revision history. Record the exact model/configuration hashes associated with each checker receipt.

## report.md

Lead with the practical outcome and whether the requested scope is complete or partial. Include:

1. Scope, source baseline, and key exclusions.
2. Answers to every user question, linked to evidence.
3. Candidate table: ID, title, status, severity where justified, and evidence link.
4. Formal result matrix: question, property, model/configuration version, assumptions/bounds, exploration mode, termination, result, and evidence. Include trace provenance, observed-state coverage, causal assumptions, match/mismatch/error, negative controls, and joint regression receipts. Separate unperformed or interrupted runs; list implementation reproduction checks separately.
5. Remaining uncertainty, environment limitations, and prioritized next actions.

When no candidates were found, retain the scoped answers and coverage. Reconcile the table against all finding files. Claim completion of the scoped investigation, not correctness of the whole project. Write user-facing reports in the user's language.
