# LLM usage and runtime

> **In one line:** you do not need an API key. `docker compose run --rm brownfield` (or
> `smoke`, `ambiguous`, `eval`) replays recorded answers: no model is called, nothing leaves your machine,
> and the result is the same every time.

## Quick answers

| Question | Answer |
| --- | --- |
| Do I need an API key? | No, for every replay command. Only `record`/`live` modes call a model. |
| What runs for real in replay mode? | Everything except the model: the engine, codebase analysis, git worktrees and merges, Maven builds and tests, gates, policy, approvals, audit log, metrics and evidence. |
| What do I need installed? | Docker. (Or Java 21 and Maven 3.9.) |
| Which provider in live mode? | Anthropic (Claude) through Spring AI's Anthropic client, behind the project's own `ModelGateway` interface. |
| Can models be changed? | Yes, in configuration only. A change alters request keys, so cassettes must be re-recorded. |

## Where a model is used, and where it is not

Never a model: the scheduler and node state machine, entry and exit gates, the policy engine, the
codebase analyzer, integration (merge), the security scan, the release checklist, approvals, the audit
log, metrics and evidence.

A model, only through `ModelGateway`:

| Agent | Model | Max output tokens | Produces |
| --- | --- | --- | --- |
| requirements | `claude-sonnet-5-5` | 16,000 | the spec: criteria, ambiguities with confidence |
| planner | `claude-opus-5-5` | 16,000 | the execution DAG (validated, then approved) |
| architect | `claude-opus-5-5` | 32,000 | design, ADRs, contracts, shared files |
| coder | `claude-opus-5-5` | 32,000 | implementation and migrations, as structured edits |
| tester | `claude-sonnet-5-5` | 32,000 | blind tests from criteria and contracts |
| docs | `claude-haiku-5-5` | 8,192 | README and docs |

Output caps include the model's thinking, which is why the code-writing agents get generous caps. No
`temperature` is sent: current models reject it.

**Fallback.** When an agent's attempts are exhausted, a node can fall back to another executor: the
planner falls back to a deterministic template planner, labelled as such in the plan. A model-level
fallback chain (for example Sonnet → Opus) is present in the configuration model but **not wired**; it
is listed under limitations.

## Run modes

| Mode | Set with | Key | Calls a model | Deterministic | For |
| --- | --- | --- | --- | --- | --- |
| replay (default) | `AGENTIC_MODE=replay` | no | no | yes | reviewers, CI, demos |
| record | `AGENTIC_MODE=record` | yes | yes | no | creating or refreshing a cassette |
| live | `AGENTIC_MODE=live` | yes | yes | no | measuring the models (eval harness) |

**How replay works.** A request's key is `sha256(model, system prompt, user prompt, parameters)`. Record
mode appends each exchange to the scenario's `cassette.jsonl` as it happens. Replay looks the key up. A
miss fails that attempt with `cassette miss: re-record this scenario`; replay never falls back to a live
call. Replayed answers report zero tokens and cost. Because prompts contain the code an agent sees, any
change to a prompt, a model setting or the scenario's starting code requires re-recording; the default
build has tests that catch a stale cassette.

**What replay proves, and what it does not.** It proves the orchestrator behaves the same way every time:
planning validation, gates, retries, approvals, re-planning, audit. It does not prove a model writes
correct code every time. The committed cassettes were generated from scripted answers and are labelled
so. Live mode with the eval harness is how model reliability would be measured.

## Using a key

```bash
export ANTHROPIC_API_KEY=sk-ant-...                 # only in your shell; never in a file
export ANTHROPIC_WORKSPACE_ID=...                   # only if your key is scoped to a workspace
docker compose --profile live run --rm ping         # one tiny call with the cheapest model (< $0.01)
AGENTIC_MODE=record docker compose --profile live up console-live
```

- The key is read from the environment only, passed only to the `console-live` and `ping` services, and
  never written to a file, a log or an artifact. The secrets gates and the security scan block a key that
  appears in a change.
- Model settings can be overridden per agent, for example
  `AGENTIC_MODELS_CODER_MODEL=claude-sonnet-5-5`.

## Where everything runs

| Component | Runs in | Network |
| --- | --- | --- |
| Orchestrator (engine, agents, gateway, console) | the `console`, `smoke`, `brownfield`, `ambiguous`, `eval` containers | replay: none needed; live: the Anthropic API |
| Agents' builds and tests | Maven processes inside the orchestrator container, one per worktree | Maven Central for dependencies (or offline from `./.m2`) |
| Audit log | memory, or PostgreSQL with `SPRING_PROFILES_ACTIVE=postgres` and the `postgres` service | internal |
| Shortener | `shortener` service, with `shortener-db` (PostgreSQL) and `redis` | internal |
| Prometheus | `prometheus` service (profile `observability`) | internal |

Run workspaces and evidence packs are written to `./runs/` on your machine through a volume.

## What is sent to a model

Only the context bundle for the current task: the relevant part of the spec, the codebase analysis, the
files the agent's role may see (impacted files first) up to a size cap, and the previous attempt's
failure on a retry. A `context-manifest.json` artifact records what was included and what was cut. No
environment variables, keys or files outside the run's workspace are included.

## Cost

Replay costs nothing. Live list prices per million tokens (input / output), October 2026: Haiku 5.5 $0.10
/ $0.50, Sonnet 5.5 $2 / $10, Opus 5.5 $4 / $20, Fable 5.1 $10 / $50. Every run's tokens and cost are in
its metrics, evidence pack and `/metrics`; each scenario has a budget (the greenfield scenario: 60 minutes,
3M tokens, $30) that safe-stops the run when exceeded. The partial live recordings made during
development cost about $2–4 in total.

## Switching provider

`ModelGateway` has three implementations: the Spring AI Anthropic gateway, the replay gateway and the
recording gateway (plus scripted gateways in tests). Another provider means another Spring AI client
behind the same interface and its model ids in configuration; the engine, gates and agents do not change.
