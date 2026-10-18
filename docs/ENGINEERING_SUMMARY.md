# Engineering summary

What exists, what is real and what is scripted, how it was built, what broke along the way, and what is
deliberately left out.

## Status

| Area | State |
| --- | --- |
| Orchestration engine (DAG, entry and exit gates, parallelism, retries, fallback, compensation, rollback, safe-stop, budgets, digest-bound approvals, crash resume, re-planning, fault injection) | Built and tested |
| Audit log (hash-chained), lineage, evidence packs, `verify-evidence` | Built and tested |
| Policy-as-code (risk floors, approval threshold, protected and approval paths, agent autonomy) | Built and tested |
| Agents (requirements, planner with validator and template fallback, architect, coder, blind tester, docs) with structured edits in per-node git worktrees | Built; tested with scripted models and partially with live models |
| Codebase analysis (JavaParser index, impact report) | Built and tested |
| Brownfield gates (`migrations-immutable`, `regression-proof`) | Built and tested |
| Clarifications, superseding a running plan, plan versions | Built and tested |
| Approval console, CLI, Docker setup | Built; tested by use |
| Metrics: per run, `/metrics` (Prometheus), eval harness, alert rules | Built and tested (alert rules not validated with `promtool`) |
| URL shortener v1 | Built and tested (105 tests, 90% line coverage) |
| Scenarios `pipeline-smoke`, `brownfield`, `ambiguous` | Replay offline from **scripted** cassettes, labelled as such |
| Scenario `greenfield` with real models | Live attempts made; **no complete recording yet** |

Measured on 2026-10-18 (replay, 2 runs of each scenario): every run succeeded, and attempt and retry
counts were identical across runs. See [results/eval-replay-2026-10-18.md](results/eval-replay-2026-10-18.md).

## How it was built

Over three days. Day 1: the engine with fake agents. Day 2: workspaces,
gates, policy, the live model gateway, the agents, the validated planner, the console; then live recording
attempts, which found the bugs listed below. Day 3: the shortener, the brownfield and ambiguous
scenarios, evidence and eval, metrics and these documents. About 10,300 lines of main code and 4,700
lines of tests.

### AI assistance (disclosure)

This project was built with Claude (Anthropic) as a coding assistant, in Claude's Cowork mode. Most of
the code, tests, scripted cassettes and documents were drafted by Claude under the author's direction.
The author chose the scope and the technical direction (Java, Spring AI, a web approval console, the plan
and its order), reviewed the changes, ran every test suite and scenario on his own machine, made the
approval and rejection decisions in the live runs, and committed and pushed every change. The scripted
model answers in the cassettes were written for this project and are labelled as scripted wherever they
appear.

## Bugs found by running against real models

A live recording of the greenfield scenario was attempted several times. It never completed, but each
attempt found a real defect, each now fixed and tested:

| # | Symptom | Cause | Fix | Test |
| --- | --- | --- | --- | --- |
| 1 | HTTP 400 "API key not scoped to workspace" | Key belongs to a workspace | Optional `ANTHROPIC_WORKSPACE_ID` header | verified by a live call (`ping-model`) |
| 2 | HTTP 400 "`temperature` is deprecated" | Spring AI's builder sends a default temperature | Own default options; temperature only when configured | `SpringAiModelGatewayTest` |
| 3 | Empty answer despite 5,221 output tokens | The response started with thinking blocks; the first block was read | Read text blocks only | `SpringAiModelGatewayTest` |
| 4 | A rejected plan's reviewer feedback vanished on the next attempt | A later gate failure overwrote the feedback | Reviewer feedback persists until the node is reset | `EngineTest.reviewerFeedbackSurvivesLaterGateFailures` |
| 5 | Parallel implementers planned to edit the same shared files | Nothing owned `application.yml`, `pom.xml`, the error handler | The architect owns shared files; the coder cannot write them | `autonomy.yaml`, prompts, `WorkspaceTest` |
| 6 | The design node failed three times on output limits | Thinking counts toward the cap; the error surfaced as invalid JSON | Truncation detected and reported with the fix; caps raised; compact design guidance | `TruncationTest` |
| 7 | Console replay missed the cassette | `application.yml` model settings differed from the code's defaults | Aligned, and a drift test | `ModelSettingsTest` |

