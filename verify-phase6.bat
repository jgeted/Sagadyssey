@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\verify-phase6.ps1"
if errorlevel 1 (
    echo Phase 6 verification FAILED. Check the error above.
) else (
    echo Phase 6 verification PASSED.
)
pause
