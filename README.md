# Probatio

**English** | [Português](README.pt-BR.md)

> *probatio* (Latin): proof, verification. In Roman law, the evidence presented at trial.

Probatio is a skill for coding agents (Claude Code, Codex, and similar) that formally verifies software behavior with TLA+. It starts from the real source code, builds a TLA+ model, runs the TLC model checker to explore every reachable state within the model's bounds, and, when it finds a counterexample, tries to reproduce it in the code.

It targets properties such as: a payment is never processed twice, a tenant never reads another tenant's data, an expired lease never deletes another owner's claim, a concurrent counter never loses an update.

## How it works

0. **Survey** (only when the request names no behavior): find the implicit state machines in the code, list their states, transitions, and impossible combinations, and rank them by risk to choose what to model.
1. **Scope**: define the questions, the expected contracts, and what is out of scope.
2. **Investigation**: follow the code through entry points, state changes, and error recovery, and build a scenario matrix.
3. **Incremental modeling**: start with the smallest model faithful to the code and add one mechanism or fault at a time.
4. **Instrumentation**: capture traces from the real implementation.
5. **Trace validation**: check that the model admits the observed executions.
6. **Model checking**: run TLC and classify each counterexample as a property error, a model error, or a candidate defect.
7. **Reproduction**: try to reproduce each candidate defect in the code.
8. **Report**: record results, model limits, and what remains uncovered.

The full workflow is in [SKILL.md](SKILL.md), with per-stage details in [references/](references/).

A TLC result holds for the finite model that was checked; it is not an unbounded proof that the implementation is correct. The report states these limits explicitly.

## Requirements

- A JDK 17 or newer (a JRE is not enough: the tools run from source)
- `tla2tools.jar`, from the [official TLA+ releases](https://github.com/tlaplus/tlaplus/releases)

Both can be installed without admin rights into `~/.probatio`, at pinned versions with verified checksums:

```bash
sh scripts/install.sh                                                  # macOS and Linux
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1 # Windows
```

The installer reuses a JDK already on your `PATH` and only downloads Temurin 21 when none is found. Either way, `~/.probatio/java` (Windows: `%USERPROFILE%\.probatio\java.cmd`) runs the selected JDK; the `java` in the commands below means that one, or any other JDK 17+.

## Installation

Copy or symlink this folder into your agent's skills directory. For Claude Code:

```bash
ln -s "$(pwd)" ~/.claude/skills/probatio
```

## Usage

Ask the agent in plain language:

- "Use $probatio to find the implicit state machines in this app and rank them by risk. Don't change any code yet."
- "Use $probatio to model payment retries and check duplicate-processing scenarios with TLC."
- "Use $probatio to model tenant access transitions and check isolation."
- "Resume $probatio from `.analysis/payment-retries/progress.md`."

Each analysis writes its artifacts to `.analysis/<run-id>/` in the target project, starting from the templates in `assets/workspace/`.

## Tools

All tools live in one source file and run directly, with no build step:

| Command | What it does |
| --- | --- |
| `java scripts/Probatio.java doctor` | Checks the JDK, the TLC jar, TLC itself and Git, and names the fix for each failure |
| `java scripts/Probatio.java init --source REPO --out DIR --scope TEXT` | Creates a fresh analysis workspace stamped with the source revision and working-tree digests |
| `java scripts/Probatio.java tlc Model.tla Model.cfg --out DIR` | Runs TLC on a snapshot of the model, with a timeout, a log, and a hashed receipt |
| `java scripts/Probatio.java trace Model.tla run.ndjson --out DIR` | Compiles an implementation trace into a TLC reachability check |
| `java scripts/Probatio.java regress suite.json --out DIR` | Runs a regression manifest of model and trace cases |

`--jar` defaults to `~/.probatio/tla2tools.jar`, and TLC runs on the same `java` that launched the tool. Exit codes are the same for every command: `0` accepted, `1` checked and not accepted (a counterexample or an unexpected regression result), `2` not checked (a tool error, a timeout, or a setup problem).

## Examples

- [examples/counter](examples/counter/EXAMPLE.md): a concurrent counter that loses an update.
- [examples/lease](examples/lease/EXAMPLE.md): an expired lease whose delayed release deletes the new owner's claim.

```bash
java examples/lease/LeaseExample.java --out /fresh/path
```

## Tests

```bash
javac -d build/test scripts/Probatio.java examples/lease/LeaseExample.java tests/ProbatioTest.java
TLA_JAR=~/.probatio/tla2tools.jar java -cp build/test ProbatioTest
```

Without `TLA_JAR`, only the input-validation tests run; the TLC tests are skipped.

## Origin

Probatio is independently written and informed by [Specula](https://github.com/specula-org/Specula)'s public skills. It does not depend on Specula or on any MCP server. Deliberate differences are documented in [references/provenance.md](references/provenance.md).
