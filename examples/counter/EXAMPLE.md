# Concurrent counter example

Run `java CounterExample.java --out /fresh/path` (add `--jar /path/tla2tools.jar` if it is not in `~/.probatio`).

Two actual Java threads each increment once. In the faulty variant, each reads before a barrier and then writes its cached value plus one, so the final value is one. The corrected variant protects the whole increment, producing two. The observation lock protects each individual read or write and its capture; it does not make the faulty read-modify-write atomic. The barrier forces a legal lost-update interleaving. This is a scheduling test, not a weak-memory or JVM implementation proof.

The model represents two actors and one increment per actor. `NoLostUpdate` compares the stored value with completed writes. Both real traces must match their model behavior; the safety configuration rejects the faulty variant and completes for the atomic variant. The refinement configurations (`RefinesBuggy.cfg`, `RefinesFixed.cfg`) state the same contract as a specification, `AbstractCounter.tla`: each actor increments once, in one atomic step. The faulty variant violates it at the second write, the step that loses the update; the atomic variant refines it. No fairness/liveness property is claimed. The fixture is independently written and has no external project dependency.
