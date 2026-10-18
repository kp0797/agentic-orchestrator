# Engineering summary

What exists, what is real and what is scripted, how it was built, and what is deliberately left out.

## Status

| Area | State |
| --- | --- |
| Orchestration engine (DAG, entry and exit gates, parallelism, retries, fallback, compensation, rollback, safe-stop, budgets, digest-bound approvals, resume from the log, re-planning, fault injection) | Built and tested (resume within one process; see limitations) |
| Audit log (hash-chained), lineage, evidence packs, `verify-evidence` | Built and tested |
| Policy-as-code (risk floors, approval threshold, protected and approval paths, agent autonomy) | Built and tested |
| Agents (requirements, planner with validator and template fallback, architect, coder, blind tester, docs) with structured edits in per-node git worktrees | Built; tested with scripted models and partially with live models |
| Provider-neutral model layer: `anthropic` or any `openai-compatible` server (OpenAI, Ollama, vLLM, LM Studio), models per agent in configuration | Built and tested; Anthropic used live; OpenAI-compatible tested against a local stub server only |
| Codebase analysis (JavaParser index, impact report) | Built and tested |
| Brownfield gates (`migrations-immutable`, `regression-proof`) | Built and tested |
| Clarifications, superseding a running plan, plan versions | Built and tested |
| Approval console, CLI, Docker setup | Built; tested by use |
| Metrics: per run, `/metrics` (Prometheus), eval harness, alert rules | Built and tested (alert rules not validated with `promtool`) |
| URL shortener v1 | Built and tested (105 tests, 90% line coverage) |
| Scenarios `pipeline-smoke`, `brownfield`, `ambiguous` | Replay offline from **scripted** cassettes, labelled as such |
| Scenario `greenfield` with real models | Live attempts made; **no complete recording yet** |

Measured on 2026-10-18 (replay, 3 runs of each scenario): every run succeeded, and attempt and retry
counts were identical across runs. See [results/eval-replay-2026-10-18.md](results/eval-replay-2026-10-18.md).

## How it was built

In three stages. First, the engine with fake agents. Second: workspaces, gates, policy, the live model
gateway, the agents, the validated planner, the console; then live recording attempts, which found and
fixed several defects. Third: the shortener, the brownfield and ambiguous
scenarios, evidence and eval, metrics, a provider-neutral model layer and these documents. About 10,600
lines of main code and 5,300 lines of tests.

## Limitations and trade-offs

**Evidence**
- The replayable cassettes are scripted. They show the orchestration deterministically, not model
  quality. No complete live recording of the greenfield scenario exists; model reliability is unmeasured.
- The eval numbers above are replay numbers: they measure the orchestrator, not the models.

**Orchestrator**
- Single process. Each run has one coordinator thread; there is no distributed queue or worker fleet.
  The engine's event-sourced design would allow one, because state lives in the log.
- The audit log can be durable (PostgreSQL), but artifacts, plans and the link between a scenario's runs
  are kept in memory, so a run cannot resume across a process restart; evidence packs persist on disk. A durable artifact store
  (object storage, content-addressed) is the next step.
- Two live providers are built in: `anthropic` and `openai-compatible`. Only Anthropic has been used for
  live calls; the OpenAI-compatible gateway is tested against a local stub server, and no live run with
  OpenAI or a local model server has been made. A provider with neither API needs one small gateway class.
- The committed cassettes use the default model ids; replaying them works with any provider setting, but
  recording with other models produces a new cassette.
- Model-level fallback (for example a stronger model after repeated failures) is not implemented;
  executor fallback is (the planner falls back to a template planner).
- A clarification during execution redoes the spec, analysis and plan and rolls back the superseded
  run's work; it does not yet reuse that work. The requirements agent sees the latest clarification.
- The default build replays only the planning stage of the brownfield and ambiguous cassettes; the full
  replay (`CommittedCassettesReplayTest`) runs with `-Dscenarios=true` because it takes minutes.
- Budgets can be raised and a run resumed through the engine's Java API, not yet from the console or CLI.
- Agents' builds and tests run as Maven processes inside the orchestrator container, with its network
  access. Agent-written tests are untrusted code; a separate verifier container without network would
  isolate them.
- The security gate is nine in-process rules, not Semgrep, gitleaks or dependency CVE scanning; new
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
- Click statistics are best-effort: clicks are dropped (and counted) when the queue is full, and lost if a
  node dies with clicks queued.

## Next steps

1. Record the greenfield scenario live end to end and run the eval harness in live mode, with a second
   provider (OpenAI or a local model server) to compare.
2. Durable artifact store; resume across restarts.
3. A verifier container without network for agent-written code; Semgrep, gitleaks and a CVE scan as
   gates.
4. Authenticated approvers (SSO) and per-approver policy.
5. Model-level fallback chain and per-node model escalation.
6. Grafana dashboards over the existing metrics.
