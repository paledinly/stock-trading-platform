# ADR-007: Trade ledger and partial-sale accounting

Date: 2026-08-18

## Decision

Phase 4 stores BUY/SELL transaction records and calculates realized profit with a per-stock moving-average cost. A sell cannot exceed the position reconstructed from earlier records. Prices and amounts use `BigDecimal`; the server derives `amount = price × quantity`.

Holding duration starts at the first buy that opens the current position and resets when the position reaches zero. Timestamps are stored as UTC instants and presented in the client locale (`Asia/Seoul` for this product).

## Partial sales and future extension

A partial sale reduces quantity without changing the remaining moving-average cost. This personal MVP does not model broker executions, fees, taxes, account transfers or tax-lot selection. Those additions should introduce `order/execution/position-lot` records without rewriting the original trade ledger.
