@echo off
setlocal
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0scripts\start-war-acceptance.ps1" %*
set "warExit=%ERRORLEVEL%"
if not "%warExit%"=="0" if "%~1"=="" pause
exit /b %warExit%
