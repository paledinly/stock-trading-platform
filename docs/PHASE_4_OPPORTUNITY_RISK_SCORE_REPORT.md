# Phase 4 Opportunity/Risk Score Report

Date: 2026-09-03

## Goal

Phase 4 adds separate opportunity and risk scores to scanner detections.

The score is intentionally not collapsed into one ranking number. A detection can be attractive and risky at the
same time, so the UI and API expose both values independently.

## Completed

- Added `OpportunityRiskScorer`
- Added `OpportunityRiskScore`
- Calculated 0~100 opportunity score
- Calculated 0~100 risk score
- Stored score factor breakdown as JSON
- Stored score version as `opportunity-risk-v1`
- Attached score data to every saved scanner detection
- Published opportunity/risk score in scanner SSE payload
- Exposed score fields in scanner detection API response
- Displayed Opportunity and Risk in the scanner detection list
- Added Flyway migration `V12__add_opportunity_risk_score.sql`
- Added scorer unit tests

## Opportunity Factors

- `priceMomentum`: positive 5-minute price change
- `volumeExpansion`: current 5-minute volume ratio above baseline
- `vwapLeadership`: current price position above VWAP
- `tradeStrength`: KIS trade strength above neutral 100
- `dayHighProximity`: proximity to intraday high

## Risk Factors

- `vwapOverextension`: excessive distance above VWAP
- `weakTradeStrength`: KIS trade strength below neutral 100
- `negativeMomentum`: negative 5-minute price change
- `farFromDayHigh`: distance from intraday high
- `sellPressure`: sell-side volume delta dominating buy-side delta

## API Fields

Scanner detection responses now include:

- `opportunityScore`
- `riskScore`
- `scoreVersion`
- `scoreBreakdown`

`scoreBreakdown` contains both factor maps, which makes later UI detail views and performance analysis possible
without recalculating historical detections.

## Database

Migration:

- `backend/src/main/resources/db/migration/V12__add_opportunity_risk_score.sql`

Columns:

- `scanner_detection.opportunity_score`
- `scanner_detection.risk_score`
- `scanner_detection.score_breakdown`
- `scanner_detection.score_version`

Indexes:

- `idx_detection_opportunity_score`
- `idx_detection_risk_score`

## Notes

- The first score version is rule-based and transparent.
- Scores use Phase 2B feature snapshots when available.
- Missing feature values are treated as neutral/zero instead of blocking detection.
- The current weights are fixed in code. The original roadmap mentions configurable weights; that remains a future
  enhancement unless product feedback shows the default score model is stable enough.

## Recommended Next Step

Proceed to Phase 5 Market Radar.

Phase 5 should use the Phase 2B feature snapshot, Phase 3 detection reason, and Phase 4 score breakdown together in
a detection detail screen. That is the natural place to explain why a stock was detected, why it looks attractive,
and what makes it risky.
