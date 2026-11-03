@echo off
rem Nightly shadow Forward (README.md). Scheduled by Windows Task Scheduler "StockPlatform-ShadowForward" at 21:30.
cd /d "%~dp0"
if not exist records mkdir records
set PYTHONIOENCODING=utf-8
"%LOCALAPPDATA%\Microsoft\WindowsApps\python.exe" shadow_daily.py >> records\task.log 2>&1
