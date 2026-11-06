# Lease ownership example

Run `python3 run.py --out /fresh/output --jar /path/tla2tools.jar --java /path/java`.

The real Python fixture acquires a short lease for A, observes expiry using a monotonic clock, acquires a lease for B, then executes A's delayed release. The faulty implementation clears B's ownership; the guarded implementation preserves it. The model has three actors, one acquisition/release per actor, and explicit lease expiry. It abstracts wall-clock duration and storage into transitions. `NoStaleDelete` checks ownership preservation, not exactly-once external effects or liveness.

Both implementation traces should match: a faithful model must describe faulty behavior too. Model checking finds the unsafe variant's violation and exhausts the guarded variant without this violation. The example mutates a state observation and an action name as synthetic negative controls; empty/malformed inputs are also rejected. Raw traces and control provenance are retained separately.

This independently written fixture illustrates a mechanism encountered while inspecting an idempotency adapter. It neither copies that adapter's code nor establishes a production defect. The fixture has no crash persistence, payload hashes, network failure, renewal, or real Redis.

The trace adapter validates one atomic model action per event and complete declared observations. It searches compatible partial orders, but this particular real scenario uses controlled sequential operations across overlapping lease lifetimes. Concurrent interval ordering is tested separately with explicitly synthetic validator controls.
