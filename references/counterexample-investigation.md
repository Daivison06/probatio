# Counterexample investigation

A TLC counterexample is a precise path through the model. It is a bug candidate only after the path is shown to correspond to reachable implementation behavior and violate a supported contract.

## Triage each trace

1. Preserve the original TLC output, model/configuration version, and state sequence.
2. Find the shortest meaningful prefix that still violates the property. Explain which actions are necessary and which are incidental.
3. Map every action and state change to source operations, local snapshots, shared/durable fields, and actual atomicity boundaries.
4. Check that the initial state and each event ordering are reachable under the recorded environment and fault assumptions.
5. Check contract interpretation against its source. If unclear, keep the question open; do not silently weaken the property.
6. Inspect compensating guards, retries, reconciliation, rollback, uniqueness, and downstream consumers. State what evidence would refute the candidate.
7. Choose the next discriminating action: repair model, clarify contract, add an observation, or reproduce through the implementation.

Classify as `PROPERTY` (contract/property mismatch), `MODEL` (source behavior represented incorrectly), `CANDIDATE` (contract and source correspondence hold), or `UNRESOLVED`. Only `CANDIDATE` proceeds as an implementation defect hypothesis. Model and property errors still require preserved evidence and regression.

## Reproduction and disposition

Turn the reduced trace into a legal sequence using public operations where possible. Use controlled scheduling to expose the interleaving; never mutate the product to manufacture the trace. Capture the observable that violates the contract, verify the baseline and control, and record unsuccessful attempts without treating them as refutation. If reproduction is blocked, retain a source-supported or unresolved disposition with the precise missing evidence.

When correcting a model or instrumentation based on a counterexample, retain the original run, link the revision rationale to source, and execute the focused validation loop plus affected regression suite. A counterexample that disappears after a model edit is not itself evidence that the candidate was false.
