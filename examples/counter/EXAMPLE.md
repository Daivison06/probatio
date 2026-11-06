# Concurrent counter example

Run `python3 run.py --out /fresh/path --jar /path/tla2tools.jar --java /path/java`.

Two actual Python threads each increment once. In the faulty variant, each reads before a barrier and then writes its cached value plus one, so the final value is one. The corrected variant protects the whole increment, producing two. The observation lock protects each individual read or write and its capture; it does not make the faulty read-modify-write atomic. The barrier forces a legal lost-update interleaving. This is a scheduling test, not a weak-memory or interpreter implementation proof.

The model represents two actors and one increment per actor. `NoLostUpdate` compares the stored value with completed writes. Both real traces must match their model behavior; the safety configuration rejects the faulty variant and completes for the atomic variant. No fairness/liveness property is claimed. The fixture is independently written and has no external project dependency.
