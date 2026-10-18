# Architecture

The orchestrator takes a requirement through requirements, codebase analysis, planning, design,
implementation, testing, integration, documentation, security scanning and release readiness. LLM agents
do the creative work; everything that decides whether work is accepted is deterministic code, policy or
a person.

> **Agents propose, gates dispose.** An agent's output is a proposal. A gate (compiler, tests, policy,
> scanners), the policy engine or a human decides whether it is accepted. No LLM ever judges an LLM.

## Principles

| Principle | How it shows up |
| --- | --- |
| Deterministic control plane | The engine, gates, policy, metrics and audit log never call a model. An ArchUnit test fails the build if `gates` or `policy` depend on the `model` package. |
| Everything is an event | Run state is rebuilt from a per-run, hash-chained event log. A stopped or crashed run resumes from the log; metrics and evidence are computed from it. |
| Least privilege, enforced in code | Each agent can write only the paths `policies/autonomy.yaml` gives it. Edits outside them are refused by the tool layer, not by asking the model nicely. |
| Humans decide what is risky | Policy sets a minimum risk per node type, and gates escalate changes to sensitive files (migrations, `pom.xml`, security code, new dependencies, disabled tests); high-risk or escalated output waits for a human, and the approval is bound to the exact output digest. |
| Honest evidence | Replay, record and live modes are labelled everywhere; injected faults and automatic approvals are labelled in the audit log; scripted cassettes say so. |

## Components

```mermaid
flowchart LR
    subgraph Edge
        CLI["CLI: run, eval, verify-evidence"]
        Console["Approval console :8080<br/>/metrics"]
    end
    subgraph Orchestration
        Orch["Orchestrator<br/>planning run then execution run<br/>repair loop, clarifications, re-plan"]
        Engine["Engine<br/>one coordinator per run"]
    end
    subgraph Work
        Exec["Executors<br/>agents + deterministic steps"]
        GW["ModelGateway<br/>replay / record / live"]
        WS["Workspaces<br/>git repo per scenario, worktree per node"]
    end
    subgraph Control
        Gates["Gates<br/>build, tests, paths, diff, secrets,<br/>migrations, regression proof, scans"]
        Policy["Policy engine<br/>policy.yaml + autonomy.yaml"]
        Appr["Approvals<br/>digest-bound"]
    end
    subgraph Record
        Log["Event store<br/>hash-chained audit log"]
        Art["Artifact store<br/>content-addressed, with lineage"]
        Ev["Evidence packs, metrics, eval"]
    end
    CLI --> Orch
    Console --> Orch
    Orch --> Engine
    Engine --> Exec
    Exec --> GW
    Exec --> WS
    Engine --> Gates
    Engine --> Policy
    Engine --> Appr
    Engine --> Log
    Engine --> Art
    Log --> Ev
    Art --> Ev
```

| Package | Responsibility | Depends on |
| --- | --- | --- |
| `artifacts` | Content-addressed artifacts with lineage (producer, attempt, inputs, model, prompt hash); hashing | nothing |
| `domain` | Plan (validated DAG, topological order, digest), TaskNode, node status machine, retry policy, budgets | artifacts (hashing) |
| `events` | Event model, hash chain, in-memory and JDBC (PostgreSQL) stores, `AuditVerifier` | artifacts (hashing) |
| `policy` | Risk floors, approval threshold, protected and approval paths, agent autonomy | domain |
| `gates` | Deterministic exit checks | domain, artifacts |
| `engine` | Coordinator: scheduling, retries, fallbacks, gates, approvals, compensation, safe-stop, budgets, `markStale`, `supersede`, fault injection | domain, events, artifacts, gates, policy |
| `workspace` | Git repository per scenario, worktree per node, structured edit application within permissions | gates (for `WorkspaceRef`) |
| `model` | `ModelGateway`, cassettes, replay and recording gateways, live gateways (Anthropic; any OpenAI-compatible server) chosen by configuration | artifacts (hashing) |
| `codebase` | JavaParser repository index and impact analysis | nothing |
| `planning` | Requirement spec, plan proposal, plan validator, binder, template planner | domain, gates, artifacts |
| `agents` | Requirements, planner, architect, coder, tester, docs agents; codebase analyzer; integrate, security scan and release steps | engine, model, workspace, planning, codebase, gates, policy, domain, artifacts |
| `scenario` | Scenario loading, two-stage orchestration, repair loop, clarifications | agents, engine, model, workspace, planning, gates, policy, events, metrics, domain, artifacts |
| `metrics` | Run metrics and the Prometheus exporter | events, domain |
| `evidence` | Evidence packs | scenario, workspace, planning, metrics, events, domain, artifacts |
| `eval` | Eval harness | scenario, evidence, engine, metrics, planning, events |
| `app`, `web`, `demo` | Spring Boot wiring, CLI, approval console, fake-agent demo | the packages above |

