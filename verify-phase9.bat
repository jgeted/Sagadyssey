@echo off
setlocal
pushd "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\verify-phase9.ps1"
set "PHASE9_EXIT=%ERRORLEVEL%"
popd
if not "%PHASE9_EXIT%"=="0" pause
endlocal & exit /b %PHASE9_EXIT%
