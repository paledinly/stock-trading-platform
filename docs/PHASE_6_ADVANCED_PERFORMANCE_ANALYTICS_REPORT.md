# Phase 6 Advanced Performance Analytics Report

Date: 2026-09-03

## Goal

Phase 6 extends scanner analytics from simple win-rate/average-return reporting into an edge-analysis workspace.

The feature uses existing detection and performance data. No new database table is required.

## Completed

- Extended `GET /api/v1/scanner-analytics`
- Added configurable query parameters:
  - `targetRate`
  - `stopRate`
  - `minimumSampleSize`
- Added Target/Stop outcome summary
- Added time-of-day performance buckets
- Added signal combination analysis by:
  - scanner type
  - Opportunity score band
  - Risk score band
- Added historical edge summary
- Added confidence labels based on minimum sample size
- Reworked the analytics UI into `Signal Edge Lab`
- Added UI controls for target, stop and minimum sample size
- Added panels for:
  - average milestone returns
  - Target/Stop comparison
  - time bucket results
  - signal combination results
  - historical edge

## API

Endpoint:

```text
GET /api/v1/scanner-analytics?from=&to=&settingId=&targetRate=&stopRate=&minimumSampleSize=
```

Existing response fields remain available:

- `total`
- `completed`
- `dataMissing`
- `winRate5m`
- `winRateClose`
- `averageReturn5m`
- `averageReturn10m`
- `averageReturn30m`
- `averageReturn60m`
- `averageReturnClose`
- `calculationVersion`

New response fields:

- `targetRate`
- `stopRate`
- `targetStop`
- `timeBuckets`
- `signalCombinations`
- `historicalEdge`
- `minimumSampleSize`

## Notes

- Target/Stop is calculated from available `maxReturn` and `maxDrawdown`.
- If both target and stop are touched in the available observation window, exact first-touch order is not inferable from the current aggregate performance row. Those cases are grouped as `neither`.
- Exact first-touch ordering would require tick-level or candle-sequence persistence after detection.
- Signal combinations use the Phase 4 Opportunity/Risk score bands:
  - `HIGH`: 70+
  - `MID`: 40~69.999
  - `LOW`: below 40
  - `UNKNOWN`: score missing
- Confidence is based on sample size compared with `minimumSampleSize`.

## Validation

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## Recommended Next Step

Proceed to Phase 7 Market-wide Scanner after live validation of Phase 6 analytics.

Phase 7 should avoid subscribing the whole market through WebSocket. Use a broad REST-based scan for the universe and
WebSocket only for precision monitoring of shortlisted candidates.