The human-in-the-loop also did its job in those runs: the requirements were rejected once with five
changes (management token, DNS checks at creation, retention, Redis behind a port, asynchronous clicks),
and plans were rejected for a wrong base package, Testcontainers-only tests, a missing schema task, k6
scripts outside the test tree, and a package the architect was not allowed to write. Each rejection went
back to the planner as feedback; the recurring ones became project constraints in the requirement and
rules in the prompts.

## Bugs found by running on the author's machine

| Symptom | Cause | Fix |
| --- | --- | --- |
| Brownfield failed in Docker: git "dubious ownership" | On Docker Desktop, mounted run directories can be owned by another user | `safe.directory` on every orchestrator git call and in the image; reproduced in `WorkspaceTest` with `chown` |
| A replay reported $0.41 | Replayed answers reported the recording's token counts | Replays report zero; the cassette keeps the recorded numbers |
| A passing `regression-proof` printed Maven `[ERROR]` lines | The gate echoed Maven's failure output | It names the failing tests and assertions instead |
| `/actuator/prometheus` 404 in tests only | Boot tests switch metrics exporters off; the test `application.yml` replaces the main one | `@AutoConfigureObservability`; the test config repeats production's exposed endpoints |
| Builds failed with `Input/output error`, files named `Name 2.class` | A sync client (iCloud Desktop) copied files while Maven wrote them | Test services keep build output on Docker volumes |
| A test crashed on `shortener/.DS_Store` | Finder metadata; it would also have changed the agents' prompts | Workspaces and tests ignore OS metadata files |
| The `postgres` profile did not store the audit log, although its comment said so | The run service always created the in-memory store | Wired: a configured database is used; `PostgresProfileTest` |

## Limitations and trade-offs

**Evidence**
- The replayable cassettes are scripted. They show the orchestration deterministically, not model
  quality. No complete live recording of the greenfield scenario exists; model reliability is unmeasured.
- The eval numbers above are replay numbers: they measure the orchestrator, not the models.

**Orchestrator**
- Single process. Each run has one coordinator thread; there is no distributed queue or worker fleet.
  The engine's event-sourced design would allow one, because state lives in the log.
- The audit log can be durable (PostgreSQL), but artifacts are kept in memory, so the console cannot
  resume a run across a process restart; evidence packs persist on disk. A durable artifact store
  (object storage, content-addressed) is the next step.
- Model-level fallback (for example Sonnet → Opus after repeated failures) is configured but not wired;
  executor fallback is.
- Agents' builds and tests run as Maven processes inside the orchestrator container, with its network
  access. Agent-written tests are untrusted code; a separate verifier container without network would
  isolate them.
- The security gate is eight in-process rules, not Semgrep, gitleaks or dependency CVE scanning; new
  dependencies escalate to a human with the license allowlist but licenses are not checked automatically.
- The console has no authentication: the approver types a name, which is recorded but not verified.
- The codebase analysis is syntactic (JavaParser without symbol resolution) and its relevance scoring is
  a heuristic; it reports why each item is in scope so a reviewer can judge it.
- The plan validator and gates assume a Java/Maven project.
- `/metrics` recomputes from the audit log per scrape: simple and drift-free, O(events) per scrape.
- No Grafana dashboards; Prometheus and the alert rules are provided.

**Shortener**
- URLs are checked for safety at creation only; a host re-pointed later is not re-checked.
- The rate limit is per node; the unique-visitor salt (ambiguous scenario) is per node.
- Click statistics are best-effort: clicks queued when a node dies are lost.

## Next steps

1. Record the greenfield scenario live end to end and run the eval harness in live mode.
2. Durable artifact store; resume across restarts.
3. A verifier container without network for agent-written code; Semgrep, gitleaks and a CVE scan as
   gates.
4. Authenticated approvers (SSO) and per-approver policy.
5. Model-level fallback chain and per-node model escalation.
6. Grafana dashboards over the existing metrics.
