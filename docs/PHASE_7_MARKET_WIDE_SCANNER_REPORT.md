# Phase 7 Market-wide Scanner Report

Date: 2026-09-03

## Goal

Phase 7 adds a market-wide scanner without violating KIS realtime subscription limits.

The design uses REST-based Broad Scan to reduce the universe, then allows WebSocket Precision subscription only for
shortlisted candidates.

## Completed

- Added Market-wide Scanner backend API
- Added REST quote based Broad Scan service
- Added tradable universe summary
- Added market regime summary
- Added broad candidate scoring
- Added precision eligibility based on current realtime subscription capacity
- Added web Market-wide Scanner workspace
- Added market, scan limit, candidate count and ETF controls
- Added shortlist table with broad score, liquidity and price strength
- Added Precision subscription action for eligible candidates
- Added frontend test coverage for the new workspace

## API

Endpoint:

```text
GET /api/v1/market-wide/scan?market=&limit=&candidates=&includeEtf=
```

Response includes:

- scan metadata
- universe summary
- market regime
- candidate shortlist
- precision subscription eligibility

## Safety Boundaries

- Phase 7 does not subscribe the whole market through WebSocket.
- Broad Scan uses REST quote sampling with a capped `limit`.
- Current server cap is 120 scanned stocks per request.
- Candidate result cap is 30.
- Precision subscription still goes through the existing realtime subscription registry and its configured limit.

## Current Broad Score

The first scoring version is intentionally simple:

- positive price change
- accumulated trading value
- current position inside intraday range

This is only for universe reduction. Final signal quality is still handled by Phase 3 scanner logic, Phase 4
Opportunity/Risk score and Phase 6 performance analytics.

## Validation

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## Recommended Next Step

Proceed to Phase 8 Backtesting.

Backtesting should reuse the same scanner evaluation logic where possible and must avoid using data that would not
have been known at the evaluated point in time.
