<#
.SYNOPSIS
    Starts, stops, restarts and inspects the backend kept alive by run-backend.ps1.
.DESCRIPTION
    backend start    : run-backend.ps1 을 별도 최소화 창에서 실행하고 health UP 까지 기다려요(시작할 때 jar 를 새로 빌드).
    backend stop     : 감시자(run-backend.ps1) → 백엔드 순서로 끝내요. 감시자가 다시 띄우지 않아요.
    backend restart  : stop 후 start. 코드 변경까지 반영돼요.
    backend status   : 감시자·백엔드 PID 와 시작 시각, health, 실시간 연결·구독 수.
    backend logs     : 현재 백엔드 로그 마지막 부분(-Lines 로 줄 수 지정).
    인자 없이 실행하면 상태와 사용법만 보여 줘요.
    장중(평일 08:55~15:45 KST)에 stop/restart 를 하면 확인을 받아요(-Force 로 생략).
    환경변수·인증정보는 출력하지 않아요.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'restart', 'status', 'logs', 'help')]
    [string]$Command = 'help',
    [int]$Lines = 50,
    [int]$StartTimeoutSeconds = 180,
    [int]$Port = 8080,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $PSScriptRoot 'run-backend.ps1'
$runDir = Join-Path $repoRoot '.run'
$runnerLog = Join-Path $runDir 'logs\runner.log'
$pidFile = Join-Path $runDir 'backend.pid'
$baseUrl = "http://localhost:$Port"

# 하는 일: 한 줄을 색과 함께 출력해요.
function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }

# 하는 일: runner.log 마지막 줄들에서 가장 최근 "Runner started (pid N" 와 "Backend started (pid N, log: 경로)" 를 찾아요.
function Read-RunnerLog {
    $result = @{ RunnerPid = $null; RunnerAt = $null; BackendLog = $null }
    if (-not (Test-Path $runnerLog)) { return $result }
    $tail = Get-Content $runnerLog -Tail 200 -Encoding UTF8
    foreach ($line in $tail) {
        if ($line -match '^(\S+ \S+) Runner started \(pid (\d+)') { $result.RunnerAt = [datetime]$Matches[1]; $result.RunnerPid = [int]$Matches[2] }
        if ($line -match 'Backend started \(pid \d+, log: (.+)\)$') { $result.BackendLog = $Matches[1] }
    }
    return $result
}

# 하는 일: 살아 있는 감시자 프로세스를 찾아요. 명령줄에 run-backend.ps1 이 보이거나(별도 창·작업 스케줄러),
# runner.log 의 최근 PID 가 그 시각에 시작된 powershell 이면(터미널에서 직접 실행) 감시자로 봐요.
function Get-Runner {
    $byCommand = Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'pwsh.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*run-backend.ps1*' -and $_.ProcessId -ne $PID } | Select-Object -First 1
    if ($null -ne $byCommand) { return Get-Process -Id $byCommand.ProcessId -ErrorAction SilentlyContinue }
    $info = Read-RunnerLog
    if ($null -eq $info.RunnerPid) { return $null }
    $process = Get-Process -Id $info.RunnerPid -ErrorAction SilentlyContinue
    if ($null -eq $process -or $process.ProcessName -notmatch '^(powershell|pwsh)$') { return $null }
    # PID 재사용을 피하려고, 로그의 시작 시각보다 늦게 시작된 프로세스는 다른 것으로 봐요.
    if ($null -ne $info.RunnerAt -and $process.StartTime -gt $info.RunnerAt.AddSeconds(5)) { return $null }
    return $process
}

# 하는 일: 백엔드(java) 프로세스를 찾아요. backend.pid 를 먼저 보고, 없으면 포트를 쓰는 프로세스를 봐요.
function Get-Backend {
    if (Test-Path $pidFile) {
        $id = 0
        if ([int]::TryParse((Get-Content $pidFile -TotalCount 1), [ref]$id)) {
            $info = Get-CimInstance Win32_Process -Filter "ProcessId = $id" -ErrorAction SilentlyContinue
            if ($null -ne $info -and ($info.CommandLine -like '*backend.jar*' -or $info.CommandLine -like '*bootRun*')) {
                return Get-Process -Id $id -ErrorAction SilentlyContinue
            }
        }
    }
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $listener) { return Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue }
    return $null
}

# 하는 일: health 와 실시간 상태를 짧게 조회해요. 실패하면 null 이에요.
function Get-Health {
    try { return (Invoke-RestMethod "$baseUrl/actuator/health" -TimeoutSec 3).status } catch { return $null }
}
function Get-Realtime {
    try { return Invoke-RestMethod "$baseUrl/api/v1/market/realtime/status" -TimeoutSec 5 } catch { return $null }
}

# 하는 일: 지금이 장중 보호 구간(평일 08:55~15:45 KST)인지 확인해요. 휴장일은 구분하지 않아요.
function Test-MarketHours {
    $kst = [System.TimeZoneInfo]::ConvertTimeBySystemTimeZoneId((Get-Date), 'Korea Standard Time')
    if ($kst.DayOfWeek -in 'Saturday', 'Sunday') { return $false }
    $minutes = $kst.Hour * 60 + $kst.Minute
    return $minutes -ge (8 * 60 + 55) -and $minutes -lt (15 * 60 + 45)
}

