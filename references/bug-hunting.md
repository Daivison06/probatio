# Systematic bug hunting

Use this process to turn broad code reading into a set of falsifiable behavioral checks. It applies to the selected scope, not every file in a repository.

## Build a scenario matrix

For each relevant mechanism, enumerate dimensions that can change behavior. Select only dimensions supported by source or contract; mark omitted dimensions as gaps when they affect a scoped claim.

| Dimension | Questions |
|---|---|
| State | Empty, valid, stale, duplicated, partially persisted, terminal? |
| Ordering | Which actions can reorder, overlap, repeat, or arrive late? |
| Failure | What if failure occurs before, during, or after each effect? What is retried? |
| Identity | Which actor, tenant, owner, version, or generation is checked at each step? |
| Time | Which deadline, lease, timeout, expiry, or clock boundary matters? |
| Recovery | What restarts, reconciles, compensates, cleans up, or releases resources? |
| Observation | What exact state, response, event, durable write, or external effect proves the outcome? |

The matrix is a search guide, not a Cartesian-product mandate. Prioritize combinations that cross an atomicity boundary, change ownership, or separate a check from its use.

## Form and test hypotheses

Write each candidate as: **reachable precondition → action/event sequence → expected contract → observable consequence**. Anchor each clause to source or a contract. For every candidate, record a counterexample shape and at least one plausible disconfirming mechanism to inspect (lock, transaction, uniqueness constraint, retry guard, stale-version check, reconciliation, or downstream validation).

Keep a ledger with stable ID, mechanism, source anchors, contract citation, trigger, observable, proposed property/configuration, disconfirming check, status, and evidence paths. Keep rejected hypotheses with their reason: they prevent rediscovery and reveal which scope was actually checked. Do not promote a suspicious pattern to a finding until the trigger is reachable and the consequence matters to a real consumer.

## Search efficiently

Start with user priorities, high-risk transitions, known failure history, and code that crosses state or trust boundaries. Search callers and consumers as well as the method that looks suspicious. Add scenarios only when source evidence reveals another reachable path or semantic distinction. For broad scopes, state what was prioritized and what was not explored. “No candidate found” is not evidence that unexamined paths are safe.
