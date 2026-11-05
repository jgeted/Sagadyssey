@echo off
setlocal
pushd "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\verify-phase10.ps1"
set "PHASE10_EXIT=%ERRORLEVEL%"
popd
if not "%PHASE10_EXIT%"=="0" pause
endlocal & exit /b %PHASE10_EXIT%
