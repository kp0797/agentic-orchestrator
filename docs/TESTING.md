# Testing

```bash
docker compose run --rm test             # default: 120 orchestrator + 105 shortener tests, about 3 minutes
docker compose run --rm test-docker      # adds PostgreSQL and Redis via Testcontainers (Docker socket)
docker compose run --rm test-scenarios   # adds full scenario runs and replays of every committed cassette (slow)
```

Outside Docker: Java 21, Maven 3.9 and git, then `mvn verify` (add `-Ddocker=true` or `-Dscenarios=true`);
JaCoCo reports are in `*/target/site/jacoco/`. The Docker test services also write the output to
`build.log`, and keep `target/` on the Docker volumes `orchestrator-target` and `shortener-target`, so
their JaCoCo reports are in those volumes rather than in your `target/` folders.

Test counts are test cases as Surefire counts them: each case of a parameterized test counts once (the
shortener's 105 cases come from 54 test methods).

The default build needs no network and no Docker daemon: databases are H2 in PostgreSQL mode, DNS is
faked, the Redis cache is switched off (and the Redis client is tested against an unreachable port), and
the model is a scripted gateway or a cassette.

## What the layers prove

| Layer | Examples | What it proves |
| --- | --- | --- |
| Engine behaviour (22 tests) | `EngineTest`: diamond DAG with lineage, real parallelism, max-parallel, retries with feedback, gate failure → fallback, compensation in reverse order, digest-bound approvals, reviewer feedback across attempts, `markStale`, kill switch and resume, token budget, crash resume from the log alone, critical policy denial, missing input artifact, injected faults visible, determinism, state rebuilt from the log equals live state | The control plane does what the architecture claims, including the failure paths |
| Properties (jqwik) | `NodeStatusProperties`, `EventCodecProperties` | The node transition table has no dead ends and reaches SUCCEEDED only through verification or approval; event encoding round-trips for arbitrary data |
| Audit integrity | `AuditVerifierTest`, evidence tamper test | Editing, removing or reordering an event breaks the chain; truncation shows only as a different head hash; a changed value in an exported log is detected |
| Planning | `PlanValidatorTest`, `PlanTest`, `PromptLibraryTest` | Invalid plans are rejected with actionable errors; prompts are versioned and fully filled |
| Gates | `WorkspaceGatesTest`, `BrownfieldGatesTest` | Each gate passes, fails or escalates as specified; regression-proof distinguishes "fails on the original code", "passes there" and "does not compile there" |
| Workspaces | `WorkspaceTest` | Edits outside permissions are refused and nothing is applied; merges of parallel branches; conflicts named; compensation resets; OS metadata files ignored; a repository owned by another user works (needs root, so it runs in the Docker test service and is skipped otherwise) |
| Codebase analysis | `CodebaseAnalysisTest` | Index of types, references, endpoints, tables and migrations; impact analysis on a synthetic project and on the real brownfield template |
| Model layer | `ModelGatewayTest`, `RecordReplayTest`, `TruncationTest`, `AnthropicModelGatewayTest`, `OpenAiCompatibleModelGatewayTest`, `LiveGatewaysTest`, `ModelSettingsTest` | Record then replay reproduces the run with no model; a changed prompt is a loud cassette miss, never a live call; truncation and thinking blocks are handled; the OpenAI-compatible gateway against a local stub server (request shape, no temperature, either output-cap field, no key for local servers, truncation, one retry on 429, errors never contain the key); provider selection and key lookup; one model for every agent; blank per-agent overrides keep the default; configuration and code defaults agree |
| End to end | `ScenarioEndToEndTest` (5), `EvalHarnessTest` | Requirement to release through real git worktrees and real Maven builds; integration failures go back to the coder; out-of-permission writes refused; invalid plans fall back; the plan approval pauses before code exists; evidence packs and eval summaries are complete |
| Scenario cassettes | `BrownfieldCassetteTest`, `AmbiguousCassetteTest`, `EvalHarnessTest` | The planning stage of the committed brownfield and ambiguous cassettes, and all of pipeline-smoke, still replay; clarifications work scripted and typed by a person |
| Full scenarios (`-Dscenarios=true`) | `BrownfieldScenarioTest`, `AmbiguousScenarioTest`, `CommittedCassettesReplayTest` | Record, inject a fault, replay; every gate, approval, supersede and re-plan the scenario READMEs describe; every committed cassette replays end to end to a successful release |
| Architecture | `ArchitectureTest` (ArchUnit) | The engine packages use no Spring or Jackson; gates and policy never depend on the model package |
| Metrics and storage | `RunMetricsTest`, `PrometheusMetricsTest`, `PostgresProfileTest`, `JdbcEventStorePostgresTest` | Metrics match the log; the exporter's output is valid Prometheus text; the `postgres` profile stores the audit log in the database (H2 in PostgreSQL mode; real PostgreSQL with `-Ddocker=true`) |
| The shortener (105) | create, redirect, stats, alias rules, URL safety including SSRF (46 cases), rate limit, cache fallback and circuit breaker, async clicks and drop metric, retention, code collisions, a 16-request alias race, Prometheus endpoint | v1 meets its spec; PostgreSQL and Redis flows with `-Ddocker=true` |

## Coverage (JaCoCo, default build)

| Module | Lines | Branches |
| --- | --- | --- |
| orchestrator | 79.2% | 64.6% |
| shortener | 90.0% | 69.2% |

In the orchestrator, the core packages are covered at 83–96% (domain 96%, codebase 95%, events 95%,
policy 94%, agents 93%, planning 91%, artifacts 91%, evidence 88%, scenario 87%, engine 86%, gates 86%,
model 86%, workspace 83%). The live gateways
are at 80% (OpenAI-compatible 82%, against a stub server; Anthropic 59%, whose network path is exercised
by live calls rather than unit tests). The low numbers are the edges: the console's web layer (2%), the CLI
wiring (20%) and the fake-agent demo (0%), which are exercised by running them (console, CLI replays,
`docker compose run --rm demo`).

## Guards against drift

Some tests exist only to keep artifacts honest as the code changes:

- `ModelSettingsTest`: the model settings in `application.yml` equal the code's defaults, so a replay
  never misses because of configuration.
- `BrownfieldTemplateTest`: the brownfield starting code equals `shortener/` except for the files that
  carry the planted defect, the README, and the two tests that would have caught the defect.
- `BrownfieldCassetteTest`, `AmbiguousCassetteTest`: a change that makes the planning stage of a
  committed cassette stale fails the default build. `CommittedCassettesReplayTest` (`-Dscenarios=true`)
  replays every committed cassette end to end, so it also catches changes that only the execution
  agents see (the edit-agent prompt, README files).

## Checked by breaking things

Several tests were confirmed to fail for the right reason by temporarily reintroducing the bug:

- the v1 alias race test against the planted defect (fails: the alias is stored more than once; the
  number of 201 responses varies with timing);
- the file-ownership workspace test without `safe.directory` (fails with git's "dubious ownership");
- the evidence tamper test (a changed value in a valid JSON line is caught by its hash, not by a parse
  error);
- the regression-proof gate against a test that already passes on the original code.

## Not covered by automated tests

- Model quality: replayed cassettes are scripted. Live and record modes and the eval harness in live mode
  measure it; no full live recording of the greenfield scenario exists yet.
- Live providers end to end: the Anthropic gateway has been used for live calls; the OpenAI-compatible
  gateway is tested against a local stub server, but no live run against OpenAI or a local model server
  has been made yet.
- Load: the 1,000 redirects/s target is a k6 script (`shortener/load/redirects.js`) the build does not run.
- The approval console's pages: checked by hand and by HTTP calls against a running console.
- Alert rules: syntax-checked as YAML only, not with `promtool`.
