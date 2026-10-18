# Ambiguous scenario: an unclear request, open questions, and a scope change mid-run

```bash
docker compose run --rm ambiguous     # replay, unattended: the product owner's answers are scripted (about 4 min)
```

The request is deliberately vague: *"Link owners keep asking how many people actually visit their links,
not just how many clicks there are."* Counting people means identifying them, which is a privacy
decision. The system must not make it silently.

## What happens

| Step | Where | What you see |
| --- | --- | --- |
| 1 | `requirements` (planning) | The spec has one criterion and an open question: *how is a visitor identified? IPs are personal data*. Its confidence is LOW, so the `spec-valid` gate **escalates to a human**. Nothing is planned. |
| 2 | clarification | The product owner answers: no IPs or user agents, not even hashed with a fixed key; a salt that changes every UTC day and is never stored; per-day counts only. Recorded as `CLARIFICATION_RECORDED`; the requirements agent re-runs with the answer and adds the privacy criterion (AC2). |
| 3 | `analyze-codebase`, `plan` | The real shortener is analyzed; plan **v1** (8 nodes) puts a design review first, because visitor identity is a privacy decision. |
| 4 | `design` (execution, plan v1) | The ADR is written and goes to review. At the review, the product owner **changes scope**: marketing also needs unique visitors per referrer. |
| 5 | re-plan | The running execution is **superseded**: pending nodes cancelled, the design's branch compensated, the run stopped with the reason. The requirements, analysis and plan are redone with the new information (spec v3, AC3); the plan's earlier approval is invalidated and asked again. |
| 6 | execution, plan **v2** | 9 nodes: the new plan adds a test task for AC3. Migration V3 (approved), implementation, two blind test tasks, JOIN with the **full existing suite**, docs, scan, release approval. |

The CLI prints all three runs in order: planning, the superseded execution (plan v1) and the final
execution (plan v2). Every audit chain verifies.

## Doing it yourself

In the approval console (`docker compose up console`), start `ambiguous`: no scripted stakeholder is
used there. Answer the open question with **Clarify or change scope** on the run page (or reject the
requirements step with your answer); send a second clarification after the plan is approved to see a
re-plan. In replay mode only the recorded answers can replay: different words are a cassette miss,
because the agents' replies to them were never recorded. Use live mode (`AGENTIC_MODE=live` with an API
key) to answer in your own words.

## What is scripted

The agents' answers (`orchestrator/src/test/java/.../scenario/AmbiguousScript.java`, with the delivered
code in `orchestrator/src/test/resources/scripted/ambiguous/`) and the product owner's two messages
(`stakeholder:` in `scenario.yaml`, labelled `scripted` in the audit log) were written by hand. The code
was built and tested against the real shortener before it was scripted. Everything else is real: the
escalation, the clarification handling, superseding and compensation, re-planning, git, Maven builds
and test runs, policy and approvals.

`AmbiguousCassetteTest` checks the clarification path in the default build; `AmbiguousScenarioTest`
(`-Dscenarios=true`) records and replays the whole run and regenerates the cassette with
`-Dambiguous.cassette.out=scenarios/ambiguous/cassette.jsonl`.
