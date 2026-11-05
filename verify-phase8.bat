@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\verify-phase8.ps1"
set "PHASE8_EXIT=%ERRORLEVEL%"
if not "%PHASE8_EXIT%"=="0" pause
endlocal & exit /b %PHASE8_EXIT%
