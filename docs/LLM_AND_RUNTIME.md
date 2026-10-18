# LLM usage and runtime

> **In one line:** you do not need an API key. `docker compose run --rm brownfield` (or
> `smoke`, `ambiguous`, `eval`) replays the model answers saved in the scenario's cassette: no model is
> called, no prompt or code is sent anywhere, and the result is the same every time. (Maven may still
> download dependencies the first time.)

## Quick answers

| Question | Answer |
| --- | --- |
| Do I need an API key? | No, for every replay command. Only `record`/`live` modes call a model. |
| What runs for real in replay mode? | Everything except the model: the engine, codebase analysis, git worktrees and merges, Maven builds and tests, gates, policy, approvals, audit log, metrics and evidence. |
| What do I need installed? | Docker. (Or Java 21, Maven 3.9 and git.) |
| Is it tied to a provider or a model? | No. Agents call the project's own `ModelGateway` interface and name a model id from configuration. Two live providers are built in: `anthropic`, and `openai-compatible` for OpenAI and any server that speaks the same API (Ollama, vLLM, LM Studio, API gateways). |
| Can models be changed? | Yes, in configuration only, per agent or one model for all. A change alters request keys, so a scenario run with other models needs its own recording to be replayed. |

## Where a model is used, and where it is not

**Never a model.** Everything that decides, checks or records is deterministic code:

- running the plan: the scheduler, node states, retries, fallbacks, compensation, safe-stop and budgets;
- checking work: plan validation, entry and exit gates, the policy engine and the security scan;
- the codebase analysis (impact report), merging at the JOIN and the release checklist;
- the repair decisions after a failed JOIN (which nodes re-run, with which failure as feedback);
- approvals and clarifications, which come from a person (or, in unattended replays, from a script
  that is labelled as such in the audit log);
- the audit log, metrics and evidence packs.

**A model, only through `ModelGateway`,** for the six agents that write text or code:

| Agent | Produces | Default model id | Output cap (tokens) |
| --- | --- | --- | --- |
| requirements | the spec: goals, acceptance criteria, ambiguities with a proposed default and a confidence | `claude-sonnet-5-5` | 16,000 |
| planner | the plan: a task DAG, validated before anyone sees it | `claude-opus-5-5` | 16,000 |
| architect | design, decision records, contracts and the shared files | `claude-opus-5-5` | 32,000 |
| coder | implementation and migrations, as structured edits | `claude-opus-5-5` | 32,000 |
| tester | tests written from the criteria and contracts, without the implementation | `claude-sonnet-5-5` | 32,000 |
| docs | README and docs | `claude-haiku-5-5` | 8,192 |

A model's output is never accepted on the model's word: specs and plans pass validation gates, edits
pass the build, test, policy and security gates, and plans, risky changes and releases need a person's
approval.

**The defaults are only configuration.** They are the ids the committed cassettes were made with
(`agentic.models` in `application.yml`); no agent depends on them. Any id the configured provider serves
works. The defaults follow a general pattern: the strongest model for planning, design and code, a
mid-tier model for specs and tests, the smallest for docs. Output caps include any reasoning the model
does, which is why the code-writing agents get large caps. No `temperature` is sent unless one is
configured, because some models reject it.

**Fallback.** When a node's attempts are exhausted it can fall back to another executor. Today only the
planner has one: a deterministic template planner, labelled as such in the plan. A model-level fallback
(for example a stronger model after repeated failures) is not implemented; it is listed under
limitations.

## Run modes

| Mode | Set with | Key | Calls a model | Deterministic | For |
| --- | --- | --- | --- | --- | --- |
| replay (default) | `AGENTIC_MODE=replay` | no | no | yes | reviewers, CI, demos |
| record | `AGENTIC_MODE=record` | depends on the provider | yes | no | creating or refreshing a cassette |
| live | `AGENTIC_MODE=live` | depends on the provider | yes | no | measuring the models (`docker compose --profile live run --rm eval-live`) |

**How replay works.** A request's key is `sha256(model, system prompt, user prompt, parameters)`; the
parameters include the prompt's name and version. Record mode **replaces** the scenario's
`cassette.jsonl`: it empties it when the scenario starts, then appends each exchange as it happens. Replay looks the key up. A
miss fails that attempt with `cassette miss: re-record this scenario`; replay never falls back to a live
call. Replayed answers report zero tokens and cost. The provider is not part of the key, so a cassette
replays the same whichever provider recorded it. Because prompts contain the code an agent sees, any
change to a prompt, a model setting or the scenario's starting code requires re-recording. The default
build replays the planning stage of the brownfield and ambiguous cassettes; `-Dscenarios=true` replays
every committed cassette end to end (`CommittedCassettesReplayTest`).

**What replay proves, and what it does not.** It proves the orchestrator behaves the same way every time:
planning validation, gates, retries, approvals, re-planning, audit. It does not prove a model writes
correct code every time. The committed cassettes were generated from scripted answers and are labelled
so. Live mode with the eval harness is how model reliability would be measured.

## Choosing a provider and models

All settings are environment variables (or the matching `agentic.llm.*` and `agentic.models.*` keys in
`application.yml`). The `console-live`, `ping` and `eval-live` services pass all of them into Docker:

