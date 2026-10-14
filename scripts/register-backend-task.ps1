<#
.SYNOPSIS
    Registers (or removes) a per-user Task Scheduler task that starts scripts/run-backend.ps1 at logon.

.DESCRIPTION
    Idempotent: re-running replaces the existing task definition. Settings: runs only while the current
    user is logged on, restarts on failure, no execution time limit, starts and keeps running on battery,
    ignores a second instance. Nothing is started unless -StartNow is given.
    [한국어 요약]
    PowerShell(윈도우 명령 셸) 스크립트로, 작업 스케줄러(윈도우가 정해진 때 프로그램을 실행해 주는 기능)에
    로그온 시 run-backend.ps1 을 켜는 작업을 등록하거나 지워요. 다시 실행하면 기존 작업 정의를 덮어써요.
    개발자가 PC에서 백엔드를 늘 켜 두고 싶을 때 한 번 직접 실행하는 용도예요.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\register-backend-task.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\register-backend-task.ps1 -StartNow
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\register-backend-task.ps1 -Unregister
#>
[CmdletBinding()]
param(
    [string]$TaskName = 'StockPlatformBackend',
    [string]$RunnerArguments = '',
    [switch]$StartNow,
    [switch]$Unregister
)

# 오류가 나면 바로 멈추도록 해요. 저장소 루트, 실행기 경로, PID 파일, 현재 사용자를 계산해요.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $PSScriptRoot 'run-backend.ps1'
$pidFile = Join-Path $repoRoot '.run\backend.pid'
$user = "$env:USERDOMAIN\$env:USERNAME"

# 하는 일: run-backend.ps1 이 남긴 PID 파일로 백엔드 java 프로세스를 찾아 종료해요.
# 받는 것: 없음(스크립트 변수 사용). 돌려주는 것: 없음.
# 명령줄에 backend.jar 또는 bootRun 이 있을 때만 종료해서, 재사용된 PID의 다른 프로그램을 건드리지 않아요.
function Stop-RunnerBackend {
    # Stopping the task ends the runner but not its java child; stop that backend explicitly.
    if (-not (Test-Path $pidFile)) { return }
    $backendId = 0
    if (-not [int]::TryParse((Get-Content $pidFile -TotalCount 1), [ref]$backendId)) { return }
    $info = Get-CimInstance Win32_Process -Filter "ProcessId = $backendId" -ErrorAction SilentlyContinue
    if ($null -ne $info -and ($info.CommandLine -like '*backend.jar*' -or $info.CommandLine -like '*bootRun*')) {
        Stop-Process -Id $backendId -Force -Confirm:$false
        Write-Host "Stopped backend pid $backendId"
    }
    Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
}

$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue

# -Unregister: 작업을 멈추고 지운 뒤, 남아 있는 백엔드 프로세스도 정리해요.
if ($Unregister) {
    if ($null -eq $existing) {
        Write-Host "Task '$TaskName' is not registered"
    } else {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "Unregistered task '$TaskName'"
    }
    Stop-RunnerBackend
    exit 0
}

if (-not (Test-Path $runner)) { throw "Runner script not found: $runner" }

# 창 없이(Hidden) 실행기를 띄우는 인자를 만들어요. -RunnerArguments 는 실행기에 그대로 덧붙여요.
# 이어서 로그온 트리거, 현재 사용자 권한, 그리고 설정을 만들어요.
# 설정: 배터리에서도 실행, 실행 시간 제한 없음, 실패 시 1분 간격 재시작, 중복 실행 무시.
# 실행기가 자체 mutex 로도 중복을 막지만 스케줄러 단계에서도 한 번 더 막아요.
$argumentLine = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$runner`""
if ($RunnerArguments) { $argumentLine += " $RunnerArguments" }
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argumentLine -WorkingDirectory $repoRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew `
    -StartWhenAvailable

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
    -Settings $settings -Description 'Keeps the stock-trading-platform backend running (scripts/run-backend.ps1).' `
    -Force | Out-Null
Write-Host ("{0} task '{1}' for {2}" -f $(if ($null -eq $existing) { 'Registered' } else { 'Updated' }), $TaskName, $user)

# -StartNow 가 있을 때만 바로 실행해요. 없으면 다음 로그온 때부터 동작해요.
if ($StartNow) {
    Start-ScheduledTask -TaskName $TaskName
    Write-Host "Started task '$TaskName'"
}
