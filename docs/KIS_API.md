# KIS API

Phase 2 uses only the following official contracts:

| Purpose | Method | Endpoint | TR_ID |
|---|---|---|---|
| Access token | POST | `/oauth2/tokenP` | n/a |
| Current domestic quote | GET | `/uapi/domestic-stock/v1/quotations/inquire-price` | `FHKST01010100` |
| KOSPI master | file | `https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip` | n/a |
| KOSDAQ master | file | `https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip` | n/a |

The token is cached in memory and refreshed before expiry under a process-local lock. The quote client applies
timeouts, a configurable rate limiter and circuit breaker. Automatic retry is intentionally omitted for token
issuance to avoid duplicate issuance; retry policy for idempotent quote calls can be added after the account's
actual KIS limits are confirmed.

KIS response DTOs never escape the adapter. `prdy_vrss_sign` is applied to change/change-rate values and all
prices are mapped to `BigDecimal`. Secrets are never written to application logs.

Master parsing follows the official CP949 fixed-width layouts: KOSPI trailing 228 characters and KOSDAQ trailing
222 characters. A format mismatch fails the sync instead of partially importing corrupt records.
