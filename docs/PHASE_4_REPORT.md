# Phase 4 Completion Report

Date: 2026-08-18

## Implemented

- BUY/SELL transaction CRUD with idempotent creation
- Server-derived `BigDecimal` amounts and optimistic versions
- Moving-average realized P&L, holding duration and oversell validation
- One-to-one investment journal with memo, target, stop-loss and classified/custom reasons
- Responsive web trade editor, history, realized-P&L summary and inline journal editor
- Calculation and component tests

## API

- `GET/POST /api/v1/trades`
- `GET/PATCH/DELETE /api/v1/trades/{id}`
- `PUT /api/v1/trades/{id}/journal`

See `ADR-007-TRADE-LEDGER.md` for partial-sale and future execution-model boundaries.
