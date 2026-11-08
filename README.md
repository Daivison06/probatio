# Probatio

**English** | [Português](README.pt-BR.md)

> *probatio* (Latin): proof, verification. In Roman law, the evidence presented at trial.

Probatio is a skill for coding agents (Claude Code, Codex, and similar) that formally verifies software behavior with TLA+. It starts from the real source code, builds a TLA+ model, runs the TLC model checker to explore every reachable state within the model's bounds, and, when it finds a counterexample, tries to reproduce it in the code.

It targets properties such as: a payment is never processed twice, a tenant never reads another tenant's data, an expired lease never deletes another owner's claim, a concurrent counter never loses an update.

## How it works

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

- Python 3
- Java (the required version depends on the TLA+ release you use)
- `tla2tools.jar`, from the [official TLA+ releases](https://github.com/tlaplus/tlaplus/releases)

## Installation

Copy or symlink this folder into your agent's skills directory. For Claude Code:

```bash
ln -s "$(pwd)" ~/.claude/skills/probatio
```

## Usage

Ask the agent in plain language:

- "Use $probatio to model payment retries and check duplicate-processing scenarios with TLC."
- "Use $probatio to model tenant access transitions and check isolation."
- "Resume $probatio from `.analysis/payment-retries/progress.md`."

Each analysis writes its artifacts to `.analysis/<run-id>/` in the target project. To create that directory manually:

```bash
python3 scripts/prepare.py --source /path/to/repo --out /path/to/analysis --scope "behavior to verify"
```

## Scripts

| Script | What it does |
| --- | --- |
| `scripts/prepare.py` | Creates a fresh analysis workspace stamped with the source revision |
| `scripts/tlc.py` | Runs TLC on a model and configuration, with a timeout and a log |
| `scripts/tracecheck.py` | Validates an implementation trace against the model |
| `scripts/regress.py` | Runs regression over a manifest of configurations |

## Examples

- [examples/counter](examples/counter/EXAMPLE.md): a concurrent counter that loses an update.
- [examples/lease](examples/lease/EXAMPLE.md): an expired lease whose delayed release deletes the new owner's claim.

```bash
python3 examples/lease/run.py --out /fresh/path --jar /path/to/tla2tools.jar --java java
```

## Tests

```bash
TLA_JAR=/path/to/tla2tools.jar python3 -m unittest discover -s tests
```

Set `JAVA=/path/to/java` if Java is not on your `PATH`. Without `TLA_JAR`, only the tests that need no Java run; the TLC tests are skipped.

## Origin

Probatio is independently written and informed by [Specula](https://github.com/specula-org/Specula)'s public skills. It does not depend on Specula or on any MCP server. Deliberate differences are documented in [references/provenance.md](references/provenance.md).