`ArchitectureTest` enforces: the engine packages use no Spring or Jackson; gates and policy never touch
the model package; the engine never depends on the app or demo layers.

## How a scenario runs

```mermaid
sequenceDiagram
    participant H as Human
    participant P as Planning run
    participant E as Execution run
    P->>P: requirements (spec, ambiguities)
    alt low-confidence ambiguity
        P-->>H: escalate questions
        H->>P: clarification (re-runs requirements with the answer)
    end
    P->>P: analyze-codebase (deterministic impact report)
    P->>P: plan (validated, repaired up to twice, then template)
    P-->>H: approve plan (digest-bound)
    H->>P: approved
    P->>E: bind plan v1 to executors and gates
    E->>E: design, then parallel implement / migrate / test in own worktrees
    E->>E: integrate (merge, full build and tests, regression proof)
    alt merged code fails
        E->>E: repair round: re-run implementers (or testers) with the failures
    end
    alt scope changes mid-run
        H->>P: clarification
        P->>E: supersede run (compensate, cancel), re-plan, plan v2
    end
    E-->>H: approve migration, release (digest-bound)
    E->>E: evidence pack, metrics
```

1. **Planning run** (fixed shape): `requirements` → `analyze-codebase` → `plan`. The requirements agent
   writes a spec with acceptance criteria and explicit ambiguities. A LOW-confidence ambiguity (privacy,
   retention, security, public API) escalates to a human instead of being guessed. The codebase analyzer
   indexes the starting code and reports what the requirement touches. The planner proposes a DAG that a
   deterministic validator checks; a human approves it.
2. **Execution run** (the approved plan): `PlanBinder` maps each node type to an executor and a gate set;
   the planner chooses the shape, never the gates.
3. **Integration repair**: if the merged code fails its build or tests, the implementation nodes are
   re-run with the failures as feedback (the tests are the specification). If the regression-proof gate
   fails, the test nodes are re-run instead. At most two rounds; after that the run fails, with the
   failures in its evidence pack.
4. **Clarifications and re-planning**: before execution, a clarification re-runs the requirements step.
   During execution, it supersedes the running plan: finished work is compensated, pending nodes are
   cancelled, approvals are invalidated, and the spec, analysis and plan are redone as plan v2 in a new
   execution run.

## The engine

**One coordinator per run** owns every state change. Executors run in parallel on virtual threads (up to
`max-parallel`) and only return results; the coordinator applies them, evaluates gates and emits events.
Executors never touch run state; the few objects they share (the run's context and its git
repository) are synchronized.

**Node state machine** (enforced by a transition table, property-tested with jqwik):

`PENDING → READY → RUNNING → VERIFYING → SUCCEEDED`, with `RETRYING`, `AWAITING_APPROVAL`, `FAILED`,
`SKIPPED`, `CANCELLED`, `ROLLED_BACK` and `STALE` branches. A transition not in the table throws.

