# Project-independent scope and examples

## Applicability

Start from an observable property and the transitions that may violate it. Any project can contain a suitable subsystem; suitability depends on whether a faithful, useful abstraction can be constructed, not on a language or framework label.

Potential targets include resource ownership, scheduler behavior, authorization transitions, workflows, cache invalidation, transactions, replication, retry protocols, and algorithmic state transitions. These are examples, not required categories or a checklist.

For each target identify actors, state, legal inputs, environment behavior, atomicity boundaries, and expected safety or liveness properties. Explain what the abstraction omits. If the decisive behavior lies outside it, report the limitation rather than forcing a misleading model or silently replacing formal verification with review.

TLA+/TLC remains this skill's formal verification engine. Supporting arbitrary project types does not mean claiming that this engine proves every kind of property. Language-level undefined behavior, exact floating-point behavior, and cryptographic claims may require different formal techniques; label those obligations outside current coverage unless separately verified with an appropriate method.

## Technical reference design

Keep reusable methodology in reference guides: abstraction, source correspondence, safety/liveness, fairness, fault assumptions, model configuration, trace instrumentation, counterexample diagnosis, and implementation reproduction. A project-specific example illustrates a technique; it does not define the technique's applicability.

Draw realistic examples from real projects. Extract general mechanisms such as lease expiry, duplicate delivery, state transitions, and partial failure. Preserve the distinction between verified source behavior, proposed properties, hypothetical counterexamples, and executed results. A suspected issue from preliminary reading must never become a documented confirmed bug.

Examples derived from client code stay local unless sharing is explicitly authorized. Prefer minimal independently written illustrative implementations over copying client code into a reusable package. Exclude credentials, personal data, business identifiers, and absolute client paths from portable examples.

Each runnable example should contain:

1. The general question and contract, with any project-specific assumption explained.
2. Minimal implementation or fixture, provenance, and abstraction map.
3. TLA+ modules, configurations, and enabled properties.
4. Trace schema and harness when demonstrating implementation trace validation.
5. Exact executed commands, tool versions, logs, and bounded conclusions.
6. A violating and a corrected variant when demonstrating bug detection, explicitly labeled as illustrative variants rather than claims about the source project.

Teach portability by making assumptions replaceable. A lease example should explain how storage atomicity and expiry semantics enter the model; it must not require Laravel, Redis, payment terminology, or the original repository merely to understand the method.

## Claims and proof obligations

Report the kind of formal evidence actually produced:

- A completed TLC exploration establishes results for the specified finite model/configuration, subject to the checker's assumptions and limitations.
- Simulation explores sampled behaviors and does not establish exhaustive coverage.
- Implementation traces support correspondence for observed executions; they do not establish full refinement.
- An implementation reproduction demonstrates the recorded behavior under its actual preconditions.
- An unbounded mathematical proof or general refinement claim requires explicit proof obligations and a suitable checked proof; TLC alone does not supply it.

The goal is both validation and bug discovery. Finding no counterexample is a legitimate bounded result; finding one requires classifying whether the property, model, or implementation is responsible.