| Variable | Meaning | Default |
| --- | --- | --- |
| `AGENTIC_LLM_PROVIDER` | `anthropic` or `openai-compatible` | `anthropic` |
| `AGENTIC_LLM_BASE_URL` | `openai-compatible` only: the API root, ending in `/v1` | none |
| `AGENTIC_LLM_API_KEY` | the key, for any provider; may be empty for a local server | none |
| `AGENTIC_LLM_MODEL` | one model id for every agent (handy for a single local model) | per-agent ids above |
| `AGENTIC_MODELS_<AGENT>_MODEL` | one agent's model id, for example `AGENTIC_MODELS_CODER_MODEL` | per-agent ids above |
| `AGENTIC_LLM_TOKEN_PARAM` | `openai-compatible` only: `max_tokens`, or `max_completion_tokens` for servers that require it | `max_tokens` |

The provider's own key variable also works (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`), and
`ANTHROPIC_WORKSPACE_ID` is passed for Anthropic keys that need it.

Examples (put your provider's model ids in place of the placeholders):

```bash
# Anthropic: nothing to set but the key (the default model ids apply)
export ANTHROPIC_API_KEY=...

# OpenAI, or a hosted service with an OpenAI-compatible API
export AGENTIC_LLM_PROVIDER=openai-compatible
export AGENTIC_LLM_BASE_URL=https://api.openai.com/v1
export AGENTIC_LLM_API_KEY=...
export AGENTIC_MODELS_PLANNER_MODEL=<strong-model> AGENTIC_MODELS_ARCHITECT_MODEL=<strong-model> \
       AGENTIC_MODELS_CODER_MODEL=<strong-model>   AGENTIC_MODELS_REQUIREMENTS_MODEL=<mid-model> \
       AGENTIC_MODELS_TESTER_MODEL=<mid-model>     AGENTIC_MODELS_DOCS_MODEL=<small-model>

# A local model server (Ollama shown; vLLM and LM Studio work the same way), no key
export AGENTIC_LLM_PROVIDER=openai-compatible
export AGENTIC_LLM_BASE_URL=http://host.docker.internal:11434/v1   # Docker Desktop; localhost outside Docker
export AGENTIC_LLM_MODEL=<local-model>
```

Then check the setup with one tiny call, and use it:

```bash
docker compose --profile live run --rm ping        # replies OK, with tokens and cost
AGENTIC_MODE=live docker compose --profile live up console-live    # console with real models
docker compose --profile live run --rm eval-live   # pipeline-smoke 3 times with real models
docker compose --profile live run --rm eval-live eval brownfield,ambiguous --runs 3
```

`console-live` defaults to record mode, which replaces the cassette of every scenario you start (see
above); use `AGENTIC_MODE=live` unless you mean to re-record.

- The key is read from the environment only, passed only to the `console-live`, `ping` and `eval-live` services, and
  never written to a file, a log, an error message or an artifact. The secrets gates and the security
  scan block Anthropic- and OpenAI-style keys that appear in a change.
- **Choosing models.** The agents return large, strict JSON (plans and structured edits), so the planner,
  architect and coder need a model that follows a JSON contract reliably and has a large output limit.
  Small local models can run the pipeline but tend to fail those contracts more often: expect more
  retries and planner fallbacks. The eval harness in live mode measures exactly that.

## Where everything runs

| Component | Runs in | Network |
| --- | --- | --- |
| Orchestrator (engine, agents, gateway, console) | one container per command: `console`, `smoke`, `brownfield`, `ambiguous`, `eval`, `demo` (replay or no model), `console-live`, `ping`, `eval-live` (live) | replay: none needed; live: the configured provider only |
| Agents' builds and tests | Maven processes inside the orchestrator container, one per worktree | Maven Central for dependencies (or offline from `./.m2`) |
| Audit log | memory, or PostgreSQL with `SPRING_PROFILES_ACTIVE=postgres` and the `postgres` service | internal |
| Shortener | `shortener` service, with `shortener-db` (PostgreSQL) and `redis` | internal |
| Prometheus | `prometheus` service (profile `observability`) | internal |

Run workspaces and evidence packs are written to `./runs/` on your machine through a volume.

## What is sent to a model

Only the context bundle for the current task: the spec, the codebase analysis, the
files the agent's role may see (impacted files first) up to a size cap, and the previous attempt's
failure on a retry. A `context-manifest.json` artifact records what was included and what was cut. No
environment variables, keys or files outside the run's workspace are included. With a local model
server, no prompt or code leaves your machine even in live mode (Maven may still download dependencies;
set `AGENTIC_MVN_OFFLINE=true` once they are cached).

## Cost

Replay costs nothing. Cost is computed from the prices in `agentic.prices` (USD per million input and
output tokens); the defaults are list prices for the default model ids as of October 2026. Add an entry
for any other model to see its cost and have the cost budget enforced; a model without a price is
reported at $0, and its token and time budgets still apply. Every run's tokens and cost are in its
metrics, evidence pack and `/metrics`; each scenario has a budget (the greenfield scenario: 60 minutes,
3M tokens, $30) that safe-stops the run when exceeded. The partial live recordings made during
development cost a few dollars in total.

## Adding another provider

`ModelGateway` has one method, `complete(ModelRequest) → ModelResponse`. Implementations: the Anthropic
gateway, the OpenAI-compatible gateway, the replay and recording gateways, and scripted gateways in
tests. A provider with neither API needs one more class (send the model, system and user messages and
an output cap; return the text, token counts and whether the output cap was hit) and one entry in
`LiveGateways`. The engine, gates, prompts and agents do not change.
