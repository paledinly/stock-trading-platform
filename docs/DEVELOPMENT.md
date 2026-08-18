# Development

## Phase 1

1. Copy `.env.example` to `.env` and change local passwords.
2. Start PostgreSQL and Redis with `docker compose up -d`.
3. Run backend tests with `backend\\gradlew.bat test`.
4. Run web tests with `npm test` in `web`.
5. Run Flutter tests with `flutter test` in `mobile`.

Phase 1 contains infrastructure only; Phase 2 adds KIS integration and the stock/quote domain foundation.

## Phase 2 local configuration

Set `KIS_ENABLED=true`, `KIS_APP_KEY` and `KIS_APP_SECRET` only in the untracked `.env` file. To load stock
master automatically, also set `KIS_MASTER_SYNC_ENABLED=true`. Do not enable scheduled sync in tests.
