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