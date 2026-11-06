# Methodological provenance

This is an independently written workflow informed by Specula's public skills, inspected at commit `39b4a0150a2eb313f3d9abbc78fb41a0d756067f`:

- [Code analysis](https://github.com/specula-org/Specula/tree/39b4a0150a2eb313f3d9abbc78fb41a0d756067f/skills/code_analysis): mechanism-based scenarios, history, atomicity boundaries, compensating mechanisms.
- [Modeling guidance](https://github.com/specula-org/Specula/tree/39b4a0150a2eb313f3d9abbc78fb41a0d756067f/skills/writing-modeling-guidance): explicit scope and questions rather than predetermined answers.
- [Spec generation](https://github.com/specula-org/Specula/tree/39b4a0150a2eb313f3d9abbc78fb41a0d756067f/skills/spec_generation) and [model checking](https://github.com/specula-org/Specula/tree/39b4a0150a2eb313f3d9abbc78fb41a0d756067f/skills/tla-checking-workflow): source correspondence and counterexample classification.
- [Confirmation](https://github.com/specula-org/Specula/tree/39b4a0150a2eb313f3d9abbc78fb41a0d756067f/skills/bug-confirmation) and [classification](https://github.com/specula-org/Specula/tree/39b4a0150a2eb313f3d9abbc78fb41a0d756067f/skills/bug-classification): reachable triggers, observable consequences, and separate status/impact.
- [Specula Lite](https://github.com/specula-org/Specula/tree/39b4a0150a2eb313f3d9abbc78fb41a0d756067f/skills/specula-lite): a focused investigation can run within one agent.

## Deliberate changes

Real projects inspected during development served only as sources of technical examples, not as the target domain of this skill. Those read-only inspections did not establish formal results or confirmed defects. Reusable guides must remain independent of any such project; example construction follows `generality.md`.

The workflow supports application state machines as well as concurrent/distributed protocols. TLA+ modeling and TLC execution are required; source analysis and tests support formal verification and implementation confirmation. It uses standalone artifacts and Java/TLC directly, without upstream scripts, MCP integration, or upstream skill dependencies.

History coverage follows the selected scope rather than a fixed issue quota. Known defects remain relevant. Parallel agents are conditional on explicit authorization. Resource limits follow the available machine and the investigation, not upstream cluster-sized examples.

Implementation determines what can happen; contracts determine what should happen. Counterexamples do not automatically confirm production defects, timeouts do not automatically prove deadlocks, and severity follows supported impact rather than a preference for higher classification.

Upstream is a design reference, not executable instructions for this pipeline. No local Specula checkout is required to use these files.
