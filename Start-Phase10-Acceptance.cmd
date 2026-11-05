@echo off
setlocal
title Sagadyssey Stage 10 Acceptance Copy
pushd "%~dp0"
if errorlevel 1 exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-phase10-client.ps1" %*
set "SAGA_EXIT=%ERRORLEVEL%"
popd
if not "%SAGA_EXIT%"=="0" pause
endlocal & exit /b %SAGA_EXIT%
