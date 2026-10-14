<#
.SYNOPSIS
    Keeps the backend running: builds the boot jar once, runs it with java -jar and restarts it when it exits.

.DESCRIPTION
    The working directory is backend/, so spring.config.import (optional:file:.env, optional:file:../.env)
    still loads the repository-root .env exactly as with gradlew bootRun. The jar is copied to .run/ so a
    running backend never locks build/libs for later gradle builds.

    Output goes to .run/logs (gitignored): runner.log (rotated to runner.log.1 above -RunnerLogMaxBytes)
    plus one .out.log/.err.log pair per backend start.
    Environment variables and credentials are never printed.

    Only one runner may be active (named mutex). If the previous backend process survived its runner
    (for example the scheduled task was stopped), it is adopted and waited on instead of starting a second
    collector. If another process already listens on the port, the runner waits instead of starting.

    [한국어 요약]
    PowerShell(윈도우 명령 셸) 스크립트로, 백엔드를 빌드한 뒤 java -jar 로 띄우고 꺼지면 다시 켜는 감시자예요.
    경비원이 정문을 지키듯, 같은 실행기는 하나만 돌고 포트가 이미 쓰이면 기다려요.
    register-backend-task.ps1 이 등록한 작업 스케줄러 작업이 실행하고, 수동으로 직접 실행해도 돼요.
    로그는 .run/logs 에 남기며 환경변수나 인증정보는 출력하지 않아요.

.PARAMETER SkipBuild
    Reuse .run/backend.jar instead of running gradlew bootJar first.

.PARAMETER UseBootRun
    Run gradlew bootRun instead of java -jar (fallback; slower to start and holds gradle resources).
#>
[CmdletBinding()]
param(
    [int]$Port = 8080,
    [int]$RestartDelaySeconds = 30,
    [int]$MaxRestartsPerHour = 6,
    [int]$LogRetentionDays = 14,
    [long]$RunnerLogMaxBytes = 10MB,
    [string]$JavaOptions = '',
    [switch]$SkipBuild,
    [switch]$UseBootRun
)

# 경로 계산: 모든 산출물(jar, pid, 로그)은 gitignore 된 .run 폴더에 모아요.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot 'backend'
$runDir = Join-Path $repoRoot '.run'
$logDir = Join-Path $runDir 'logs'
$jarPath = Join-Path $runDir 'backend.jar'
$pidFile = Join-Path $runDir 'backend.pid'
$gradlew = Join-Path $backendDir 'gradlew.bat'
$runnerLog = Join-Path $logDir 'runner.log'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# 하는 일: 시각을 붙여 runner.log 와 화면에 한 줄을 남겨요.
# 받는 것: 메시지 문자열. 돌려주는 것: 없음.
function Write-RunnerLog([string]$message) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message
    Add-Content -Path $runnerLog -Value $line -Encoding UTF8
    Write-Host $line
}

# 하는 일: 이전 실행기가 띄운 백엔드가 아직 살아 있으면 그 프로세스를 찾아요.
# 받는 것: 없음. 돌려주는 것: Process 객체 또는 $null.
function Get-PreviousBackend {
    if (-not (Test-Path $pidFile)) { return $null }
    $previousId = 0
    if (-not [int]::TryParse((Get-Content $pidFile -TotalCount 1), [ref]$previousId)) { return $null }
    # Only adopt the exact backend this runner started (PIDs are reused by unrelated processes).
    $info = Get-CimInstance Win32_Process -Filter "ProcessId = $previousId" -ErrorAction SilentlyContinue
    if ($null -eq $info -or -not ($info.CommandLine -like '*backend.jar*' -or $info.CommandLine -like '*bootRun*')) {
        return $null
    }
    return Get-Process -Id $previousId -ErrorAction SilentlyContinue
}

# 하는 일: 지정 포트에 이미 대기(Listen) 중인 프로그램이 있는지 확인해요. 돌려주는 것: $true/$false.
function Test-PortBusy {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $listener
}

