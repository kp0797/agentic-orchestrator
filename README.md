# Agentic SDLC Orchestrator

A governed orchestration engine that takes a requirement through requirements, design, implementation, testing, documentation and release readiness, with LLM agents working inside hard limits. The URL shortener it builds is the test subject; the orchestrator is the product.

> **Agents propose, gates dispose.** Agents generate output. Deterministic verifiers, policy-as-code and humans decide what is accepted.

## Status

| Part | State |
| --- | --- |
| Day 1: DAG engine, gates, retries, fallback, rollback, safe-stop, approvals, audit log, replay gateway | Built, tested |
| Day 2a: git workspaces per node, Maven and policy gates, YAML policy, Spring AI live gateway, agents, validated planner, approval console | Built, tested with scripted models |
| Day 2b: real recordings of greenfield and brownfield, curated shortener, impact analysis | Not started |
| Day 3: ambiguity scenario, observability, evidence pack, docs | Not started |

This table is updated as work lands. Nothing below describes a feature that does not exist yet.

## Quickstart

Prerequisite: Docker.

```bash
docker compose up console        # approval console on http://localhost:8080 (replay mode, no API key)
docker compose run --rm smoke    # pipeline-smoke scenario in the terminal, approvals automatic and labelled
docker compose run --rm demo     # engine demo: fake agents, no LLM
docker compose run --rm test     # full test suite; output also written to build.log
```

The first build downloads Maven dependencies and takes a few minutes.

To run outside Docker: Java 21 and Maven 3.9, then `mvn verify`.

**About the `pipeline-smoke` recording.** It was generated from scripted answers, not recorded from a model. It shows the orchestration machinery (planning, worktrees, gates, approvals, audit) end to end, not model quality. Real recordings of the greenfield and brownfield scenarios come with Day 2b.

## How a scenario runs

1. **Planning run.** The requirements agent turns the requirement into a spec with acceptance criteria and explicit ambiguities. The planner proposes a task DAG. A validator checks it: every criterion implemented and tested, tests independent of the code, a join before release. Invalid plans are repaired with the errors as feedback, then replaced by a template. A human approves the plan.
2. **Execution run.** The approved plan is bound to executors and gates. Each node works in its own git worktree, starting from its dependencies' commits. Agents return structured edits; the tool layer applies them only inside the paths `policies/autonomy.yaml` allows and commits a real diff.
3. **Gates decide.** Maven build and tests, protected paths, diff size, secrets, and a security scan. Migrations, build-file changes, new dependencies and disabled tests escalate to a human.
4. **Integration repair.** If the merged code fails the independently written tests, the implementation is re-run with the failures as feedback (at most twice), then a human decides.
5. **Release.** A human approves the release checklist and evidence. Every approval is bound to the exact output digest.

## What the Day-1 demo shows

An 8-node plan (requirements, design, implement and write-tests in parallel, integrate, docs and security-scan in parallel, release-readiness) runs with fake agents through three scenarios:

1. **Retry, fallback, approvals, re-plan.** An injected compile error is retried with the error fed back. An injected gate failure exhausts the primary executor and falls back to a stronger one. Design and release pause for a human. Then the design is marked stale: design and everything after it re-run, requirements are reused, and old approvals are invalidated.
2. **Safe-stop and resume.** A token budget stops the run. After the budget is raised, the run resumes from the audit log without redoing finished nodes.
3. **Rollback.** An unrecoverable failure compensates every succeeded node, newest first.

Each scenario ends with the audit timeline, a hash-chain verification and reliability metrics computed from the log.

## Engine design in brief

- **One coordinator per run** owns all state changes. Executors run in parallel on virtual threads and only return results.
- **Event-sourced state.** Every decision is an event in a per-run, hash-chained log. State is rebuilt from the log, so a stopped or crashed run resumes exactly where it was.
- **Gates are deterministic.** No LLM decides whether work passes. An ArchUnit test enforces that gates and policy cannot depend on the model package.
- **Approvals are bound to output digests.** If the output changes, the approval no longer applies.
- **Faults are injectable and always logged**, so the demo can show recovery without pretending a failure was real.

See `docs/` (to be written on Day 3) for the full architecture, scenarios, testing approach and LLM usage.

## AI assistance

This project is being built with Claude as a coding assistant. The author reviews every change. `ENGINEERING_SUMMARY.md` will record which parts were AI-assisted and what was changed after review.
