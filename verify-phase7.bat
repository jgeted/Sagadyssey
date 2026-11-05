@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\verify-phase7.ps1"
if errorlevel 1 (
    echo Phase 7 verification FAILED. Check the error above.
) else (
    echo Phase 7 verification PASSED.
)
pause
