# Refinement: the contract as a specification

An invariant states one thing that must never happen. A refinement check states everything that may happen: write the contract as a small abstract specification and let TLC check that every behavior of the source-grounded model is also a behavior of the abstract one (Lamport, *Specifying Systems*, sections 5.8 and 10.8). Use it when the contract is easier to describe as a simpler system than as a list of invariants: "this cache behaves like a single memory", "retries behave like one payment", "a request with an attacker present does only what an authorized request may do".

## Writing the check

1. **Abstract specification.** A separate module with only the state the contract talks about, one action per allowed visible change, and no implementation detail. Keep it small enough to read as the contract. Cite the contract source in `models/modeling.md`.
2. **Refinement mapping.** In the concrete model, define each abstract variable as an expression over concrete variables, then instantiate:

   ```tla
   Abstract == INSTANCE AbstractCounter WITH value <- value, done <- done
   Refinement == Abstract!Spec
   ```

   Concrete variables the abstract module does not mention are hidden. A concrete step that changes none of the mapped expressions is an abstract stutter step and is always allowed.
3. **Configuration.** `PROPERTY Refinement` with the concrete `INIT`/`NEXT` (or `SPECIFICATION`). A failure at the start is reported as an initial-state property violation; a failure later is an action property violation. Both are counterexamples (`violation`, exit 1).

`examples/counter` checks `Counter` against `AbstractCounter` ("each actor increments once, atomically"). The faulty variant violates it at the second write, the step that loses the update, and the corrected variant passes.

## Limits

- TLC needs an explicit mapping. When the abstract state cannot be written as a function of the current concrete state (for example, it depends on a decision the implementation makes later), the check needs history or prophecy variables. Record this as a gap rather than weakening the abstract specification.
- If the abstract specification has fairness, the check includes liveness: it is more expensive, it is unreliable under SYMMETRY or VIEW, and a finite model can hide a violation.
- A passing check covers the finite model and its bounds. It is not an unbounded refinement proof, and trace validation does not extend it to the implementation.
- An abstract specification that permits too much passes trivially. Give each refinement configuration a negative control: a known-faulty variant, or a mutated mapping, that must produce `violation`.
