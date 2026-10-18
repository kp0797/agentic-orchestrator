# Eval summary

Measured on 2026-10-18 with `eval pipeline-smoke,brownfield,ambiguous --runs 3` (replay mode, offline
Maven, in the development sandbox), the same command as `docker compose run --rm eval`. The evidence
directories listed below are not committed; that command regenerates all of this on your machine. Wall
times depend on the machine.

Notes on reading the table:

- **Approval decisions** were all automatic (`eval-harness`, labelled in each audit log); in the console a
  person makes them.
- **brownfield**'s retry is the deliberate scripted mistake (an edit to an applied migration) that the
  `migrations-immutable` gate rejects; its recovery is that node succeeding on the retry.
- **ambiguous**'s retry is not a failure: the product owner's scripted answer rejects the first spec with
  the clarification, so the requirements step is redone. Its re-plan is the mid-run scope change.

Mode: **replay**. Every run replays the same model answers from the scenario's cassette, so these numbers measure the orchestrator (determinism, gates, recovery paths, real builds and tests), not model quality. Run eval in live mode (docker compose --profile live run --rm eval-live) to measure how reliably the models complete the same scenarios.

| Scenario | Runs | Success | Attempt success | Retries/run | Recoveries/run | Re-plans/run | Approval decisions/run | MTTR | Wall p50 | Wall p95 | Mean cost |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pipeline-smoke | 3 | 100% | 100% | 0.00 | 0.00 | 0.00 | 2.00 | n/a | 13.0s | 13.1s | $0.0000 |
| brownfield | 3 | 100% | 92% | 1.00 | 1.00 | 0.00 | 3.00 | 10.2s | 103.0s | 106.3s | $0.0000 |
| ambiguous | 3 | 100% | 100% | 1.00 | 0.00 | 1.00 | 6.00 | n/a | 115.2s | 166.3s | $0.0000 |

## Runs

| Scenario | # | Result | Attempts (failed) | Retries | Faults injected | Re-plans | Wall time | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pipeline-smoke | 1 | SUCCEEDED | 10 (0) | 0 | 0 | 0 | 13.1s | `runs/eval-20261018-181735/pipeline-smoke-1` |
| pipeline-smoke | 2 | SUCCEEDED | 10 (0) | 0 | 0 | 0 | 13.0s | `runs/eval-20261018-181735/pipeline-smoke-2` |
| pipeline-smoke | 3 | SUCCEEDED | 10 (0) | 0 | 0 | 0 | 13.0s | `runs/eval-20261018-181735/pipeline-smoke-3` |
| brownfield | 1 | SUCCEEDED | 12 (1) | 1 | 0 | 0 | 106.3s | `runs/eval-20261018-181735/brownfield-1` |
| brownfield | 2 | SUCCEEDED | 12 (1) | 1 | 0 | 0 | 103.0s | `runs/eval-20261018-181735/brownfield-2` |
| brownfield | 3 | SUCCEEDED | 12 (1) | 1 | 0 | 0 | 81.8s | `runs/eval-20261018-181735/brownfield-3` |
| ambiguous | 1 | SUCCEEDED | 17 (0) | 1 | 0 | 1 | 93.2s | `runs/eval-20261018-181735/ambiguous-1` |
| ambiguous | 2 | SUCCEEDED | 17 (0) | 1 | 0 | 1 | 166.3s | `runs/eval-20261018-181735/ambiguous-2` |
| ambiguous | 3 | SUCCEEDED | 17 (0) | 1 | 0 | 1 | 115.2s | `runs/eval-20261018-181735/ambiguous-3` |

Definitions: a run succeeds when its final execution run succeeds. Attempt success is the share of node attempts that passed all their gates. A recovery is a node that failed an attempt and later succeeded. MTTR is the mean time from a node's first failed attempt to its success. Wall time spans the scenario's first to last audit event, including any re-plans.
