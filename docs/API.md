# API

## Stock

- `GET /api/v1/stocks/search?q={keyword}&limit={1..50}`: active stock name/code search
- `GET /api/v1/stocks/{stockCode}`: stock master detail
- `GET /api/v1/stocks/{stockCode}/quote`: current KIS quote

Errors use RFC 9457 Problem Details with a stable `code` property.
