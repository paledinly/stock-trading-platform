## 시작

### Bash

프로젝트 루트에서 환경 파일과 PostgreSQL·Redis를 준비합니다.

```bash
cp .env.example .env
docker compose up -d
```

Backend를 실행합니다.

```bash
cd backend
./gradlew bootRun
```

새 터미널에서 Web을 실행합니다.

```bash
cd web
npm install
npm run dev
```

Flutter가 설치되어 있다면 새 터미널에서 Mobile을 실행합니다.

```bash
cd mobile
flutter pub get
flutter run
```

### Windows PowerShell

프로젝트 루트에서 환경 파일과 PostgreSQL·Redis를 준비합니다.

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform
Copy-Item .env.example .env
docker compose up -d
```

`.env` 값을 현재 PowerShell 프로세스에 적용합니다.

```powershell
Get-Content .env |
    Where-Object { $_ -and -not $_.StartsWith('#') } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
```

같은 PowerShell 창에서 Backend를 실행합니다.

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform\backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat bootRun
```

새 PowerShell 창에서 Web을 실행합니다.

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform\web
npm install --cache .npm-cache
npm run dev
```

Flutter가 설치되어 있다면 새 PowerShell 창에서 Mobile을 실행합니다.

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform\mobile
flutter pub get
flutter run
```

Backend health:

```text
http://localhost:8080/actuator/health
```

Web:

```text
http://localhost:5173
```

## Supabase DB 사용

로컬 Docker PostgreSQL 대신 Supabase PostgreSQL을 사용할 수 있습니다. Redis는 로컬 Docker 또는 Upstash/VPS Redis를 계속 사용할 수 있습니다.

### `.env` 설정

Supabase Session Pooler를 사용하는 경우 프로젝트 루트의 `.env`에서 DB 설정을 다음 형태로 변경합니다.

```env
DB_URL=jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=require
DB_USERNAME=postgres.umyianworjumpiockbrr
DB_PASSWORD=Supabase_DB_비밀번호
```

Direct connection을 사용하는 경우에는 Supabase Dashboard의 host 값을 사용합니다.

```env
DB_URL=jdbc:postgresql://db.프로젝트_REF.supabase.co:5432/postgres?sslmode=require
DB_USERNAME=postgres
DB_PASSWORD=Supabase_DB_비밀번호
```

> Supabase Direct connection은 IPv6 환경이 필요할 수 있습니다. 로컬 PC나 VPS에서 연결이 되지 않으면 Session Pooler를 사용하세요.

### 테이블 생성

Supabase DB가 비어 있다면 백엔드를 한 번 실행해 Flyway 마이그레이션으로 테이블을 생성합니다.

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform\backend
.\gradlew.bat bootRun
```

`flyway_schema_history`는 Flyway가 마이그레이션 실행 이력을 관리하는 테이블입니다. 로컬 데이터를 이관할 때 이 테이블은 제외하고, Supabase에 생성된 이력은 유지합니다.

### 로컬 Docker PostgreSQL 데이터 이관

로컬 PostgreSQL에서 데이터만 dump합니다.

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform
New-Item -ItemType Directory -Force backup
docker compose exec postgres sh -c "pg_dump -U stock -d stock_platform --data-only --format=custom --no-owner --no-privileges --exclude-table=flyway_schema_history -f /tmp/stock_data.dump"
$cid = docker compose ps -q postgres
docker cp "${cid}:/tmp/stock_data.dump" ".\backup\stock_data.dump"
```

Supabase에 이미 일부 데이터가 들어갔다면 restore 전에 앱 테이블만 비웁니다. `flyway_schema_history`는 비우지 않습니다.

```sql
truncate table
  overnight_position_decision,
  overnight_performance,
  closing_recommendation,
  detection_performance,
  scanner_detection,
  stock_candle,
  market_broad_snapshot,
  market_wide_scan_run,
  precision_subscription_session,
  trade_reason,
  investment_journal,
  trade,
  watchlist_item,
  watchlist_group,
  scanner_setting,
  platform_schema_version,
  stock
restart identity cascade;
```

dump 파일을 Supabase로 restore합니다.

```powershell
docker run --rm -e PGPASSWORD="Supabase_DB_비밀번호" -v "${PWD}\backup:/backup" postgres:17-alpine pg_restore `
  --host=aws-0-ap-northeast-2.pooler.supabase.com `
  --port=5432 `
  --username=postgres.umyianworjumpiockbrr `
  --dbname=postgres `
  --data-only `
  --no-owner `
  --no-privileges `
  /backup/stock_data.dump
```

> Supabase에서는 일반 사용자가 시스템 FK 트리거를 끌 수 없으므로 `pg_restore --disable-triggers` 옵션을 사용하지 않습니다.

### 이관 확인

Supabase SQL Editor에서 데이터와 용량을 확인합니다.

```sql
select count(*) from stock;
select count(*) from stock_candle;
select count(*) from scanner_detection;
select count(*) from market_broad_snapshot;
select count(*) from closing_recommendation;
select pg_size_pretty(pg_database_size(current_database()));
```

## 테스트

### Bash

```bash
(cd backend && ./gradlew clean test)
(cd web && npm test && npm run build)
(cd mobile && flutter test)
```

### Windows PowerShell

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform

Push-Location backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat clean test
Pop-Location

Push-Location web
npm test
npm run build
Pop-Location

Push-Location mobile
flutter test
Pop-Location
```

## 종료 방법

### Bash

Foreground로 실행한 Backend, Web, Mobile은 해당 터미널에서 `Ctrl+C`를 눌러 종료합니다.

PostgreSQL과 Redis를 종료합니다.

```bash
docker compose stop
```

컨테이너까지 제거하되 DB·Redis 데이터는 유지합니다.

```bash
docker compose down
```

DB·Redis 데이터 볼륨까지 삭제하려면 다음 명령을 사용합니다.

```bash
docker compose down --volumes
```

> `--volumes`를 사용하면 로컬 PostgreSQL과 Redis 데이터가 삭제되므로 주의하세요.

### Windows PowerShell

Foreground로 실행한 프로세스는 각각의 PowerShell 창에서 `Ctrl+C`를 눌러 종료합니다.

- Backend: `gradlew.bat bootRun`을 실행한 창에서 `Ctrl+C`
- Web: `npm run dev`를 실행한 창에서 `Ctrl+C`
- Mobile: `flutter run`을 실행한 창에서 `q` 또는 `Ctrl+C`

PostgreSQL과 Redis를 종료합니다.

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform
docker compose stop
```

컨테이너까지 제거하되 데이터는 유지합니다.

```powershell
docker compose down
```

DB·Redis 데이터 볼륨까지 삭제합니다.

```powershell
docker compose down --volumes
```

> `docker compose down --volumes`는 로컬 DB와 Redis 데이터를 삭제합니다.

### 포트가 계속 사용 중일 때

포트를 사용하는 프로세스를 확인합니다.

```powershell
Get-NetTCPConnection -LocalPort 8080, 5173 |
    Select-Object LocalPort, State, OwningProcess
```

프로세스 정보를 확인합니다.

```powershell
Get-Process -Id <OwningProcess>
```

필요한 프로세스임을 확인한 후 종료합니다.

```powershell
Stop-Process -Id <OwningProcess>
```

정상 종료되지 않을 때만 강제 종료합니다.

```powershell
Stop-Process -Id <OwningProcess> -Force
```