| Mechanism | Behaviour |
| --- | --- |
| Entry gates | Before a node runs: policy decision (a denial, for example a denied executor, safe-stops the run) and every input artifact present. |
| Exit gates | After each attempt, in order, fail-fast. The first failure becomes the feedback for the next attempt. |
| Retries | Per-node `RetryPolicy` (attempts, backoff). Feedback carries the gate failure, the exception, or the reviewer's rejection; reviewer feedback persists across later attempts. |
| Fallback | A node can list fallback executors; after the primary's attempts are exhausted the next one takes over (`FALLBACK_ACTIVATED`). Used for the planner → template planner. |
| Compensation and rollback | Executors implement `compensate`. A failed node is compensated; `rollback` compensates a node and its succeeded dependents in reverse dependency order. Edit agents reset their branch. |
| Safe-stop | Kill switch, budget breach or critical policy denial cancels in-flight attempts, records why, and leaves the run resumable. |
| Budgets | Tokens and cost per run, and wall time since the run was last started or resumed; exceeding one safe-stops the run. `Engine.updateBudget` raises a budget (recorded as `BUDGET_UPDATED`) so the run can resume; it is a Java API, not yet a console or CLI action. |
| Approvals | `AWAITING_APPROVAL` pauses the node; the decision must name the output digest it approves. Approving changed output is refused. Automatic approval exists only for unattended demos and labels itself. |
| Re-planning | `markStale(node)` resets a node and everything downstream, invalidating their approvals and keeping the rest; the repair loop after a failed JOIN uses it. `supersede(run)` ends a run that a new plan replaces: its finished work is rolled back and pending nodes are cancelled. |
| Crash resume | State is rebuilt from the log; nodes in flight at the crash are retried, finished nodes are not redone. Tested within one process; across a process restart it also needs the artifacts and plans, which are kept in memory today (see limitations). |
| Fault injection | `FaultSpec`: a failed attempt (as if the executor threw; it is not called) or a forced gate failure on given attempts, from the scenario file or `--inject`. Always recorded as `FAULT_INJECTED`. |

## Gates

| Gate | Checks | Bound to |
| --- | --- | --- |
| `outputs-present` | The attempt produced artifacts | every node |
| `no-secrets` | No secret patterns in text outputs | requirements, release |
| `spec-valid` | Spec parses and is complete; LOW-confidence ambiguities **escalate** to a human | requirements |
| `plan-valid` | Every criterion implemented and tested, tests independent of code, one design first, a join before release, release last, at most 15 nodes | plan |
| `protected-paths` | No change to `policies/**`, `Dockerfile`, `docker-compose.yml`, `.github/**`, production config | editing nodes, join |
| `secrets-in-diff` | No secret patterns in added lines | editing nodes |
| `diff-size` | At most 600 changed lines per node | editing nodes |
| `approval-paths` | Migrations, `pom.xml`, `**/security/**` changed: **escalate** | editing nodes |
| `new-dependency` | New dependency names in the build file: **escalate** with the license allowlist | editing nodes |
| `disabled-tests` | Tests disabled or deleted: **escalate** | editing nodes |
| `migrations-immutable` | No change to a migration that exists in the starting code (already applied in production) | editing nodes, join |
| `non-empty-change` | The node actually changed files | design, implement, migration, test |
| `build` | `mvn test-compile` in the node's worktree | design, implement, migration, join |
| `tests` | `mvn test` on the merged code | join |
| `regression-proof` | The run's new tests **fail** on the starting code (defect fixes) | join, when the scenario sets `regressionProof` |
| `security-clean` | Nine in-process rules over `src/main`: hard-coded secrets, Anthropic- and OpenAI-style API keys, private keys, command execution, Java deserialization, SQL concatenation (HIGH: fail); CORS wildcard, logging raw client IPs (MEDIUM: escalate) | security scan |

An escalation does not fail the node: it passes the gate and asks a human, with the reason shown in the
approval.

## Policy

`policies/policy.yaml` and `policies/autonomy.yaml` are protected paths: no agent can change them.

