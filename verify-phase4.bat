@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\verify-phase4.ps1"
if errorlevel 1 (
    echo Phase 4 verification FAILED. Check the error above.
) else (
    echo Phase 4 verification PASSED.
)
pause
