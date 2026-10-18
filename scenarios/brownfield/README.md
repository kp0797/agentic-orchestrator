# Brownfield scenario: fix a production defect in an existing service

```bash
docker compose run --rm brownfield                     # replay, approvals automatic and labelled (1–2 min of run time)
docker compose run --rm brownfield run brownfield \
    --auto-approve --inject fix-alias-race:gate:build@1 # same, with an injected gate failure that is retried
```

## The starting point

`scenarios/templates/shortener-v1` is the shortener in `shortener/` with one planted defect, as it might
have shipped: alias creation checks `findByCode` and then inserts, and `links.code` has a plain index
instead of a unique constraint. Concurrent requests for one alias store the same code twice, and every
later lookup of that code fails with HTTP 500. The tests that would have caught it are absent.
`BrownfieldTemplateTest` keeps the copy identical to `shortener/` apart from `LinkService`,
`LinkRepository`, the V1 migration, the README, and the two tests that would have caught the defect.

## What happens

| Step | Node | What is real |
| --- | --- | --- |
| 1 | `requirements` | The incident report becomes a spec with two criteria and one ambiguity (which duplicate survives). |
| 2 | `analyze-codebase` | **Deterministic.** JavaParser indexes the code and the migrations; the report names the alias path (`AliasPolicy`, `LinkRepository`, `LinkService`), the endpoints and tests in scope, the `links` table, and that the next migration is V3. The planner and every agent see this report. |
| 3 | `plan` | ADR, then three parallel tasks (migration, service fix, regression tests), JOIN, docs, scan, release. Validated, then approved. |
| 4 | `migrate-unique-codes` | **Deliberate mistake:** the first answer edits the applied `V1` migration. The `migrations-immutable` gate rejects it; the retry adds `V3__unique_link_codes.sql` (deletes later duplicates, adds the unique constraint). HIGH risk and a migration path: the run waits for approval (automatic and labelled as such with `--auto-approve`; a person decides in the console). |
| 5 | `fix-alias-race` | `LinkService` inserts and maps `DuplicateKeyException` to 409, instead of checking first. Compiled by the build gate. |
| 6 | `regression-tests` | Written without seeing the fix: a 16-request race (AC1) and a Flyway test that seeds duplicates under V2 and migrates (AC2). |
| 7 | `integrate` | Real merge of the three branches; the **whole existing suite** runs on the merged code; then `regression-proof` runs the new tests against the **original** code and requires them to fail there. |
| 8 | `docs`, `security-scan`, `release-readiness` | README update, scan, evidence and the release approval. |

Every step is in the hash-chained audit log, and every artifact has lineage back to the incident text.

## What is scripted

The agents' answers in `cassette.jsonl` were written by hand
(`orchestrator/src/test/java/.../scenario/BrownfieldScript.java`), not recorded from a model, so the
scenario shows the orchestration, gates and guardrails deterministically, not model quality. Everything
else is real: the codebase analysis, git worktrees and merges, Maven builds and test runs of the full
service, policy, approvals and audit. To run it with real models, configure a provider (see
[LLM_AND_RUNTIME.md](../../docs/LLM_AND_RUNTIME.md#choosing-a-provider-and-models)), start
`AGENTIC_MODE=live docker compose --profile live up console-live` and start `brownfield` in the console.

`BrownfieldCassetteTest` replays the planning stage from this cassette in the default build;
`CommittedCassettesReplayTest` (with `-Dscenarios=true`) replays all of it. `BrownfieldScenarioTest`
(also `-Dscenarios=true`) records and replays the full run from the script and regenerates the cassette
with `-Dbrownfield.cassette.out=../scenarios/brownfield/cassette.jsonl` (the path is relative to
`orchestrator/`).