- **Risk floors** per node type (DESIGN and IMPLEMENT at least MEDIUM, SCHEMA_MIGRATION and
  RELEASE_READINESS HIGH). Policy can raise the planner's risk, never lower it.
- **Approval threshold**: HIGH and above waits for a human.
- **Protected paths** never change; **approval paths** escalate; **diff limit** 600 lines; **denied
  executors** for incident response.
- **Autonomy**: requirements and planner are read-only. Only the architect can write `application.yml`
  and `pom.xml`, and it also creates the shared Java code (error handler, configuration classes) so
  parallel implementers do not collide; the coder can write `src/main/java` and migrations, and its
  prompt keeps it to the package the design assigns it; the tester writes `src/test` only; docs writes
  `README.md` and `docs/`.

## Agents

| Agent | Kind | Sees (file contents) | Can write |
| --- | --- | --- | --- |
| requirements | model, read-only | the requirement, plus a clarification or the reviewer's feedback when it is redone | nothing: produces the spec |
| codebase analyzer | deterministic | the whole starting code | nothing: produces the impact report |
| planner | model, read-only | the spec and the impact report | nothing: produces the plan |
| architect | model, edit | main code and resources, `pom.xml`, `docs/` | `docs/`, the OpenAPI contract, `application.yml`, `pom.xml`, and the `api`, `domain`, `common` and `config` packages |
| coder | model, edit | main code and resources, `pom.xml`, `docs/` (including the design) | `src/main/java`, Flyway migrations |
| tester | model, edit | the design, the OpenAPI contract, the `api` and `domain` packages, existing tests, `pom.xml`; **no implementation classes** | `src/test` |
| docs | model, edit | `README.md`, `docs/`, the OpenAPI contract, the `api` package, `application.yml` | `README.md`, `docs/` |
| integrate, security scan, release | deterministic | the merged branch | the merge commit, the scan report, the release checklist |

Every edit agent (architect, coder, tester, docs) also gets the spec, the impact report and a list of all
file names; every model agent gets the previous attempt's failure on a retry. What an agent may write comes from `policies/autonomy.yaml` and is
enforced by the tool layer; what it sees is set per role in `EditAgent`. Each agent names a model id in
configuration and the configured provider serves it: no agent is tied to a provider or a model (defaults
and how to change them: [LLM_AND_RUNTIME.md](LLM_AND_RUNTIME.md)).

Agents return **structured edits** (`create`, `replace`, `patch` with a unique search string, `delete`),
not unified diffs. The tool layer applies them all-or-nothing inside the agent's writable paths and
commits a real diff for the gates and the approver. Each agent's context bundle holds the files its role
may see, with the impact report's files first, up to about 120,000 characters; a manifest records what
was included or cut.

**Blind tests.** Test nodes depend on the design, not on the implementation nodes, so the tester writes
its tests before the new implementation exists, and its context holds only the design, the contracts
and the `api` and `domain` packages. (When starting from existing code, those packages can contain
existing controllers, which the tester does see.) The tests and the implementation meet at the JOIN, which is where the tests must pass;
if they fail there, the implementation is reworked, not the tests.

## Workspaces

Each scenario gets one git repository seeded from its starting code (OS metadata files excluded); its
planning run, execution run and any re-planned execution run share it.
Each node gets its own worktree and branch, starting from the merge of its dependencies' commits, so
parallel nodes never see each other's half-done work and the JOIN is where their changes meet. A merge
conflict fails the JOIN with the conflicting files named. Compensation resets a node's branch. Git
metadata operations are serialized per repository; builds in different worktrees run in parallel.

## Model gateway and record/replay

Every model call goes through `ModelGateway`. The request key is
`sha256(model, system prompt, user prompt, parameters)`.

- **replay** (default): answers come from the scenario's `cassette.jsonl`. A missing key fails the attempt
  with "cassette miss: re-record this scenario"; replay never falls back to a live call. Replayed answers
  report zero tokens and cost.
- **record**: live calls. The scenario's cassette is replaced: emptied when the scenario starts, then each
  exchange is appended as it happens.