# 하는 일: 보존 기간(LogRetentionDays)보다 오래된 backend-*.log 파일을 지우고, runner.log 를 크기 기준으로 돌려요.
# runner.log 가 RunnerLogMaxBytes(기본 10MB)를 넘으면 runner.log.1 로 이름을 바꿔(이전 runner.log.1 은 지워요)
# 새 runner.log 에 이어 써요. mutex 를 쥔 실행기만 부르므로 두 실행기가 동시에 돌리지 않아요.
function Remove-OldLogs {
    $limit = (Get-Date).AddDays(-$LogRetentionDays)
    Get-ChildItem -Path $logDir -Filter 'backend-*.log' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $limit } | Remove-Item -Force -ErrorAction SilentlyContinue
    $current = Get-Item -Path $runnerLog -ErrorAction SilentlyContinue
    if ($null -ne $current -and $current.Length -gt $RunnerLogMaxBytes) {
        $rotated = "$runnerLog.1"
        try {
            Move-Item -Path $runnerLog -Destination $rotated -Force -ErrorAction Stop
            Write-RunnerLog "Rotated runner.log ($($current.Length) bytes) to runner.log.1"
        } catch {
            Write-RunnerLog "runner.log rotation failed: $($_.Exception.Message)"
        }
    }
}

# 하는 일: 실행할 java.exe 경로를 찾아요. JAVA_HOME 을 먼저 보고, 없으면 PATH 에서 찾아요.
# 돌려주는 것: java.exe 경로. 못 찾으면 예외를 던져요.
function Resolve-Java {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw 'java.exe not found (set JAVA_HOME to a Java 21 runtime)' }
    return $command.Source
}

# 하는 일: gradlew bootJar 로 실행용 jar 를 만들고 .run/backend.jar 로 복사해요.
# 받는 것: 없음. 돌려주는 것: 성공하면 $true, 실패하면 $false.
# 복사본을 쓰는 이유는 실행 중인 jar 가 build/libs 를 잠가 다음 빌드를 막지 않게 하려는 거예요.
# 빌드 결과 중 -plain.jar(의존성 없는 jar)는 빼고 가장 최근 boot jar 를 골라요.
#
# (바로 아래 Start-Backend 함수 설명도 여기에 함께 적어요.)
# Start-Backend 하는 일: 백엔드 프로세스를 하나 띄우고 PID 파일에 기록해요.
# 받는 것: 없음(스크립트 매개변수 사용). 돌려주는 것: 시작된 Process 객체.
# JavaOptions 문자열은 공백으로 나눠 인자로 넘겨요. 따옴표로 묶인 공백 포함 값은 나뉠 수 있어요.
function Build-Jar {
    $env:GRADLE_USER_HOME = Join-Path $backendDir '.gradle-user-home'
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $buildLog = Join-Path $logDir "backend-build-$stamp.out.log"
    Write-RunnerLog "Building boot jar (log: $buildLog)"
    $build = Start-Process -FilePath $gradlew -ArgumentList 'bootJar', '--console=plain' `
        -WorkingDirectory $backendDir -RedirectStandardOutput $buildLog `
        -RedirectStandardError (Join-Path $logDir "backend-build-$stamp.err.log") `
        -WindowStyle Hidden -PassThru
    $null = $build.Handle
    $build.WaitForExit()
    if ($build.ExitCode -ne 0) { Write-RunnerLog "Build failed with exit code $($build.ExitCode)"; return $false }
    $jar = Get-ChildItem -Path (Join-Path $backendDir 'build\libs') -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $jar) { Write-RunnerLog 'Build produced no boot jar'; return $false }
    Copy-Item -Path $jar.FullName -Destination $jarPath -Force
    Write-RunnerLog "Using $($jar.Name)"
    return $true
}

function Start-Backend {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $out = Join-Path $logDir "backend-$stamp.out.log"
    $err = Join-Path $logDir "backend-$stamp.err.log"
    if ($UseBootRun) {
        $env:GRADLE_USER_HOME = Join-Path $backendDir '.gradle-user-home'
        $process = Start-Process -FilePath $gradlew -ArgumentList 'bootRun', '--console=plain' `
            -WorkingDirectory $backendDir -RedirectStandardOutput $out -RedirectStandardError $err `
            -WindowStyle Hidden -PassThru
    } else {
        $arguments = @()
        if ($JavaOptions) { $arguments += $JavaOptions -split '\s+' | Where-Object { $_ } }
        $arguments += @('-jar', "`"$jarPath`"")
        $process = Start-Process -FilePath (Resolve-Java) -ArgumentList $arguments `
            -WorkingDirectory $backendDir -RedirectStandardOutput $out -RedirectStandardError $err `
            -WindowStyle Hidden -PassThru
    }
    $null = $process.Handle  # keeps ExitCode readable on Windows PowerShell 5.1
    Set-Content -Path $pidFile -Value $process.Id -Encoding ASCII
    Write-RunnerLog "Backend started (pid $($process.Id), log: $out)"
    return $process
}

