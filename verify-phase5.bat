@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\verify-phase5.ps1"
if errorlevel 1 (
    echo Phase 5 verification FAILED. Check the error above.
) else (
    echo Phase 5 verification PASSED.
)
pause
