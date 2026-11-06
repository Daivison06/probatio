# Investigation

## Source map

Trace the scoped operation from its actual caller to its observable outcome. Record relevant components, state owners, persistence, trust boundaries, external services, and tests. Follow helpers and downstream consumers far enough to establish behavior. Mark partial reads honestly rather than claiming whole-repository coverage.

Separate three sources of truth:

- The implementation establishes reachable behavior.
- Contracts, specifications, and user requirements establish expected behavior.
- Tests and comments are evidence of intent; they can themselves be wrong.

An implementation's behavior cannot alone justify weakening an expected contract.

## History

Search local history for fixes in the scoped paths and mechanisms. Read relevant diffs and their current callers; distinguish a historical vulnerability from a defect in today's source. Record paths, revision ranges, and limitations such as a shallow clone.

Consult remote issues or design references only when relevant and allowed by the task. Read the discussion supporting any claim, including rebuttals and fix status. Local-only investigations record remote history as unexamined. There is no minimum issue or commit quota.

Known status is separate from correctness. Keep a still-present known defect with its citation; deduplicate by mechanism and affected site. A TODO is a lead, not proof of a defect or of a prior report.

## Mechanism-driven questions

Use only the branches supported by the selected code; these are investigation prompts, not mandatory findings.

| Branch | Questions |
|---|---|
| Transactions and business state | Can validation become stale before a write? Which operations commit atomically? What survives partial failure? Are units, rounding, and boundary values consistent with the contract? |
| Permissions and tenants | Where is identity established? Is authorization checked on every reachable path and object? Do joins, caches, jobs, and exports preserve tenant boundaries? |
| Queues and integrations | Can delivery or callbacks repeat or reorder? Is deduplication durable and atomic with the effect? What does an ambiguous timeout mean? Can cancellation race with completion? |
| Concurrent state | What can interleave between read, check, reserve, publish, and release? Do fast and slow paths enforce the same guards? Who owns cleanup, wakeup, and object lifetime? |
| Distributed state | Which state is durable versus in memory? Can crash/restart, stale messages, or independent control loops violate recovery or ordering guarantees? Are assumed faults admissible? |
| Client state | Can stale responses overwrite newer intent? Do navigation, cancellation, retries, and optimistic rollback preserve visible state? |

For atomics, distinguish language memory ordering from scheduling interleavings. Explicitly record weak-memory behavior outside the TLA+ abstraction. Language-aware checkers and sanitizers can provide complementary evidence; they do not replace the required TLA+/TLC stages.

## Candidate formation

State a hypothesis as: “Given reachable preconditions P, operation/event sequence S may violate contract C, causing observable outcome O.” Cite the current source and the contract. An unclear contract becomes an open question, not an invented invariant.

Before promoting a candidate:

1. Re-read exact code and trace its caller preconditions.
2. Inspect alternate paths, locks, transaction isolation, uniqueness constraints, retries, reconciliation, and downstream consumers for compensating behavior.
3. Describe a legal trigger and a real consumer of the wrong result.
4. Identify what evidence would disprove the hypothesis.
5. Propose a TLA+ property, relevant transitions, and a complementary reproduction strategy; explain abstraction limits.

Group candidates by failure mechanism rather than file. Complete the analysis stage when each scoped question has a supported answer, candidate, or explicit gap, and every candidate has enough evidence to design a discriminating check.
