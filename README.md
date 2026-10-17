# Agentic SDLC Orchestrator

A governed orchestration engine that takes a requirement through requirements, design, implementation, testing, documentation and release readiness, with LLM agents working inside hard limits. The URL shortener it builds is the test subject; the orchestrator is the product.

> **Agents propose, gates dispose.** Agents generate output. Deterministic verifiers, policy-as-code and humans decide what is accepted.

## Status

| Part | State |
| --- | --- |
| Day 1: DAG engine, gates, retries, fallback, rollback, safe-stop, approvals, audit log, replay gateway | Built, with fake agents |
| Day 2: real agents, policy rules, approval console, greenfield and brownfield scenarios | Not started |
| Day 3: ambiguity handling, re-planning scenario, observability, evidence pack | Not started |

This table is updated as work lands. Nothing below describes a feature that does not exist yet.

## Quickstart

Prerequisite: Docker.

```bash
docker compose run --rm demo     # Day-1 engine demo: fake agents, no LLM, no API key
docker compose run --rm test     # full test suite; output also written to build.log
```

The first build downloads Maven dependencies and takes a few minutes.

To run outside Docker: Java 21 and Maven 3.9, then `mvn verify`.

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
