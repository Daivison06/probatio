# Continuing an analysis across source revisions

Use this workflow when source changes after a formal analysis. A previous result applies only to the source, model, harness, and assumptions recorded in its receipt.

## Establish the delta

1. Preserve the prior analysis directory. Create a new revision directory or clearly version new outputs; never replace the historical run.
2. Record old and new source revisions, dirty changes, and the exact diff. If either baseline is dirty, compare the saved diff/hash or an archived worktree snapshot; commit IDs alone are insufficient.
3. Map changed files/symbols to prior source anchors, state variables, actions, properties, instrumentation points, traces, findings, and configurations.
4. Label each evidence item `REUSE-CANDIDATE`, `RECHECK`, or `INVALIDATED`, with a reason. Reuse is provisional until affected dependencies are checked.

## Update in dependency order

1. Re-run reconnaissance for changed paths and callers. Determine whether semantics changed, not merely whether lines changed.
2. Update the scenario/modeling brief and traceability map. Add scenarios for new mechanisms; retain prior scenarios whose contracts still apply.
3. Update shared actions first, then focused model-checking configurations and properties. Preserve old model versions and counterexamples.
4. Rebase or regenerate harness instrumentation where capture points or synchronization changed. Emit fresh traces from the new implementation; label old traces with their original source revision.
5. Validate traces against the updated shared model. Resolve fidelity mismatches before hunting for new bugs.
6. Run changed-scenario configurations, then interactions with unchanged scenarios that share state or assumptions. Run the full prior suite when shared semantics, properties, or observation mapping changed.
7. Revisit prior candidates: fixes can invalidate, preserve, or expose a finding. Reproduce any newly supported candidate against the new source baseline.

Finish with a delta report: changed semantics, reused and invalidated evidence, checks rerun, new or changed candidates, and residual gaps. Do not call an old pass current until its dependency mapping and required rechecks are complete. If the old baseline is unavailable, state that the comparison is incomplete and analyze the current revision as a fresh baseline.
