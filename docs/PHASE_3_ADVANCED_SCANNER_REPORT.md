# Phase 3 Advanced Scanner

Date: 2026-09-03

## Implemented

- Added advanced scanner types:
  - `VOLUME_BREAKOUT`
  - `TURNOVER_BREAKOUT`
  - `HIGH_BREAKOUT`
  - `VWAP_BREAKOUT`
  - `VWAP_RECLAIM`
  - `PULLBACK_REBREAK`
- Extended scanner setting database constraint with Flyway `V11__add_advanced_scanner_types.sql`.
- Added `detection_reason` to `scanner_detection` for structured scanner explanations.
- Refactored `ScannerEvaluator` into a reusable calculation core that returns a `Decision`.
- Kept existing `VOLUME`, `PRICE_RISE` and `MOMENTUM` behavior compatible through the previous `matches` path.
- Connected Phase 2B `MarketFeatureSnapshot` values to advanced scanner decisions.
- Stored detection-time reason JSON and feature snapshot together on scanner detections.
- Added scanner SSE event names for advanced scanner types.
- Added default advanced scanner presets for missing bootstrap settings.
- Exposed advanced scanner tabs in the web scanner workspace.

## Detection reason

Detection reasons use `scanner-reason-v1` JSON and currently include:

- scanner type
- decision state
- five-minute change rate
- volume ratio
- previous high
- VWAP
- VWAP distance
- turnover ratio
- trade strength
- day-high distance
- feature version

## Boundary

- Phase 3 does not implement Opportunity/Risk Score.
- Phase 3 does not implement Market Radar detail pages.
- Phase 3 does not make scanner setting UX fully editable.
- `PULLBACK_REBREAK` uses the current candle, prior high and VWAP relationship as a conservative state proxy; richer state machines can be introduced before backtesting.
- Mobile still exposes the existing compact scanner controls. Full mobile advanced scanner UX belongs with the later Market Radar/mobile UX phase.

## Verification

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```
