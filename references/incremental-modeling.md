# Incremental modeling and completeness review

Build models in small, reviewable steps. The goal is a model that captures the mechanism behind a scoped question, not a line-for-line translation of the repository.

## Modeling stages

1. **Reconnaissance:** identify the entry point, state owners, relevant operations, synchronization/transaction boundaries, environment assumptions, and expected contract. Record source anchors before writing operators.
2. **Reference model:** encode the smallest valid initial state and normal lifecycle. Parse it and check types, invariants, and reachable terminal states. This establishes the baseline behavior and helps expose vacuity.
3. **Focused analysis:** select one candidate mechanism or contract question. State the legal trigger, expected observable, and distinguishing scenario. Make a dedicated property/configuration when assumptions differ.
4. **Model the mechanism:** add only the state, operation split, fault, or interleaving needed for that scenario. Preserve source atomicity and split operations wherever another actor can run between effects.
5. **Check and review:** parse, run focused TLC checks, inspect every counterexample, and complete the audit below. Then run the applicable shared configurations and trace regressions.

Keep focused models/configurations small enough to understand counterexamples. Share definitions where semantics truly match; do not force unrelated subsystems into one huge state space. A subsystem boundary is useful when it has distinct state ownership, lifecycle, contracts, or fault assumptions. Document the interface assumptions where models are composed or checked separately.

## Completeness audit

For every scoped question, verify and record:

- The contract and observable outcome are explicit.
- Initial states include the relevant legal variants, not just the happy path.
- Every source operation affecting the property is represented, including alternate entry points and cleanup/recovery paths.
- Action granularity matches actual atomicity. Reads, checks, writes, callbacks, persistence, and publication are split at real interleaving boundaries.
- Actors retain local snapshots where implementation code does; shared and durable state are not conflated.
- Relevant faults, retries, duplicate/reordered events, cancellation, restart, or clock transitions are modeled only when admissible in the target environment.
- Bounds and fairness assumptions are stated, justified, and aligned with the question.
- Properties are enabled, reachable, non-vacuous, and capable of failing under a meaningful negative control where feasible.
- Each hypothesis maps to a property/configuration or to a specific coverage gap.
- Excluded behavior and abstractions that can hide a defect are explicit.

Add rows to `models/modeling.md` for each gap, with impact and the next check that could close it. Do not describe the model as complete merely because TLC finishes. A completeness review is a scope-relative audit, not a proof that the implementation is fully represented.

## Change discipline

When a counterexample reveals a missing behavior, update the model with a source citation and explain the semantic change. Preserve the previous model and result. Rerun parse, focused checks, affected shared configurations, and trace validation. Never remove a contract or restrict initial states/faults solely to eliminate a violation; any contract reinterpretation requires supporting evidence and a recorded decision.
