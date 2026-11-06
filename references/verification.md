# Verification and disposition

## Reproduction

Prefer the actual public interface and existing test harness. Establish preconditions through legal operations. If a component-level test is needed, document which callers, integrations, or environment properties it omits.

Use deterministic scheduling controls where possible. A barrier may expose a legal interleaving; changing guards, removing locks, fabricating unreachable state, or reverting an existing fix changes the system under investigation. Preserve that distinction in the evidence.

Check the baseline before attributing a failure. A missing dependency, assertion typo, network outage, or pre-existing test failure is not evidence of the hypothesized defect. Capture the observed value/error and compare it with a cited expected result. A control case should distinguish the mechanism when useful.

For timing-sensitive tests, record scheduling controls, attempts, and observed outcomes. Treat timeouts as incomplete observations until logs, stacks, or a concrete wait cycle establish the cause. Do not leave test processes running unobserved.

When a model or test misrepresents the source, repair the artifact and recheck. Preserve the earlier evidence and reason for revision. When the environment blocks one route, use another meaningful route if available and label its narrower conclusions.

## Candidate statuses

Assign one status per candidate. Keep novelty (`known`, `new-to-this-analysis`, `not-checked`) separate; absence of a tracker match does not prove global novelty.

| Status | Evidence required |
|---|---|
| `REPRODUCED` | Executed check on the recorded implementation reaches legal preconditions and demonstrates an observable contract violation. Include command, log, and reproduction artifact. |
| `SUPPORTED` | Source/path evidence establishes a defect and consequence, but execution is unavailable or incomplete. Name the missing environment or check; do not call it reproduced. |
| `MASKED` | A defect in a specific component contract is supported, while an identified surrounding mechanism prevents the downstream consequence. Explain both layers; a merely redundant safeguard is not a defect. |
| `REJECTED` | Positive evidence refutes the candidate: unreachable trigger, contract actually permits the behavior, or the implementation already prevents it. Explain why. |
| `UNRESOLVED` | Evidence cannot yet establish or refute the candidate. State the exact remaining question, blocker, and next discriminating check. |

A model counterexample alone remains `UNRESOLVED` until mapped back to reachable source behavior; it may then be `SUPPORTED` or `REPRODUCED` according to the evidence. A clean bounded check does not reject a code-level candidate outside the checked abstraction.

An explicitly requested analysis-only stage can finish that limited scope. Formal verification remains unperformed until TLC executes. Reproduction success does not substitute for model checking; a completed model check does not substitute for implementation reproduction.

## Severity and reporting

Keep confidence/status independent of impact. Use the repository's rubric when available. Otherwise describe realistic consequence, affected users/data, prerequisites, scope, and recovery, then assign:

- Critical: systemic compromise or broad irreversible loss supported by the evidence.
- High: substantial data integrity, access-control, or availability impact.
- Medium: limited wrong behavior or recoverable disruption.
- Low: minor demonstrated contract impact.

Do not inflate severity by imagining unsupported cascades. For `MASKED`, distinguish current impact from conditional impact if the mask changes. Use no severity for `REJECTED` or `UNRESOLVED`; unresolved hypotheses can still have a clearly labeled investigation priority.

Before completing, verify that every candidate has a disposition, each reproduced claim links to an executed check, each exclusion has a reason, and each user question has an answer or limitation. “No defect found within this scope” is valid; “the project is correct” is not established by this workflow.
