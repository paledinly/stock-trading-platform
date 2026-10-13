<#
.SYNOPSIS
    Keeps the backend running: builds the boot jar once, runs it with java -jar and restarts it when it exits.

.DESCRIPTION
    The working directory is backend/, so spring.config.import (optional:file:.env, optional:file:../.env)
    still loads the repository-root .env exactly as with gradlew bootRun. The jar is copied to .run/ so a
    running backend never locks build/libs for later gradle builds.

    Output goes to .run/logs (gitignored): runner.log plus one .out.log/.err.log pair per backend start.
    Environment variables and credentials are never printed.

    Only one runner may be active (named mutex). If the previous backend process survived its runner
    (for example the scheduled task was stopped), it is adopted and waited on instead of starting a second
    collector. If another process already listens on the port, the runner waits instead of starting.

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
    [string]$JavaOptions = '',
    [switch]$SkipBuild,
    [switch]$UseBootRun
)

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

function Write-RunnerLog([string]$message) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message
    Add-Content -Path $runnerLog -Value $line -Encoding UTF8
    Write-Host $line
}

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

function Test-PortBusy {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $listener
}

function Remove-OldLogs {
    $limit = (Get-Date).AddDays(-$LogRetentionDays)
    Get-ChildItem -Path $logDir -Filter 'backend-*.log' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $limit } | Remove-Item -Force -ErrorAction SilentlyContinue
}

function Resolve-Java {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw 'java.exe not found (set JAVA_HOME to a Java 21 runtime)' }
    return $command.Source
}

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

$mutex = New-Object System.Threading.Mutex($false, 'Local\stock-platform-backend-runner')
if (-not $mutex.WaitOne(0)) {
    Write-RunnerLog 'Another run-backend.ps1 is already active; exiting.'
    exit 0
}

try {
    Write-RunnerLog "Runner started (pid $PID, mode $(if ($UseBootRun) { 'bootRun' } else { 'java -jar' }))"
    $previous = Get-PreviousBackend
    if ($null -ne $previous) {
        Write-RunnerLog "Adopting running backend pid $($previous.Id); waiting for it to exit"
        $previous.WaitForExit()
        Write-RunnerLog "Adopted backend pid $($previous.Id) exited"
    }

    if (-not $UseBootRun -and -not $SkipBuild) {
        if (-not (Build-Jar)) {
            if (Test-Path $jarPath) { Write-RunnerLog 'Falling back to the previously copied jar' }
            else { throw 'No backend jar is available; fix the build and restart the runner' }
        }
    } elseif (-not $UseBootRun -and -not (Test-Path $jarPath)) {
        throw "-SkipBuild was given but $jarPath does not exist"
    }

    $starts = @()
    while ($true) {
        Remove-OldLogs
        if (Test-PortBusy) {
            Write-RunnerLog "Port $Port is already in use by another process; checking again in 60s"
            Start-Sleep -Seconds 60
            continue
        }
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
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
