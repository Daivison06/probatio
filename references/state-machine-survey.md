# Implicit state machine survey

Use this stage when the request does not name a behavior to verify ("verify my app", "find bugs in this service") or when the user asks for a survey. It finds the state machines the code implements without declaring them, ranks them by risk, and picks what to model first. It is read-only: do not change implementation code.

## Find candidates

Implicit state hides in:

- Status or phase fields, enums, and string constants compared in conditionals.
- Clusters of booleans that together encode one state (`isLoading`, `isError`, `isDone`).
- Nullable timestamps or references used as states (`paid_at`, `deleted_at`, `locked_by`).
- Database columns and migrations, cache and session keys, files or markers on disk.
- Job, queue, and workflow status; locks, leases, tokens, and retry counters.
- UI component state, reducers, and stores; feature flags that interact.

For each candidate, search every writer: assignments, update queries, event and message handlers, scheduled jobs, webhooks, migrations, and admin tools. A machine is the storage plus everything that writes it, not the one function that looks central.

Record, with `path:line` anchors:

| Item | What to record |
|---|---|
| Storage | Where the state lives and which fields together encode it |
| States | Named states, including ones that exist only as field combinations |
| Transitions | Trigger, guard, and effect for each move, and which actor or process performs it |
| Writers | Every actor that writes the storage, and whether a lock, transaction, or version check covers them together |
| Impossible combinations | Field combinations the code assumes never occur (for example `paid_at` set while `status = pending`), and what enforces that: a constraint, a transaction, a single writer, or only convention |
| Terminal and recovery | Terminal states, and what a crash, timeout, or retry in the middle of a transition leaves behind |

Mark each item as **evidence** (read in source) or **inference**. Impossible combinations are the invariant candidates: an unenforced one is a property TLC can falsify.

## Rank by risk

| Criterion | Raises risk when |
|---|---|
| Concurrency | More than one actor writes the state without a shared lock, transaction, or version check |
| Check-use gap | A guard is read in one step and relied on in a later one |
| External effects | A transition moves money, sends a message, grants or revokes access, deletes data, or calls another system |
| Duplicates and reordering | Messages, webhooks, or jobs can repeat, arrive late, or overlap |
| Encoding | The state is spread across flags or nullable fields that can disagree |
| Unenforced impossibility | A combination assumed impossible is enforced by nothing |
| Recovery | A crash mid-transition leaves a non-terminal state that nothing resumes or cleans up |

Rate **high** when a transition has an external effect or controls access and also has concurrency, duplicates, or a check-use gap. Rate **medium** for any other machine that meets a criterion, and **low** for a single writer whose impossible combinations are enforced. Name the criteria behind each rating. Order by rating, then by the user's priorities and known failure history.

## Bridge to modeling

For the top one to three machines, propose without writing TLA+ yet:

- The scope boundary: which writers and effects are inside, and what is abstracted.
- Model variables from the storage, and actions from the transitions.
- Invariants from the impossible combinations, and any liveness the recovery behavior implies.
- The first fault or interleaving to add, chosen from the criteria that drove the rating.
- The observables an implementation trace must capture.

## Output and handoff

Add a **State machine survey** section to `analysis.md`:

| ID | Machine | Storage (anchors) | States | Transitions (trigger → effect) | Writers | Impossible combinations (enforced by) | Risk | Criteria |
|---|---|---|---|---|---|---|---|---|

List areas that were not surveyed as gaps. Finding no machine in an area is not evidence that it is safe.

If the user asked only for the survey, stop there and report the ranking; that is an analysis-only boundary, not completed formal verification. Otherwise take the user's pick, or the top-ranked machine, write `scope.md` from its bridge, and continue the pipeline at stage 2 for that machine.
