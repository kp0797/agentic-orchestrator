# Eval summary

Measured on 2026-10-18 with `eval pipeline-smoke,brownfield,ambiguous --runs 2` (replay mode, offline Maven,
in the development sandbox). The evidence directories listed below are not committed; `docker compose run
--rm eval` regenerates all of this on your machine.

Mode: **replay**. Every run replays the same recorded model answers, so these numbers measure the orchestrator (determinism, gates, recovery paths, real builds and tests), not model quality. Run with AGENTIC_MODE=live to measure how reliably the models complete the same scenarios.

| Scenario | Runs | Success | Attempt success | Retries/run | Recoveries/run | Re-plans/run | Human decisions/run | MTTR | Wall p50 | Wall p95 | Mean cost |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pipeline-smoke | 2 | 100% | 100% | 0.00 | 0.00 | 0.00 | 2.00 | n/a | 13.8s | 15.3s | $0.0000 |
| brownfield | 2 | 100% | 92% | 1.00 | 1.00 | 0.00 | 3.00 | 12.0s | 77.8s | 88.1s | $0.0000 |
| ambiguous | 2 | 100% | 100% | 1.00 | 0.00 | 1.00 | 6.00 | n/a | 64.9s | 122.2s | $0.0000 |

## Runs

| Scenario | # | Result | Attempts (failed) | Retries | Faults injected | Re-plans | Wall time | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pipeline-smoke | 1 | SUCCEEDED | 10 (0) | 0 | 0 | 0 | 15.3s | `runs/eval-20261018-132324/pipeline-smoke-1` |
| pipeline-smoke | 2 | SUCCEEDED | 10 (0) | 0 | 0 | 0 | 13.8s | `runs/eval-20261018-132324/pipeline-smoke-2` |
| brownfield | 1 | SUCCEEDED | 12 (1) | 1 | 0 | 0 | 77.8s | `runs/eval-20261018-132324/brownfield-1` |
| brownfield | 2 | SUCCEEDED | 12 (1) | 1 | 0 | 0 | 88.1s | `runs/eval-20261018-132324/brownfield-2` |
| ambiguous | 1 | SUCCEEDED | 17 (0) | 1 | 0 | 1 | 64.9s | `runs/eval-20261018-132324/ambiguous-1` |
| ambiguous | 2 | SUCCEEDED | 17 (0) | 1 | 0 | 1 | 122.2s | `runs/eval-20261018-132324/ambiguous-2` |

Definitions: a run succeeds when its final execution run succeeds. Attempt success is the share of node attempts that passed all their gates. A recovery is a node that failed an attempt and later succeeded. MTTR is the mean time from a node's first failed attempt to its success. Wall time spans the scenario's first to last audit event, including any re-plans.
