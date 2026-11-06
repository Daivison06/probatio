# Focused validation loop

Use this loop whenever source, a model, instrumentation, or trace interpretation changes. It keeps focused investigation quick while ensuring that local improvements do not silently break shared assumptions.

1. **Pin the inputs:** record source revision/dirty diff, model and configuration hashes, trace schema/capture version, and the exact candidate or property under investigation.
2. **Run the smallest discriminating check:** execute the focused TLC configuration and relevant implementation scenario. Include a positive case and a negative control where applicable.
3. **Compare complete observations:** check action sequence, state fields, causal order, and lifecycle completion. Do not accept a match based only on action names or final state if intermediate ordering is part of the contract.
4. **Diagnose mismatches before editing:** determine whether the cause is (a) model semantics, (b) instrumentation/capture, (c) target implementation behavior, or (d) trace format/clock/correlation. Use raw logs and source anchors to distinguish them.
5. **Change one cause at a time:** keep raw traces immutable, version derived artifacts, and record why the change is justified. Do not edit expected traces or expected violations simply to make checks pass.
6. **Promote the fix:** rerun focused checks, then every affected model configuration and implementation-trace case. Regenerate traces only when source or capture semantics changed; retain older traces as historical evidence.
7. **Record a receipt:** command, working directory, tool/version, input hashes, exit status, log path, expected result, observed result, and any remaining gap.

Classify each mismatch as `MODEL`, `CAPTURE`, `IMPLEMENTATION`, or `FORMAT/ENVIRONMENT`; unresolved mismatches remain open. A timeout, missing observation, empty trace, or inconclusive run is not a match. A green run only validates the recorded inputs and scenario.
