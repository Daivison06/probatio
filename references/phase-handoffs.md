# Phase handoffs and evidence gates

Use these gates for medium and large investigations. For a small scope, one `analysis.md` may contain all handoffs, but retain the same links and evidence requirements.

## Analysis → modeling

The `analysis.md` executive modeling brief must state:

- System execution shape: distributed/message-driven, concurrent shared-memory, transactional state machine, client async state, or a justified combination.
- Core state owners, lifecycle, external effects, and relevant crash/interleaving/linearization boundaries.
- Contract sources and observable outcomes.
- Scenario list grouped by failure mechanism, with source anchors, trigger, likely property, and disconfirming check.
- Proposed abstraction, fault assumptions, bounds, and known observability gaps.

**Gate:** every requested behavior maps to a scenario or an explicit exclusion, and source/contract questions are not concealed as assumptions.

## Modeling → instrumentation

Keep model checking and trace validation as distinct purposes. A typical suite has:

- A shared source-faithful base module for state and actions.
- One ordinary model-check configuration for baseline invariants and lifecycle properties.
- Focused hunt configurations for scenarios whose bounds/faults differ.
- A trace adapter that invokes the same base actions and checks observations.
- An instrumentation map connecting action, implementation boundary, captured fields, and limitations.

These names are conventions, not mandatory file proliferation. Avoid duplicated action definitions between MC and trace modules; divergence undermines the value of trace validation.

**Gate:** all scoped scenarios map to model properties/configurations, and the harness can observe the fields needed to distinguish the expected behavior. Gaps are explicit.

## Instrumentation → validation

Deliver a runnable command or test target that builds (if required), runs legal scenarios against actual critical implementation paths, collects traces, and reports failures. Keep environment setup, cleanup, and output locations explicit. For lock-free or high-contention paths, instrumentation must not add a global synchronization point that removes the target interleaving. Where a single shared clock is not trustworthy, record causal relations and use a custom adapter instead of inventing a total order.

**Gate:** traces are nonempty and carry source/capture provenance; positive, negative, malformed/empty, and relevant concurrency-order controls have been evaluated. Synthetic controls are labeled and never counted as implementation evidence.

## Validation → candidate confirmation

For each TLC violation, preserve the reduced counterexample and source mapping. Investigate the contract, legal preconditions, alternate paths, mitigations, and observable harm. Attempt an executable reproduction through the actual interface or a justified component seam. If reproduction cannot be run, retain a lower-evidence status and the exact blocker. Keep implementation fixes out of the analysis baseline unless explicitly requested.

**Gate:** each candidate has a stable ID, evidence-backed disposition, separate impact/severity and confidence, and reproducibility receipt or an explicit reason it is unavailable.

## Incremental handoff

Each handoff identifies run/source revision, scope, inputs, output paths, allowed modifications, expected artifacts, and a completion condition. When a later phase changes assumptions or discovers a modeling mismatch, send the finding back to the earlier artifact and rerun dependent gates. A phase is not complete just because its files exist.