- **live**: the configured provider. `LiveGateways` builds one of two gateways, only in live or record
  mode, so replay never needs a key:
  - `anthropic`: Spring AI's Anthropic client, built by hand (no autoconfiguration). Thinking blocks are
    skipped.
  - `openai-compatible`: a small HTTP client for the Chat Completions protocol, so OpenAI and servers that
    expose the same API (Ollama, vLLM, LM Studio, API gateways) work by setting a base URL.

  Both send no temperature unless one is configured, retry a failed call once (the OpenAI-compatible
  gateway only on 429 and 5xx; the engine owns every other retry), and report an output-cap stop as truncation with an actionable message instead of
  a JSON parse error. The provider is not part of the request key: a cassette replays whichever provider
  recorded it.

Prompts are versioned files (`prompts/*.md`, each with a `version:` line; planner and edit-agent are at
v2). The prompt id (`name@version`) is part of every request key, and the rendered prompt's hash is
stored on every model-produced artifact, so a prompt change is visible in lineage and invalidates
cassettes.

## Audit, lineage and evidence

- **Audit log**: every state change, gate result, approval, clarification, fault, compensation, token
  count and decision is an event. Each event's hash covers its content and the previous hash;
  `AuditVerifier` detects an edited, inserted, removed or reordered event. Cutting events off the end, or
  rebuilding the whole chain, shows only as a different head hash, so the head hash must be kept
  elsewhere and compared (the evidence pack's README shows its first 16 characters; the comparison is
  manual today).
  In memory by default; PostgreSQL with the `postgres` profile.
- **Lineage**: every artifact records its producer node and attempt, executor, input artifact hashes,
  model and prompt hash.
- **Evidence pack** (after every run): a README (runs, plan versions, per-node gates, human input,
  failures and recoveries, agent decisions, metrics), the audit logs as JSONL, every artifact with its
  lineage, the final diff against the starting code and the metrics. `verify-evidence` re-checks the
  hash chains from those files alone.

## Observability

- `RunMetrics` from the log: attempts and failures, retries, fallbacks, rollbacks, compensations,
  injected faults, approvals, recovered nodes, MTTR with and without human wait, end-to-end time,
  tokens, cost.
- `GET /metrics` on the console: counters across all runs in Prometheus format, computed from the logs at
  scrape time (runs, attempts and failures, gate results, approvals, retries, fallbacks, rollbacks,
  compensations, injected faults, recoveries, re-plans, clarifications, tokens, cost, audit-chain
  health).
- The shortener exposes `/actuator/prometheus` (HTTP latency, click pipeline, cache).
- `ops/prometheus/alerts.yml`: broken audit chain, approval waiting 30 minutes, gate failure rate,
  dropped clicks, cache errors, p99 redirect latency.
- The eval harness turns N complete scenario runs into success rate, retries, recoveries, re-plans and
  latency percentiles.

## Key decisions

| Decision | Alternatives | Why |
| --- | --- | --- |
| Own engine, event-sourced | Temporal, LangGraph, Airflow | The orchestration layer is the product; owning it keeps every mechanism visible and testable. Event sourcing gives resume, audit and metrics from one source. |
| Deterministic gates only | LLM-as-judge | A model judging a model is not a control. Gates must be reproducible. |
| Structured edits | Unified diffs from the model | Diffs from models often fail to apply; structured edits are validated and applied all-or-nothing. |
| Worktree per node | One shared checkout | Parallel work without interference; every node's change is a reviewable commit. |
| Blind tests | Tests written after the code | Tests derived from the criteria, not the implementation, catch the implementation being wrong. |
| Record/replay | Live-only demos | Anyone can run every scenario without a model or a key, deterministically and at no model cost; live mode measures the models. |
| Two-stage runs | One DAG including planning | The plan is itself reviewed and approved before any code is written. |
| Java 21, Spring Boot, Spring AI | Python agent frameworks | Matches the author's stack; virtual threads make the parallel coordinator simple. |
