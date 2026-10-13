<#
.SYNOPSIS
    Registers (or removes) a per-user Task Scheduler task that starts scripts/run-backend.ps1 at logon.

.DESCRIPTION
    Idempotent: re-running replaces the existing task definition. Settings: runs only while the current
    user is logged on, restarts on failure, no execution time limit, starts and keeps running on battery,
    ignores a second instance. Nothing is started unless -StartNow is given.

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

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $PSScriptRoot 'run-backend.ps1'
$pidFile = Join-Path $repoRoot '.run\backend.pid'
$user = "$env:USERDOMAIN\$env:USERNAME"

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

if ($StartNow) {
    Start-ScheduledTask -TaskName $TaskName
    Write-Host "Started task '$TaskName'"
}