# 하는 일: 장중이면 경고하고 yes 를 입력받아요. -Force 면 묻지 않아요.
function Confirm-OutsideMarket([string]$action) {
    if ($Force -or -not (Test-MarketHours)) { return $true }
    Say "지금은 장중 보호 구간(평일 08:55~15:45)입니다. $action 하면 실시간 수집이 끊기고 정밀 구독이 다시 배정됩니다." 'Yellow'
    Say '14:00~15:45 는 공식 평가 구간이라 특히 피해야 합니다.' 'Yellow'
    $answer = Read-Host '그래도 진행하려면 yes 를 입력하세요'
    return $answer -eq 'yes'
}

function Show-Status {
    $runnerProcess = Get-Runner
    $backendProcess = Get-Backend
    if ($null -ne $runnerProcess) { Say ("감시자   : 실행 중 (pid {0}, 시작 {1:MM-dd HH:mm:ss})" -f $runnerProcess.Id, $runnerProcess.StartTime) 'Green' }
    else { Say '감시자   : 없음' 'Yellow' }
    if ($null -ne $backendProcess) { Say ("백엔드   : 실행 중 (pid {0}, 시작 {1:MM-dd HH:mm:ss})" -f $backendProcess.Id, $backendProcess.StartTime) 'Green' }
    else { Say '백엔드   : 없음' 'Yellow' }
    $health = Get-Health
    Say ("health   : {0}" -f $(if ($health) { $health } else { '응답 없음' })) $(if ($health -eq 'UP') { 'Green' } else { 'Yellow' })
    $status = Get-Realtime
    if ($null -ne $status) {
        $live = $status.realtime
        $connected = if ($live.connected) { '연결됨' } else { '연결 안 됨(장 시간 밖이면 정상)' }
        Say ("실시간   : {0} · 구독 {1}/{2} · 수신 틱 {3:N0}" -f $connected, $status.subscriptionCount, $status.subscriptionLimit, $live.receivedTicks)
    }
}

function Stop-Backend {
    if (-not (Confirm-OutsideMarket '중지')) { Say '취소했습니다.'; return $false }
    $runnerProcess = Get-Runner
    $backendProcess = Get-Backend
    # 감시자를 먼저 끝내야 백엔드를 끈 뒤 30초 후 다시 띄우지 않아요.
    if ($null -ne $runnerProcess) {
        Say "감시자 종료 (pid $($runnerProcess.Id))"
        Stop-Process -Id $runnerProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($null -ne $backendProcess) {
        Say "백엔드 종료 (pid $($backendProcess.Id))"
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
    }
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
        Say "포트 $Port 를 아직 다른 프로세스가 쓰고 있습니다. backend status 로 확인하세요." 'Red'
        return $false
    }
    Say '중지했습니다.' 'Green'
    return $true
}

function Start-Backend {
    if ($null -ne (Get-Runner)) { Say '감시자가 이미 실행 중입니다. 코드 변경을 반영하려면 backend restart 를 쓰세요.' 'Yellow'; Show-Status; return }
    if ($null -ne (Get-Backend)) { Say "감시자 없이 백엔드가 떠 있습니다. 먼저 backend stop 하세요." 'Yellow'; Show-Status; return }
    $since = Get-Date
    Say '감시자를 별도 최소화 창에서 시작합니다(jar 빌드 포함).'
    Start-Process -FilePath 'powershell.exe' -WindowStyle Minimized -WorkingDirectory $repoRoot `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$runner`"") | Out-Null
    $deadline = $since.AddSeconds($StartTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if ((Get-Health) -eq 'UP') {
            Say ("health UP ({0:N0}초)" -f ((Get-Date) - $since).TotalSeconds) 'Green'
            Show-Status
            return
        }
    }
    Say "${StartTimeoutSeconds}초 안에 health UP 이 되지 않았습니다. backend logs 와 .run\logs\runner.log 를 확인하세요." 'Red'
}

function Show-Logs {
    $info = Read-RunnerLog
    if ($null -eq $info.BackendLog -or -not (Test-Path $info.BackendLog)) { Say '백엔드 로그를 찾지 못했습니다(.run\logs).' 'Yellow'; return }
    Say "로그: $($info.BackendLog)" 'DarkGray'
    Get-Content $info.BackendLog -Tail $Lines -Encoding UTF8
}

switch ($Command) {
    'status' { Show-Status }
    'logs' { Show-Logs }
    'stop' { [void](Stop-Backend) }
    'start' { Start-Backend }
    'restart' { if (Stop-Backend) { Start-Backend } }
    default {
        Show-Status
        Say ''
        Say '사용법: backend.ps1 <start|stop|restart|status|logs> [-Force] [-Lines 50]'
        Say '  start   감시자를 별도 창에서 시작(jar 새로 빌드)  restart  중지 후 시작(코드 변경 반영)'
        Say '  stop    감시자 → 백엔드 순서로 중지                status   상태      logs  최근 로그'
    }
}