# 이름 있는 mutex(동시에 한 명만 쥘 수 있는 자물쇠)로 실행기가 둘 이상 돌지 않게 해요.
$mutex = New-Object System.Threading.Mutex($false, 'Local\stock-platform-backend-runner')
if (-not $mutex.WaitOne(0)) {
    Write-RunnerLog 'Another run-backend.ps1 is already active; exiting.'
    exit 0
}

try {
    Write-RunnerLog "Runner started (pid $PID, mode $(if ($UseBootRun) { 'bootRun' } else { 'java -jar' }))"
    # 이전 실행기가 남긴 백엔드가 살아 있으면 새로 띄우지 않고 그 프로세스가 끝날 때까지 기다려요.
    $previous = Get-PreviousBackend
    if ($null -ne $previous) {
        Write-RunnerLog "Adopting running backend pid $($previous.Id); waiting for it to exit"
        $previous.WaitForExit()
        Write-RunnerLog "Adopted backend pid $($previous.Id) exited"
    }

    # 기본 모드에서는 먼저 빌드해요. 빌드가 실패해도 예전 jar 가 있으면 그걸로 계속 실행해요.
    if (-not $UseBootRun -and -not $SkipBuild) {
        if (-not (Build-Jar)) {
            if (Test-Path $jarPath) { Write-RunnerLog 'Falling back to the previously copied jar' }
            else { throw 'No backend jar is available; fix the build and restart the runner' }
        }
    } elseif (-not $UseBootRun -and -not (Test-Path $jarPath)) {
        throw "-SkipBuild was given but $jarPath does not exist"
    }

    # 감시 루프: 포트가 비어 있고 시간당 재시작 한도(MaxRestartsPerHour) 안이면 백엔드를 띄우고,
    # 프로세스가 끝나면 RestartDelaySeconds 만큼 쉬었다가 다시 띄워요.
    $starts = @()
    while ($true) {
        Remove-OldLogs
        if (Test-PortBusy) {
            Write-RunnerLog "Port $Port is already in use by another process; checking again in 60s"
            Start-Sleep -Seconds 60
            continue
        }
        # 최근 1시간 안의 시작 시각만 남겨 한도를 계산해요. 한도를 넘으면 가장 오래된 시작이 1시간 지날 때까지 기다려요.
        $cutoff = (Get-Date).AddHours(-1)
        $starts = @($starts | Where-Object { $_ -ge $cutoff })
        if ($starts.Count -ge $MaxRestartsPerHour) {
            $wait = [math]::Ceiling(($starts[0].AddHours(1) - (Get-Date)).TotalSeconds) + 1
            Write-RunnerLog "Restart cap reached ($MaxRestartsPerHour starts/hour); waiting ${wait}s"
            Start-Sleep -Seconds ([math]::Max(1, $wait))
            continue
        }
        $starts += Get-Date
        $process = Start-Backend
        $process.WaitForExit()
        Write-RunnerLog "Backend pid $($process.Id) exited with code $($process.ExitCode); restarting in ${RestartDelaySeconds}s"
        Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds $RestartDelaySeconds
    }
} catch {
    Write-RunnerLog "Runner stopped: $($_.Exception.Message)"
    exit 1
} finally {
    # 어떤 경로로 끝나든 mutex 를 풀어 다음 실행기가 시작될 수 있게 해요.
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
