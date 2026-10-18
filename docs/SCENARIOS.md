# Scenarios

Four scenarios, each a requirement plus the code it starts from. Three replay offline from a cassette;
the greenfield scenario is for live recording.

| Scenario | Starts from | Shows | Replay | Command |
| --- | --- | --- | --- | --- |
| `pipeline-smoke` | tiny Base62 library skeleton | the whole pipeline end to end, fast | yes, scripted cassette | `docker compose run --rm smoke` |
| `brownfield` | the shortener with a planted production defect | codebase analysis, safe change to existing code, migration guardrail, regression proof, fault injection | yes, scripted cassette | `docker compose run --rm brownfield` |
| `ambiguous` | the real shortener | escalated questions, clarification, mid-run scope change, superseding a plan, re-planning (plan v2) | yes, scripted cassette | `docker compose run --rm ambiguous` |
| `greenfield` | empty Spring Boot skeleton | building the shortener from nothing with real models | no: live only | `AGENTIC_MODE=record docker compose --profile live up console-live` |

**About the cassettes.** The three replayable cassettes were generated from scripted answers written by
hand, not recorded from a model, and each scenario's description says so. They demonstrate the
orchestrator, the gates and the guardrails deterministically; they do not show model quality. Everything
else in a replay is real: git worktrees and merges, Maven builds and test runs, policy, approvals, the
audit log and the evidence. Live recording found seven real bugs (see
[ENGINEERING_SUMMARY.md](ENGINEERING_SUMMARY.md)); a complete live recording of the greenfield scenario
has not been made yet.

## Where each capability is demonstrated

| Capability | Where to see it |
| --- | --- |
| Task DAG with dependencies | Every execution run; `PLAN_ACCEPTED` lists the topological order |
| Entry and exit gates | Every node: `GATE_EVALUATED` events; entry policy decisions as `POLICY_EVALUATED` |
| Parallel paths | brownfield: `migrate-unique-codes`, `fix-alias-race` and `regression-tests` start in the same millisecond |
| Context and lineage | Every artifact's lineage in the evidence pack's `manifest.json`; `context-manifest.json` per edit attempt |
| Human approvals | Plan, HIGH-risk migration and release approvals, digest-bound (`APPROVAL_REQUESTED` / `GRANTED`) |
| Retries with feedback | brownfield: the migration's first attempt fails a gate and is retried with the reason |
| Fallback | `invalidPlansAreRepairedThenFallBackToTheTemplate` (planner → template planner); fake-agent demo |
| Rollback and compensation | ambiguous: the superseded plan's design is compensated; fake-agent demo scenario 3 |
| Safe-stop | console kill switch; budget breach (fake-agent demo scenario 2); `--inject` exception faults |
| Policy guardrails | `migrations-immutable`, `protected-paths`, autonomy write paths, approval paths, risk floors |
| Audit and observability | Hash-chained log, `verify-evidence`, `/metrics`, evidence packs |
| Reliability metrics | Per-run metrics line; `eval` summary (success rate, retries, MTTR, p50/p95) |
| Dynamic re-planning | ambiguous: clarification during execution → plan v2; repair rounds after integration failures |
| Greenfield | pipeline-smoke (replay) and greenfield (live) |
| Brownfield | brownfield |
| Ambiguous requests | ambiguous |

## pipeline-smoke

A tiny library ("encode numeric ids as short Base62 codes and back"), so the whole pipeline runs in about
15 seconds: requirements, codebase analysis (empty project), plan, design, implementation and blind tests
in parallel, integration with real Maven builds, docs, security scan, release. Used by the console's
default demo and by the eval harness.

## brownfield

Starts from `scenarios/templates/shortener-v1`: the shortener with one planted defect. Alias creation
checks whether the alias exists and then inserts, and `links.code` has no unique constraint, so two
concurrent requests store the same code and every later lookup of it fails with HTTP 500. The tests that
would have caught it are absent. `BrownfieldTemplateTest` keeps the copy identical to `shortener/`
otherwise. The requirement is an incident report.

What happens: the codebase analysis names the alias path and says the next migration is V3; the plan
runs an ADR, then the migration, the service fix and the regression tests in parallel. The migration
agent's first answer edits the applied V1 migration (a deliberate scripted mistake) and the
`migrations-immutable` gate rejects it; the retry adds V3, which repairs stored duplicates and adds the
constraint, and a human approves it. At the JOIN the whole existing suite passes on the merged code and
`regression-proof` shows the new tests fail on the original code (16 of 16 concurrent requests got 201).
Details: [scenarios/brownfield/README.md](../scenarios/brownfield/README.md).

Variant with an injected fault (also in the cassette):

```bash
docker compose run --rm brownfield run brownfield --auto-approve --inject fix-alias-race:gate:build@1
```

## ambiguous

Starts from the real shortener with an underspecified request: show how many people visit, not just
clicks. Identifying people is a privacy decision. The spec's question about it is LOW confidence, so it
escalates; the (scripted) product owner answers: no IPs or user agents stored, a daily salt that is
never stored. Plan v1 starts with a design review, where the product owner adds per-referrer unique
visitors. The running plan is superseded (pending nodes cancelled, design compensated, approvals
invalidated) and the spec, analysis and plan are redone: plan v2 has an extra test task. Details:
[scenarios/ambiguous/README.md](../scenarios/ambiguous/README.md).

In the approval console, no script answers: a person types the clarification in **Clarify or change
scope**. In replay only the recorded answers can be replayed; use live mode to answer in your own words.

## greenfield

Builds the shortener from an empty skeleton (`scenarios/templates/spring-boot-service`) with real
models. The requirement states the project's constraints (base package, offline tests, k6 for load
targets). It needs an API key:

```bash
export ANTHROPIC_API_KEY=...                       # never written to a file or a log
docker compose --profile live run --rm ping        # one tiny call (< $0.01) to check the key
AGENTIC_MODE=record docker compose --profile live up console-live
```

Then start `greenfield` in the console and review each approval. Budget: 60 minutes, 3M tokens, $30.

## Injecting faults

Any scenario: `run <scenario> --auto-approve --inject <fault>` (repeatable), or `faults:` in its
`scenario.yaml`.

| Fault | Effect |
| --- | --- |
| `node:exception[@1,2]` | the executor throws on those attempts |
| `node:gate:<gate-id>[@1]` | that exit gate fails on those attempts even if the output is fine |

Injected faults are always recorded as `FAULT_INJECTED` and counted separately in the metrics. In replay
mode a retry after a fault sends a different prompt (it carries the failure), so only variants recorded
in the cassette replay; the brownfield cassette includes `fix-alias-race:gate:build@1`.
