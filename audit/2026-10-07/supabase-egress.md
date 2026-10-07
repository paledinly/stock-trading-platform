# Supabase Egress read-only diagnosis (2026-10-07 KST)

User dashboard: approximately 17.3 GB on Oct 6, 54.01 GB during displayed period. Product/category breakdown and hourly billing export were not available. No production code/configuration/data was changed during this diagnosis.

## Measurements

Queries ran inside PostgreSQL with a read-only transaction; only aggregates/query statistics were returned. Raw input payloads were not downloaded for this audit.

- pg_stat_statements stats_reset: 2026-09-09 18:31:31.946295 UTC; dealloc 0. Counts are cumulative, NOT Oct 6 daily counts and NOT measured wire bytes.
- Oct 6 intraday_input: 1,320,826 rows, 2,741,333,207 payload bytes, average 2,075 bytes. Full-day replay/recovery can transfer approximately 2.74 GB of JSON plus protocol/other columns.
- Oct 1: 499,350 inputs / 1,032,445,377 payload bytes. Oct 2: 435,157 / 897,809,005 bytes.
- Oct 6 recommendations: 44; combined current snapshot/outcome JSON 219,043 bytes. First recommendation 11:16:00 KST, last 14:57:02 KST.
- SELECT recommendation WHERE id IN (44 placeholders): 30,911 calls / 1,360,082 rows. Current final JSON size * calls = approximately 6.77 GB. This is an indicative volume calculation, not historical exact bytes; payload sizes evolve and statement statistics do not retain parameter values/dates.
- Analogous IN (47) query: 31,167 calls / 1,464,848 rows. Oct 1 had 47 recommendations / current JSON size 230,477 bytes, consistent with the similar chart spike but not independent billing attribution.
- Final aggregate snapshot: recommendation-by-ID queries 137,885 calls / 3,779,727 rows; recommendation-by-date queries 8,038 calls / 123,024 rows; input-page queries 3,803 calls / 7,547,211 rows. Statistics changed between probes because the application remained active.
- Historical Scanner previous-candle query: 5,769,219 calls / 34,038,986 rows. The earlier Oct 6 fix removes incomplete-bar DB reads; these cumulative stats include the old behavior.

## Causal code paths

1. IntradayService.flush runs with default 1s fixed delay whenever enabled. It calls engine.advance and persist even if the batch is empty and after regular trading ends.
2. persist calls recommendations.findAllById for every engine result, loading immutable snapshot plus mutable outcome before checking whether any outcome changed. A no-op update therefore still causes database egress. This does not require a browser to be open.
3. recover calls replayEngine(today, null), paging the entire day's input JSON. Batch failures set engine=null, which can trigger another full recovery. /replay and /exit-comparison also read the full selected day.
4. Intraday UI polls recommendations every 5s and analytics every 30s; both use service.list. This is additional traffic, but the measured by-ID persistence row count is much larger.

## Priority corrections (not applied in this diagnostic task)

- Preserve expiry/time-exit handling but avoid DB persistence reads for empty unchanged batches and terminal outcomes, including after hours.
- Determine changed/new recommendation IDs before loading DB rows; do not reload immutable JSON every second. Keep optimistic locking, atomic input/outcome commits and recovery-after-rollback semantics.
- Bound recovery using authoritative outcome checkpoints and sufficient rolling input history; verify same results before replacing full replay.
- Require explicit full replay and provide payload-volume estimates; avoid automatic replays/failure loops. Cache identical completed-day research requests only with a stable input revision/cost/configuration key.
- Share/cache short-lived UI reads and suspend unnecessary background polling.
- Capture query-stat deltas with timestamps and Supabase product/hour egress breakdown for exact post-fix validation. Do not reset existing statistics for this audit.

Supabase reference: https://supabase.com/docs/guides/platform/manage-your-usage/egress (shared pooler/database responses to clients are egress; server-side rows scanned are not the same as rows transferred).
