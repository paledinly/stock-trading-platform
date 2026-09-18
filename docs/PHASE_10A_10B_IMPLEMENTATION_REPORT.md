# Phase 10A/10B implementation notes

## Implemented

- A recommendation generation creates a durable run. Repeated requests with the same idempotency key return that run; a new key preserves a separate evaluation. Existing recommendation IDs and linked performance/decision rows migrate into LEGACY runs.
- Next-session performance is tied to the expected trading date. Intraday observations are separate from completed close returns, and missing intervals remain incomplete rather than being called a final loss or win.
- Forward evaluation and the primary overnight replay now share `ClosingPrecisionEvaluator`: latest detection per stock, frozen as-of time, continuous finalized 5-minute candles, signal age, daily-data readiness, opportunity/risk thresholds, final score, and rank order.
- Evaluations retain detection-feature snapshots separately from data-readiness metadata. New detections record receipt time. Existing detections keep a null receipt time because that history cannot be reconstructed. The run's data version is a hash of the evaluated candidate snapshot; a later backfill produces a different version.
- The strict daily-MA comparison profile needs 60 finalized daily candles; the primary daily-MA strategy needs 21 and the previous trading day's finalized candle. A missing or late daily series is not treated as a neutral risk score.

## Operational prerequisite and limitations

- The primary evaluator needs at least 21 valid prior-session daily candles per stock. `KisDailyCandleClient` and `DailyCandleBackfillService` now prepare the recent detection/watchlist universe separately from recommendation generation. The daily preparation job runs at 08:10 KST on weekdays when KIS is enabled, and the advanced recommendation screen has an explicit preparation command.
- Daily import uses the existing global KIS pacing/retry path, a dedicated endpoint limiter, at most 40 stock requests per run by default, and a per-stock retry interval. It stores unadjusted (`FID_ORG_ADJ_PRC=1`) finalized `1D` candles to match the unadjusted intraday quote basis. The [official KIS domestic daily example](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py) documents this endpoint and its 100-row response limit.
- Preparation after the evaluation cutoff cannot retroactively turn a past run into a forward-verified recommendation. It readies the next trading session. Historical replays continue to reject candles that were not received by the frozen as-of time.
- Old scanner detections have no trustworthy receipt time. Historical replay from those rows is exploratory, not evidence of what the server could have known at that moment. A corrected candle row cannot reconstruct its pre-correction value; rows updated after the as-of time are excluded.
- The market holiday list is configuration-based. Verify the configured holidays against the exchange calendar before relying on date-completeness or daily-series freshness around unusual closures.
- Scoring still uses the most recent scanner feature snapshot, bounded to 30 minutes before evaluation; it does not synthesize a new VWAP/order-flow feature at freeze time. A dedicated feature snapshot feed would be needed to make the evaluation-time state independent of detection time.

## Verification

- Backend full test suite, including H2 application startup and PostgreSQL migration/reference-preservation tests.
- Frontend production build and closing-recommendation screen test.
- No production database migration was executed by this work; Flyway V22 and V23 run on the next backend startup after backup/rollout review.
