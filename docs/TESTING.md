# Testing

```bash
docker compose run --rm test             # default: 104 orchestrator + 105 shortener tests, about 3 minutes
docker compose run --rm test-docker      # adds PostgreSQL and Redis via Testcontainers (Docker socket)
docker compose run --rm test-scenarios   # adds the full brownfield and ambiguous runs (about 10 minutes)
```

Outside Docker: Java 21, Maven 3.9, `mvn verify` (add `-Ddocker=true` or `-Dscenarios=true`). Output is
also written to `build.log`; JaCoCo reports are in `*/target/site/jacoco/`.

The default build needs no network and no Docker daemon: databases are H2 in PostgreSQL mode, DNS is
faked, Redis is replaced by a port, and the model is a scripted gateway or a cassette.

## What the layers prove

| Layer | Examples | What it proves |
| --- | --- | --- |
| Engine behaviour (22 tests) | `EngineTest`: diamond DAG with lineage, real parallelism, max-parallel, retries with feedback, gate failure → fallback, compensation newest first, digest-bound approvals, reviewer feedback across attempts, `markStale`, kill switch and resume, token budget, crash resume from the log alone, critical policy denial, missing input artifact, injected faults visible, determinism, state rebuilt from the log equals live state | The control plane does what the architecture claims, including the failure paths |
| Properties (jqwik) | `NodeStatusProperties`, `EventCodecProperties` | Illegal node transitions are impossible; event encoding round-trips for arbitrary data |
| Audit integrity | `AuditVerifierTest`, evidence tamper test | Editing, removing or reordering an event breaks the chain; a changed value in an exported log is detected |
| Planning | `PlanValidatorTest`, `PlanTest`, `PromptLibraryTest` | Invalid plans are rejected with actionable errors; prompts are versioned and fully filled |
| Gates | `WorkspaceGatesTest`, `BrownfieldGatesTest` | Each gate passes, fails or escalates as specified; regression-proof distinguishes "fails on the original code", "passes there" and "does not compile there" |
| Workspaces | `WorkspaceTest` | Edits outside permissions are refused and nothing is applied; merges of parallel branches; conflicts named; compensation resets; OS metadata files and foreign file ownership handled |
| Codebase analysis | `CodebaseAnalysisTest` | Index of types, references, endpoints, tables and migrations; impact analysis on a synthetic project and on the real brownfield template |
| Model layer | `ModelGatewayTest`, `RecordReplayTest`, `TruncationTest`, `SpringAiModelGatewayTest`, `ModelSettingsTest` | Record then replay reproduces the run with no model; a changed prompt is a loud cassette miss, never a live call; truncation and thinking blocks are handled; configuration and code defaults agree |
| End to end | `ScenarioEndToEndTest` (5), `EvalHarnessTest` | Requirement to release through real git worktrees and real Maven builds; integration failures go back to the coder; out-of-permission writes refused; invalid plans fall back; the plan approval pauses before code exists; evidence packs and eval summaries are complete |
| Scenario cassettes | `BrownfieldCassetteTest`, `AmbiguousCassetteTest` | The committed cassettes still match the prompts and starting code; clarifications work scripted and typed by a person |
| Full scenarios (`-Dscenarios=true`) | `BrownfieldScenarioTest`, `AmbiguousScenarioTest` | Record, inject a fault, replay; every gate, approval, supersede and re-plan the scenario READMEs describe |
| Architecture | `ArchitectureTest` (ArchUnit) | The engine packages use no Spring or Jackson; gates and policy never depend on the model package |
| Metrics | `RunMetricsTest`, `PrometheusMetricsTest`, `PostgresProfileTest` | Metrics match the log; the exporter's output is valid Prometheus text; the `postgres` profile stores the audit log in the database |
| The shortener (105) | create, redirect, stats, alias rules, SSRF (46 cases), rate limit, cache fallback and circuit breaker, async clicks and drop metric, retention, code collisions, a 16-request alias race, Prometheus endpoint | v1 meets its spec; PostgreSQL and Redis flows with `-Ddocker=true` |

## Coverage (JaCoCo, default build)

| Module | Lines | Branches |
| --- | --- | --- |
| orchestrator | 78.6% | 64.1% |
| shortener | 90.0% | 69.2% |

In the orchestrator, the core packages are covered at 82–96% (domain 96%, codebase 95%, events 94%,
policy 94%, agents 92%, planning 90%, scenario 87%, engine 86%, gates 85%). The low numbers are the edges:
the console's web layer (2%), the CLI wiring (17%) and the live Spring AI client (26%), which are exercised
by running them (console, CLI replays, live recording) rather than by unit tests.

## Guards against drift

Some tests exist only to keep artifacts honest as the code changes:

- `ModelSettingsTest`: the model settings in `application.yml` equal the code's defaults, so a replay
  never misses because of configuration.
- `BrownfieldTemplateTest`: the brownfield starting code equals `shortener/` except for the files that
  carry the planted defect.
- `BrownfieldCassetteTest`, `AmbiguousCassetteTest`: a prompt, analysis or starting-code change that
  would make a committed cassette stale fails the default build, not a demo.

## Checked by breaking things

Several tests were confirmed to fail for the right reason by temporarily reintroducing the bug:

- the v1 alias race test against the planted defect (fails: the alias is stored several times; in one run
  all 16 concurrent requests got 201);
- the file-ownership workspace test without `safe.directory` (fails with git's "dubious ownership");
- the evidence tamper test (a changed value in a valid JSON line is caught by its hash, not by a parse
  error);
- the regression-proof gate against a test that already passes on the original code.

## Not covered by automated tests

- Model quality: replayed cassettes are scripted. Live and record modes and the eval harness in live mode
  measure it; no full live recording of the greenfield scenario exists yet.
- Load: the 1,000 redirects/s target is a k6 script (`shortener/load/redirects.js`) the build does not run.
- The approval console's pages: checked by hand and by HTTP calls against a running console.
- Alert rules: syntax-checked as YAML only, not with `promtool`.
